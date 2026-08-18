# Anonymous Telemetry — Plan

Status: **planned, not started.** Verified against the tree at `f07a178`.
Backend decision: **self-hosted Aptabase.**

## 1. What this is

A small, closed-vocabulary event stream that answers one question the issue
tracker cannot: *where does setup break, and how often does dictation fail, for
people who never file a bug?* It is the only feedback channel this product has
that does not require the user to write an email.

Everything below is built around a single constraint: VocaPhone's whole
proposition is that nothing leaves the phone unless the user sent it somewhere
they own. Telemetry is the first feature that violates the letter of that
promise, so it has to be built so it does not violate the spirit — and the
existing copy has to be corrected rather than quietly left standing.

### The three decisions this plan settles

| Decision | Value | § |
| --- | --- | --- |
| Backend | **Self-hosted Aptabase** — AGPL-3.0 server, three containers, no third-party processor | §3 |
| Client | **Hand-rolled sender against Aptabase's documented ingest API**, not the SDK | §4.4 |
| Default | **Opt-in on the onboarding screen**, with a one-constant switch if you disagree | §6 |
| F-Droid | **Compiled out of the `fdroid` flavor entirely** | §9 |

---

## 2. What this breaks, exactly

Eight places currently promise no analytics. Shipping without changing all of
them is the actual breach — not the telemetry itself.

| File | Line | Text |
| --- | --- | --- |
| `docs/privacy.md` | 36 | "No analytics or third-party transcription" |
| `README.md` | 92 | "There is no analytics SDK and no…" |
| `README.md` | 143 | "no analytics, and no third-party transcription" |
| `web/index.html` | 595 | "No analytics SDK quietly keeping score." |
| `fastlane/…/full_description.txt` | 3 | "no accounts, no analytics SDK, and no subscription" |
| `docs/testflight.md` | 48–49 | "No analytics, no advertising, no third-party data sharing." |
| `ios/…/SetupView.swift` | 345 | "…transcription or analytics service is involved." |
| `ios/…/SettingsView.swift` | 842 | "No third-party transcription or analytics service is used." |

Note that **"no analytics *SDK*" survives this plan intact**, because §4.4
hand-rolls the sender — no third-party analytics code is linked into either
binary. That is a real, checkable claim and it is one of the reasons to
hand-roll. But leaning on that wording alone would be weaselly. The honest
replacement:

> No third-party analytics service, no advertising, and no profile of you. If
> you turn on usage reporting, VocaPhone sends a fixed list of counters to an
> Aptabase server VocaHQ runs — never text, audio, or your gateway address. It
> stores nothing on your phone to identify you. It is off until you turn it on,
> and you can turn it off again in Settings.

Also on the record: `Plan-Beta-Release.md:233` calls the absence of analytics
"correct and non-negotiable". That was written for 0.1.0. Re-opening it is a
deliberate change of position and should be noted in `docs/decisions.md`.

---

## 3. The backend: self-hosted Aptabase

Chosen over self-hosted PostHog and over a first-party receiver. It is
purpose-built for exactly this problem — privacy-first analytics for mobile
apps — and its identity model (§4.2) is better than anything I would have
designed by hand.

### 3.0 First, take the free data off the table

Before writing any client code, note what you already get with zero privacy
cost and zero code:

- **Play Console → Android vitals**: crash rate, ANR rate, per-device and
  per-OS breakdowns, install/uninstall counts, retention cohorts.
- **App Store Connect → Metrics**: crashes, launch time, installs, deletions,
  OS/device distribution — sourced from users who opted into Apple's own
  "Share iPhone Analytics", so the consent burden is Apple's, not yours.

That covers crashes, device mix, and installs on both platforms, and it covers
them at 100% of users rather than at your opt-in rate. It does **not** cover the
in-app setup funnel or per-stage dictation failures — which is the residue this
feature carries, and why the event list in §5 is short.

### 3.1 The stack

Three containers, one exposed port. From `aptabase/self-hosting`:

| Service | Image | Role |
| --- | --- | --- |
| `aptabase_db` | `postgres:15-alpine` | Accounts, apps, salts |
| `aptabase_events_db` | `clickhouse/clickhouse-server:*-alpine` | Event storage |
| `aptabase` | `ghcr.io/aptabase/aptabase:main` | Ingest + dashboard, host `:8000` → container `:8080` |

Postgres and ClickHouse are internal to the compose network; only 8000 is
published. Key env vars: `BASE_URL`, `AUTH_SECRET`, `DATABASE_URL`,
`CLICKHOUSE_URL`. SMTP is off by default — the first account's activation link
appears in the container logs, which is fine for a single-operator deploy.

For scale: this is three containers against PostHog's eight-service stack
(ClickHouse *plus* Kafka, Zookeeper, Redis, MinIO, workers). A modest VPS
carries it. ClickHouse is still the memory-hungry component — give it room and
cap it explicitly in compose rather than discovering the limit under load.

Pin the image tags. `ghcr.io/aptabase/aptabase:main` is a moving target, and an
analytics backend silently changing its schema under you is a bad afternoon.

### 3.2 Licensing

- **Server: AGPL-3.0.** Same licence as VocaPhone, so no friction — but the
  network copyleft is live. If you patch the server (§4.3 discusses one reason
  you might), you must publish the modified source to its users. In practice
  that means a public fork, which is a *trust asset* here rather than a cost.
- **SDKs: MIT.** Compatible with linking into an AGPL app, if you go that route.

### 3.3 Operational setup

1. Deploy behind your existing TLS termination. Ingest is `POST /api/v0/events`;
   the dashboard is the same host.
2. **The reverse proxy must pass the client IP** (`X-Forwarded-For`) — this is
   the opposite of the usual advice, and it is deliberate: Aptabase needs the IP
   *transiently* to compute the daily user hash (§4.2). It does not store it.
3. **Verify that claim yourself, because you can.** After Phase 2 (§12), open
   ClickHouse and inspect the events table schema and a sample row. Confirm no
   column holds a raw address. You are self-hosting precisely so that this is
   checkable rather than a vendor promise — so check it, once, and write the
   date you checked it into `docs/privacy.md`.
4. Turn off or truncate proxy access logs for the ingest path. Aptabase not
   storing the IP is worthless if nginx is writing it to disk beside it.
5. Set a ClickHouse TTL on the events table so data ages out — 180 days is a
   reasonable default, and it is a number you can state publicly.
6. Create the app in the dashboard and take the key. Self-hosted keys carry the
   `SH` region prefix — `A-SH-xxxxxxxx` — which is what tells a client it must
   be pointed at a custom host rather than `eu.`/`us.aptabase.com`.

---

## 4. Architecture

Four pieces. Build them in this order.

### 4.1 The closed event vocabulary — the load-bearing decision

There is already a model for this in the tree:
`ios/VocaPhoneShared/DiagnosticLog.swift:20`, whose doc comment reads:

> Deliberately finite and content-free. There is no API here that accepts a
> transcript, typed text, audio path, URL, token, microphone name, or arbitrary
> metadata, so private user content cannot accidentally enter an export.

**Do the same thing again, smaller.** A separate `TelemetryEvent` enum — not a
reuse of `DiagnosticEvent`, which is larger and higher-frequency than anything
that should leave the phone. Every property value is drawn from a closed enum
too. There is no `String` parameter anywhere in the API.

This matters more with Aptabase than it would have with a first-party receiver,
because Aptabase's ingest accepts arbitrary `props` — the discipline has to live
entirely on the client. It is what makes the privacy claim *structural* rather
than a matter of review discipline: not that we are careful about what we send,
but that the type system offers no way to send anything else.

```kotlin
// android/app/src/main/java/com/vocahq/vocaphone/telemetry/TelemetryEvent.kt
enum class TelemetryEvent(val wire: String) {
    APP_FIRST_OPEN("app_first_open"),
    SETUP_STEP_COMPLETED("setup_step_completed"),
    SETUP_FINISHED("setup_finished"),
    SOURCE_SELECTED("source_selected"),
    MODEL_DOWNLOAD_FINISHED("model_download_finished"),
    FIRST_DICTATION_EVER("first_dictation_ever"),
    DICTATION_SUCCEEDED("dictation_succeeded"),
    DICTATION_FAILED("dictation_failed"),
    TELEMETRY_DISABLED("telemetry_disabled"),
}
```

```swift
// ios/VocaPhoneShared/TelemetryEvent.swift — mirrors the above, same wire strings
```

A test asserts the two lists are identical (§11).

### 4.2 Identity: there isn't one, and that is the point

This is where Aptabase earns the choice. **The client stores no identifier at
all.** Instead:

- The server derives an anonymous user hash from **client IP + User-Agent + a
  per-app salt that is discarded every 24 hours**. Same technique Plausible
  uses. Because the salt is thrown away, the same phone on Tuesday and Wednesday
  produces two unrelated hashes, and nobody — including you, with root on the
  box — can join them afterwards.
- The client sends a `sessionId`: epoch-seconds plus 8 random digits, minted at
  launch and rotated after a period of inactivity (roughly an hour in the
  current SDKs; confirm against whatever you pin). It is ephemeral and lives in
  memory.
- No device ID, no IDFV, no ANDROID_ID, no advertising ID, no fingerprint.

Three consequences worth being explicit about:

**It removes the strongest legal objection to an opt-out default.** My earlier
draft leaned on ePrivacy Art. 5(3) — storing or reading information on the
user's terminal equipment requires consent. With Aptabase there is no stored
identifier to trigger it. (The toggle's own boolean is stored, but a
user-preference the user set is a different animal.) The remaining GDPR question
is whether the data is anonymous enough to fall outside it entirely, and a
daily-rotating server-side hash is the strongest version of that argument
available. §6 still recommends opt-in, but on product grounds now, not legal
ones.

**You can say "anonymous" and mean it.** The word was untrue in my first draft,
which proposed a 30-day rotating UUID stored on the device. It is true here.

**It costs you every cross-day question.** This is the real price and it is not
small:

- No retention curves. No "do people still use it after a week."
- No multi-day funnels. "Of people who installed, how many ever reached a first
  transcript?" is *not answerable per-user* if setup spans days.
- No per-user paths through setup.

§5 redesigns the measurement around this rather than pretending otherwise.

### 4.3 The payload, and the fight over `systemProps`

Aptabase's ingest, per its own "build your own SDK" guide:

```
POST {host}/api/v0/events
Content-Type: application/json
App-Key: A-SH-xxxxxxxx
```

```json
[
  {
    "timestamp": "2026-08-18T14:03:44.000Z",
    "sessionId": "175552582412345678",
    "eventName": "dictation_failed",
    "systemProps": {
      "locale": "en",
      "osName": "Android",
      "osVersion": "15",
      "isDebug": false,
      "appVersion": "0.1.0-beta.15",
      "sdkVersion": "vocaphone-telemetry@1"
    },
    "props": {
      "stage": "transcription",
      "reason": "engine_not_ready",
      "source": "on_device",
      "duration_bucket": "10_30s"
    }
  }
]
```

Max **25 events per request** — the queue chunks accordingly.

**The `deviceModel` problem.** Aptabase's official SDKs auto-populate
`systemProps` with `deviceModel` and a full `osVersion`. For this app I would
send neither: Play Console and App Store Connect already give you the device
distribution for free (§3.0), and `deviceModel` + exact OS build + locale is a
usable fingerprint at beta population sizes — which partly undoes the work §4.2
just did.

`systemProps` is not fixed by the protocol, though; it is whatever the client
sends. So:

- **Omit `deviceModel` entirely.**
- **Send `osVersion` as the major only** — `"15"`, not `"15.1.1"`.
- **Send `locale` as the language subtag only** — `"en"`, not `"en-IN"`. (The
  Swift SDK already does this; Aptabase's own example payload shows `"en-US"`,
  so do not assume.)

This is the single strongest argument for §4.4. Sending less than the SDK sends
is trivial when you own the request and requires a fork when you don't.

**Never sent, and asserted by test:** device model, exact OS build, region or
timezone, gateway URL/host/IP/token, model file paths, transcript or audio
anything including character counts, and free text of any kind. Recording
duration is **bucketed** (`<10s`, `10_30s`, `30_60s`, `60s+`) so it answers "is
the 120 s cap hurting anyone" without being a content-length side channel.

### 4.4 The sender: hand-rolled, not the SDK

Aptabase publishes MIT-licensed Swift and Kotlin SDKs and an official guide to
writing your own. Take the second path. Four reasons, in order of weight:

1. **`systemProps` control.** §4.3. This is the decisive one.
2. **`README.md:92` and the Play description stay true.** "No analytics SDK" is
   a claim this project has made repeatedly and can keep making, for the cost of
   about 150 lines.
3. **F-Droid.** No new dependency in either flavor keeps the reproducible-build
   story exactly where `build.gradle.kts:110–115` left it.
4. **The ingest API is trivial and officially documented as a target.** A POST
   with a JSON array and one header. There is nothing here worth a dependency.

The shape, so nothing above it knows which backend is live:

```kotlin
interface TelemetrySink { suspend fun send(events: List<TelemetryRecord>): Boolean }
```

- `AptabaseSink` — OkHttp, already a dependency (`build.gradle.kts:189`);
  `URLSession` on iOS.
- `NoOpTelemetrySink` — the `fdroid` flavor, tests, debug builds, and all of
  Phase 1 (§12).

Queue and flush rules:

- Bounded queue, cap 200 events, drop **oldest** on overflow. Never grows
  unboundedly, never blocks a dictation.
- Chunk into batches of 25. Flush on app backgrounding and at most hourly, on
  unmetered network. `androidx.work` is already a dependency
  (`build.gradle.kts:187`); on iOS flush on `scenePhase` → `.background`.
- Fail closed: max 3 attempts with backoff, drop on any 4xx, drop the batch on
  repeated 5xx. Aptabase's own guidance is to log and swallow rather than raise.
- **A network failure must never surface in the UI.** The user did not ask for
  this feature to work.
- Because there is no persistent identity, an event that fails to send is simply
  gone. Do not build durable disk-backed retry for it; the data is not worth the
  complexity or the storage.

### 4.5 The keyboard sends nothing in v1

On iOS the keyboard extension is a separate process with its own
`PrivacyInfo.xcprivacy`, and its `NSPrivacyCollectedDataTypes` is currently an
empty array. **Keep it empty.** A Full Access keyboard that opens a network
socket is the scariest thing this product could ship, regardless of what is
actually in the packet.

This costs the `insertion_skipped` reason codes — which `DiagnosticLog.swift:47`
correctly identifies as the hardest failure in the product to reproduce, and
which is genuinely what you most want. Take the loss in v1; revisit once the
pipeline is trusted, with the keyboard *writing to the shared App Group queue
and never opening a socket*.

On Android the IME runs in the app process, so the distinction does not exist —
hold the same line anyway so the two platforms report comparable numbers.

---

## 5. What we measure, given no cross-day identity

§4.2 rules out per-user funnels. The fix is to **count one-shot events instead
of tracing users** — which turns out to answer most of the same questions.

**The trick:** guard the milestone events behind a local boolean so each fires
at most once per install, ever. Then `count(first_dictation_ever) ÷
count(app_first_open)` over the same window is your install→activation rate,
with no identity of any kind involved. Ratios of one-shot counters give you the
funnel; they just don't let you inspect an individual's path through it.

Those local booleans are ordinary preferences, not identifiers — they never
leave the phone and hold no value that could distinguish one install from
another.

| Question | How |
| --- | --- |
| Where does setup stall? | `setup_step_completed{step}`, one-shot per step: `keyboard`, `microphone`, `notifications`, `source` |
| How many installs ever reach a first transcript? | `count(first_dictation_ever) ÷ count(app_first_open)` |
| On-device or gateway? | `source_selected{source}` |
| Do model downloads finish? | `model_download_finished{model_id, outcome}` — `model_id` pinned to the shipped catalog, never a path |
| Which local models actually get *used*? | `dictation_succeeded{model_id}` and `dictation_failed{model_id}`. Downloads are a weak proxy for this and were the original answer here: a download is a one-time act of optimism, and someone can fetch three models, use one, and go back to the gateway. `gateway` where transcription did not run on the phone |
| Is a performance fix landing, and at what accuracy? | `dictation_succeeded{model_id, quality, duration_bucket}` — `quality` is `not_applicable` for gateway sessions, which decide their own decoding |
| Where does dictation fail? | `dictation_failed{stage, reason, source}`, `reason` drawn from the existing `DiagnosticReason` vocabulary |
| Does the 120 s cap bite? | `dictation_succeeded{duration_bucket}` |
| Which OS majors still matter? | `systemProps.osVersion`, major only |
| How many people turn this off? | `telemetry_disabled`, sent once as the last act before the switch takes effect |

That last one must be stated plainly in the UI copy. A "we log your opt-out"
that a user discovers by packet capture is far worse than not knowing the number.

Explicitly **not** measured: screen views, session length, button taps,
per-keystroke anything, retention.

**Accept the retention gap rather than working around it.** If you later decide
you must have retention, that is a decision to re-introduce a persistent
identifier, and it should be argued on its own merits in its own document — not
smuggled in as an implementation detail here.

---

## 6. Opt-in or opt-out

You asked for opt-out with a notice at onboarding. I would still ship opt-in,
but the argument has changed shape now that Aptabase stores nothing on the
device.

**What no longer applies:** the ePrivacy Art. 5(3) consent hook. There is no
identifier written to or read from the phone, so the strongest legal reason to
require opt-in is gone (§4.2). Opt-out is defensible here in a way it would not
have been under my first draft.

**What still applies, and is now the whole case:**

- **F-Droid.** Anything enabled by default that phones home earns the `Tracking`
  anti-feature on the listing. §9 removes this by compiling telemetry out of
  that flavor — but only if the flavor split actually happens.
- **The audience.** This app's users are self-hosters who installed it *because*
  of the sentence in §2. Opt-out telemetry in a privacy-first keyboard is the
  kind of thing that gets a paragraph in a Hacker News comment, and the
  paragraph is not wrong. The cost is not legal, it is that you spend trust you
  have been banking since 0.1.0.
- **Opt-in gets you enough**, because §5 is built on ratios of counters. You
  need direction, not significance, and the ratio is the same whether you
  observe 30% of installs or 100% — as long as you remember the denominator is
  opted-in users, not installs.

It is one constant either way:

```kotlin
// The only line in the codebase that decides this. Changing it changes the
// onboarding copy, the pre-checked state, and the Play data-safety answer.
const val TELEMETRY_DEFAULT_ENABLED = false
```

If you flip it to `true`, two things become mandatory: the onboarding screen
must be *blocking* rather than a card the user can scroll past, and
`docs/privacy.md` must state the default in its first paragraph.

The middle path, if opt-in is off the table: **opt-out in the Play and App Store
builds, compiled out entirely for F-Droid and source builds.** Defensible,
honest, and it costs one `buildConfigField`.

---

## 7. The two surfaces

### 7.1 Onboarding notice

A step at the **end** of setup, after the user has a working transcript — not
before, while they are still deciding whether this app is worth their time.

- Android: a new card in `SetupScreen.kt`, after the `Section("Speech")` block
  and before `PrimaryButton("Start dictating")` (`SetupScreen.kt:110`).
- iOS: a new step in `SetupView.swift`, after "Try typing"
  (`SetupView.swift:269`) and before "Start dictating" (`SetupView.swift:300`).

Requirements, all load-bearing:

- **The switch is on this screen.** Not behind "Learn more", not on a settings
  screen the user is told about. If the toggle is not visible where the notice
  is, the notice is not a choice.
- **Both buttons equally weighted.** No greyed-out "No thanks", nothing that
  makes declining look like a mistake.
- Name the concrete things sent and not sent. "Which setup step you reached,
  whether a dictation succeeded, the app version" lands better than "anonymous
  usage data" — and here you have an unusually good story to tell, so tell it.
- A **"See exactly what's sent"** link into the payload viewer (§7.2).
- Continuing without touching the switch takes `TELEMETRY_DEFAULT_ENABLED`.

Draft copy:

> **Help fix what's broken?**
>
> VocaPhone is in beta and most problems never get reported. If you turn this
> on, the app sends a short list of counters — which setup step you reached,
> whether a dictation succeeded or failed and at which stage, the app version —
> to a server VocaHQ runs.
>
> It never sends what you say, what you type, your transcripts, your audio, or
> your gateway's address. It stores nothing on your phone to identify you, and
> nothing sent today can be linked to anything sent tomorrow.
>
> `[ See exactly what's sent ]`
> `( Turn on )`  `( Not now )`
>
> You can change this any time in Settings › Privacy.

That third sentence is the one worth keeping through every copy review. It is
literally true under §4.2, it is unusual, and it is the thing a sceptical reader
will actually weigh.

### 7.2 Settings

- Android: a new `Section("Usage reporting")` in `SettingsScreen.kt`, next to
  `Section("Audio retention")` (`SettingsScreen.kt:294`).
- iOS: a new section in `PrivacySettingsView` (`SettingsView.swift:753`), under
  "What is kept".

Contents:

1. The switch, current state legible without tapping.
2. The full sent/never-sent list as footer text — the same words as onboarding,
   not a shorter paraphrase.
3. **"See what's sent"** — the pending queue rendered as the literal JSON that
   would go over the wire, `systemProps` included. This is the trust move, it
   costs almost nothing because the payload is already a serializable struct,
   and it is self-enforcing: if someone later adds a field, it shows up here.
4. A line stating that turning the switch **off** sends one final
   `telemetry_disabled` event and then discards the local queue.

No "Reset ID" button — there is no ID to reset, which is worth one sentence of
explanation in the footer rather than a control.

---

## 8. Platform work

### 8.1 Android

New package `com.vocahq.vocaphone.telemetry`:

| File | Contents |
| --- | --- |
| `TelemetryEvent.kt` | Closed enums — events, steps, stages, reasons, sources, duration buckets |
| `TelemetryRecord.kt` | One Aptabase event: timestamp, sessionId, eventName, systemProps, props. No `String` props anywhere |
| `TelemetrySession.kt` | In-memory session ID, epoch-seconds + 8 random digits, rotates on inactivity |
| `TelemetryQueue.kt` | Bounded in-memory queue, drop-oldest, chunks of 25 |
| `TelemetrySink.kt` | Interface + `AptabaseSink` (OkHttp) + `NoOpTelemetrySink` |
| `TelemetryFlushWorker.kt` | `androidx.work`, unmetered + backoff constraints |
| `Telemetry.kt` | The one object the rest of the app calls; no-ops when disabled |

`SettingsRepository.kt:357` gains `TELEMETRY_ENABLED` plus the one-shot
milestone booleans from §5 (`SEEN_FIRST_OPEN`, `SEEN_FIRST_DICTATION`, and one
per setup step), following the existing key pattern, with matching fields on
`VocaPhoneSettings` (`SettingsRepository.kt:108`).

Build config: a `TELEMETRY` boolean `buildConfigField` on both flavors,
following the `SHERPA_ONNX` precedent at `build.gradle.kts:50` and `:58`. The
Aptabase host and `A-SH-*` app key also go in `buildConfigField` — the key is a
public write credential, not a secret, and having it greppable in the APK is a
feature.

### 8.2 iOS

New files in `VocaPhoneShared/` (so the types exist for the keyboard target
later without a move) and `VocaPhoneApp/`:

| File | Target | Contents |
| --- | --- | --- |
| `TelemetryEvent.swift` | Shared | The mirrored closed enums |
| `TelemetryRecord.swift` | Shared | `Codable`, `.sortedKeys` like `SharedStore.swift:22` |
| `TelemetryQueue.swift` | Shared | Bounded queue |
| `TelemetrySink.swift` | App | Protocol + `URLSession` implementation + no-op |
| `Telemetry.swift` | App | The façade; the only thing call sites see |

Preference keys follow `KeyboardPreferences.swift:235` — `telemetryEnabledKey`
and the milestone booleans — in the `group.com.vocahq` suite.

`ios/VocaPhoneApp/PrivacyInfo.xcprivacy` gains an `NSPrivacyCollectedDataTypes`
entry (§10). `ios/VocaPhoneKeyboard/PrivacyInfo.xcprivacy` and
`ios/VocaPhoneLiveActivity/PrivacyInfo.xcprivacy` stay exactly as they are —
and because §4.4 links no SDK, no third-party privacy manifest merges into the
app's either.

---

## 9. F-Droid

`fdroid/com.vocahq.vocaphone.yml` currently lists no `AntiFeatures`. Two ways to
keep it that way; take the first.

**Compile it out.** The `fdroid` flavor gets `TELEMETRY = false` and binds
`NoOpTelemetrySink`; R8 strips the HTTP path. The settings toggle and onboarding
step are hidden behind `BuildConfig.TELEMETRY` rather than shown-and-disabled —
a switch that cannot do anything is worse than no switch. Because §4.4 adds no
dependency, the flavor's dependency graph is unchanged, which keeps the
reproducible-build work at `build.gradle.kts:110–115` and the CMake notes in the
F-Droid metadata header exactly as they are.

**Or declare it.** If telemetry ships in the F-Droid flavor at all, add
`AntiFeatures: [Tracking]` to the metadata proactively. Being flagged by a
reviewer reads very differently from having declared it.

Source and debug builds default to disabled regardless of
`TELEMETRY_DEFAULT_ENABLED`, so contributors and CI never emit events. Gate on
`BuildConfig.DEBUG`, and set `isDebug` in `systemProps` honestly if anything
ever does send from a debug build.

---

## 10. Store paperwork

**iOS — `PrivacyInfo.xcprivacy` (app target only).** Add to
`NSPrivacyCollectedDataTypes`:

- Type: `NSPrivacyCollectedDataTypeProductInteraction`
- `NSPrivacyCollectedDataTypeLinked`: `false`
- `NSPrivacyCollectedDataTypeTracking`: `false`
- Purpose: `NSPrivacyCollectedDataTypePurposeAnalytics`

`NSPrivacyTracking` stays `false` and `NSPrivacyTrackingDomains` stays empty:
"tracking" in Apple's sense means linking to other companies' data or using it
for ads, and this does neither. No ATT prompt. Do not let the similarity of
vocabulary talk you into adding one. Aptabase publishes an Apple App Privacy
guide — read it, but note it is written for SDK users and you are not one.

**App Store Connect nutrition label.** Product Interaction → collected → not
linked to you → not used for tracking. `docs/testflight.md:48–49` is the script
for filling this in, so it changes in the same commit.

**Play Data safety.** App activity → App interactions → collected, not shared,
and — critically — tick **"Users can choose whether this data is collected."**
That checkbox is only truthfully tickable because of the toggle in §7, and it is
where opt-in versus opt-out becomes a form answer rather than a vibe.

---

## 11. Tests

The tests are the privacy claim.

1. **Vocabulary parity.** The Kotlin and Swift event/prop enums produce
   identical wire-string sets. A drifted enum is a silently broken funnel.
2. **`systemProps` allowlist.** Serialize a record and assert the `systemProps`
   key set is **exactly** `{locale, osName, osVersion, isDebug, appVersion,
   sdkVersion}`. This is the test that keeps `deviceModel` out (§4.3), and it is
   the most important one in the list.
3. **`osVersion` is a major.** Assert the serialized value matches `^\d+$` on
   both platforms. Same for `locale` matching `^[a-z]{2}$`.
4. **No free text.** A compile-level or reflection assertion that no telemetry
   API accepts an unconstrained `String` — the direct analogue of the existing
   diagnostics test at `docs/privacy.md:111–113`.
5. **Disabled means silent.** With the toggle off, a fake sink records zero
   calls across a full simulated setup-plus-dictation run. Assert on the *sink*,
   not the queue: the interesting bug is a queue that fills while disabled and
   floods on first enable.
6. **One-shot milestones fire once.** Run setup twice; `app_first_open` and
   `first_dictation_ever` each appear exactly once. §5's arithmetic is wrong if
   this breaks, and it breaks silently.
7. **Bounded queue.** 500 events in, 200 retained, and they are the newest 200.
   Batches never exceed 25.
8. **No content leakage.** Push a transcript containing a canary string through
   a full dictation; assert the canary appears in no serialized payload.

---

## 12. Rollout

Ship the trust surface before the pipe. Each phase is releasable.

**Phase 1 — everything except transmission.** Event vocabulary, session, queue,
toggle, onboarding step, settings section, payload viewer. `NoOpTelemetrySink`
in every flavor. Nothing leaves the phone. You see real payloads in the viewer
on your own device and the schema stabilizes before anyone's data rides on it.

**Phase 2 — stand up Aptabase.** Compose deploy, pinned tags, TLS, proxy passing
`X-Forwarded-For`, access logs off for the ingest path, ClickHouse TTL. Then the
§3.3 audit: inspect the schema and a sample row, confirm no raw IP is persisted,
and record the date. Load-test with synthetic batches against a throwaway app
key before any real client points at it.

**Phase 3 — Android, one channel.** Bind `AptabaseSink` in the `full` flavor
only, ship to the Play internal track. Watch a week: does the funnel look sane,
what is the opt-in rate, is anything unexpected in ClickHouse.

**Phase 4 — iOS.** Same, via TestFlight.

**Phase 5 — docs and copy.** All eight strings in §2, one commit, release notes
saying plainly what changed and that it is off by default. Do not let this slip
behind the code; the window where code and copy disagree is the window where
someone finds it.

**Phase 6 (later, optional) — keyboard events.** `insertion_skipped` from the
iOS extension via the App Group queue, sender still app-side only, keyboard
privacy manifest updated. Only after Phases 1–5 have been stable for a release.

**Phase 7 — make the shipped claims true.** Added after Phases 1, 3, 4 and 5
landed and the gap between what the docs assert and what has been verified
became the largest remaining risk in the feature. Ordered by what it costs to be
wrong, not by effort:

1. **Correct `docs/privacy.md` where it overclaims.** Done. It asserted that the
   ingest path "is handled by rate limiting" in the present tense while §13
   listed that as outstanding, and it claimed a ClickHouse TTL without a number
   and a no-raw-IP guarantee nobody had checked. The three server-side claims now
   sit in a table with the command that would establish each and a status column
   that currently reads "not yet verified". The table is deliberately awkward to
   look at, which is the point: it converts an unverified claim from something
   invisible into something that nags.
2. **Put the deployment in the repository.** Done, as `telemetry/` — §14's open
   question 2 answered by "it being public matters more than where". The compose
   file is a template until it is reconciled against the live instance, which
   predates the directory; `telemetry/README.md` says so at the top and carries
   the one command that reconciles them. A compose file in the repo that does not
   match production would be worse than none, because it would read as
   documentation while being fiction.
3. **The §3.3 audit itself.** Server-side, needs host access, cannot be done from
   this tree. `telemetry/README.md` has the ClickHouse queries written as
   discovery — `SHOW TABLES`, `DESCRIBE`, a whole sample row, a sweep of every
   address-shaped column name — rather than as confirmation of an assumed schema,
   because an audit that only looks where it expects to find nothing is not one.
   Date each row of the `docs/privacy.md` table as it is done.
4. **Set the TTL and rate limit the ingest path.** Both are one proxy or
   ClickHouse statement, both are in the runbook, and both are currently claimed
   or implied in a public document.
5. **Console paperwork (§10).** The in-repo half is already done — the app
   target's `PrivacyInfo.xcprivacy` declares Product Interaction, not linked, not
   tracking, analytics purpose, and the keyboard and Live Activity manifests are
   correctly empty. What is left is App Store Connect's nutrition label and Play
   Data safety, including the "users can choose whether this data is collected"
   checkbox that only the §7 toggle makes truthfully tickable. This gates the next
   release rather than a later one.
6. **Consume `model_id` and `quality`.** The dictation events now carry which
   shipped model transcribed and at which accuracy setting (§5). Nothing reads
   them yet, and an event nobody queries is a cost with no benefit — it widened
   the payload and bought nothing until there is a saved query for failure rate
   and duration bucket grouped by `model_id`, on-device rows only.

### Kill switch

No remote config needed: turn the endpoint off server-side and the client fails
closed by design (§4.4). Confirm it once, deliberately, in Phase 3 — point a
build at a dead host and verify the app is completely unaffected.

---

## 13. Risks

| Risk | Mitigation |
| --- | --- |
| Community reads this as a betrayal of the README | Opt-in default, F-Droid compiled out, payload viewer, self-hosted AGPL backend, no SDK, copy updated in the same release. The §4.2 story is genuinely strong — lead with it. |
| Aptabase's SDK defaults creep back in later | §11.2 fails the build if `deviceModel` ever appears. |
| Proxy logs IPs beside a server that deliberately doesn't | §3.3 step 4, verified in Phase 2, not assumed. |
| ClickHouse eats the VPS | Pin tags, cap memory in compose, set the TTL before Phase 3 rather than after. |
| `ghcr.io/aptabase/aptabase:main` shifts under you | Pin a digest; upgrade deliberately. |
| Schema drifts between platforms | Parity test (§11.1) in CI. |
| Someone adds a free-text field later | §11.4 and the payload viewer both fail loudly. |
| Losing retention data hurts more than expected | Accept it for now (§5). Re-introducing an identifier is its own decision with its own document. |
| Opt-in rate too low to be useful | Accept it. §5 is ratios, and §3.0 already covers crashes and installs at 100%. |

## 14. Open questions

1. **Is the loss of retention analysis acceptable?** It is the real cost of this
   backend (§4.2), and it is worth sitting with for a day before Phase 1 rather
   than discovering it in month two. My read: for a beta whose central question
   is a setup funnel measured in minutes, yes.
2. Where does the Aptabase compose config live — `gateway/`, a new `telemetry/`
   directory here, or its own repo? It being public matters more than where.
3. Does `systemProps.appVersion` alone distinguish TestFlight from App Store, or
   do you want a `channel` prop? Useful, but it is one more correlating bit on a
   small population.
4. Should `docs/decisions.md` carry the reversal of `Plan-Beta-Release.md:233`
   explicitly? I think yes — the earlier position was stated strongly enough
   that silently changing it is its own small breach.

---

## As built — where the implementation diverged

Phases 1, 3, 4 and 5 are done on both platforms. Three things changed in
contact with the code:

1. **No WorkManager.** §4.4 called for `androidx.work` with unmetered
   constraints. That is incoherent with an in-memory queue: a deferred job runs
   after Android has reclaimed the process and almost always wakes to nothing.
   Flushing happens on process lifecycle instead — `ProcessLifecycleOwner`
   `onStop` on Android, `scenePhase` leaving `.active` on iOS. The cost is that
   a jetsam kill loses the queue, which is acceptable for counters and is
   written down in `TelemetryFlushScheduler`.

2. **The F-Droid claim is narrower than §9 promised, and measured.** "Compiled
   out entirely" was too strong. Scanning the `fdroidRelease` dex: no host, no
   ingest path, no `App-Key` header, no `AptabaseSink`, and none of the
   user-facing copy. What survives is the SDK-version constant and one section
   title, both inert — an unreachable queue wired to the no-op sink. Getting
   even that far needed a second `BuildConfig.TELEMETRY` guard in
   `SetupScreen.kt`, because the payload viewer was called outside the first
   one and R8 kept it. `docs/privacy.md` states the measured result rather than
   the intention.

3. **Telemetry sees a three-method preference protocol, not the settings
   store.** Holding `SettingsRepository` (or `KeyboardPreferences`) would put
   telemetry one field access from the gateway URL and the clipboard history.
   `TelemetryPreferences` makes the boundary reviewable at a glance and makes
   the whole package testable without a `Context`.

**The app key is wired in** — `A-SH-3275173609`, committed on both platforms
rather than injected, because it is an append-only ingest credential that ships
inside every binary and so cannot be kept secret from anyone who has the APK.
Forks override it with `-PaptabaseAppKey=` or `APTABASE_APP_KEY`; a blank or
malformed value disables transmission rather than falling back to ours. Tests on
both platforms fail the build if the key stops being a well-formed `A-SH-` key
or the host stops being HTTPS, because a typo there is otherwise silent — every
flush would be rejected with nothing visible to the user.

**Two things learned on a real device, both worth keeping.**

1. **`ProcessLifecycleOwner` was the wrong flush trigger and made the feature
   look completely broken.** It observes *activities*; the IME is a Service. A
   dictation from the keyboard inside another app happens with no activity on
   screen, so no background transition was ever reported and the queue was never
   flushed — every keyboard-only dictation, which is nearly all of them, was
   lost when the process died. Delivery is now owned by a 5-second debounced
   flush triggered by the event itself, on both platforms; the lifecycle
   observer only adds an immediate send when someone does leave the app.
   `the queue flushes itself without an activity` is the regression test.

2. **A 200 from Aptabase's ingest does not mean the events were stored.**
   Measured against the live instance: a bogus `App-Key` returns 200, *no*
   `App-Key` returns 200, and only malformed JSON returns 400. The endpoint
   answers 200 for anything well-formed and silently drops events whose key does
   not map to a registered app. So neither the HTTP status nor an emptied client
   queue is evidence of delivery — the client drops a batch on 4xx too. The only
   authority is the server: ClickHouse rows, or the dashboard.

   This is worth writing into the runbook. Verifying the pipeline **must** be
   done server-side; every client-side signal available is indistinguishable
   between "stored" and "silently discarded".

3. **The Aptabase dashboard splits Debug and Release, and defaults are easy to
   misread.** An orange `Debug Data` ribbon and a bug/rocket toggle in the top
   right decide which bucket you are looking at; the split comes purely from the
   `isDebug` flag in the event payload. Both clients send `isDebug: false` in
   release builds and transmit nothing at all in debug builds, so **every real
   event is in Release** — but a probe sent with `isDebug: true` sits in the
   other bucket and looks like proof the pipeline works. Verifying anything on
   that dashboard starts with checking which mode is selected.

**Closed since the first pass.** iOS reported no dictation outcomes at all —
`dictationSucceeded`, `dictationFailed` and `modelDownloadFinished` had no call
sites, so the most valuable signal in the feature was Android-only. Both
outcomes now report from the two points every route converges on
(`markTranscriptDelivered` and `fail`), with the recording clock tracked
separately from `SessionRecord.createdAt` because a slow model load would
otherwise inflate the duration bucket. Failure-code translation lives in
`TelemetryFailureMapping` rather than the coordinator, which is what makes it
testable — the coordinator drags the audio stack into any target that compiles
it.

**Still outstanding:** everything in Phase 7 from step 3 down — the §3.3
ClickHouse audit, the TTL, proxy rate limiting on the ingest path (§13), the
Play Data safety and App Store Connect nutrition-label declarations (§10,
console work), and a query that reads `model_id` — plus the Phase 6 keyboard
`insertion_skipped` events. Steps 1 and 2 of Phase 7 are done: `docs/privacy.md`
no longer claims the rate limiting exists, and the deployment now lives in
`telemetry/`.

Of §14's open questions, 1 and 4 are settled — `docs/decisions.md:40` carries the
reversal explicitly — and 2 is answered by `telemetry/`. Question 3, whether to
add a `channel` prop to tell TestFlight from the App Store, is still open, and
the case against it has not weakened: it is one more correlating bit on a small
population, and `appVersion` already separates most of what it would separate.

---

## Sources

- [aptabase/self-hosting](https://github.com/aptabase/self-hosting) — compose stack
- [How to build your own SDK](https://github.com/aptabase/aptabase/wiki/How-to-build-your-own-SDK) — ingest endpoint, batch limit, session ID
- [aptabase/aptabase](https://github.com/aptabase/aptabase) — AGPL-3.0 server
- [aptabase-swift](https://github.com/aptabase/aptabase-swift) / [aptabase-kotlin](https://github.com/aptabase/aptabase-kotlin) — MIT SDKs, `systemProps`, `A-SH-*` host handling
- [Aptabase privacy policy](https://aptabase.com/legal/privacy) — daily salt rotation
