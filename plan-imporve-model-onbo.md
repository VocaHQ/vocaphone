# Improve Model Onboarding — Implementation Plan

Status: **baseline implemented; validation follow-ups remain.**

This plan improves how iOS and Android users choose an on-device
speech-to-text model. It does not add a model, change an engine, alter audio
handling, or modify the optional self-hosted gateway.

## Implemented baseline

The first implementation now delivers the decision-light path described here:

- iOS and Android onboarding show one language- and device-aware recommendation
  with a real download size and a plain-language reason.
- “Download and continue” downloads, verifies, prepares, selects, and enables
  the model through the existing platform model managers.
- “Help me choose” offers balanced, smaller-download, and quality priorities
  without making model names or engine details the first decision; quality
  stays on the balanced result until measured comparisons exist.
- Advanced users can still browse the compatible catalog; Settings keeps the
  full picker and existing filters.
- F-Droid/runtime eligibility and language coverage remain hard filters, with a
  visible no-match state when the selected language cannot be served locally.
- Pure recommendation tests cover balanced, lighter, quality-input handling,
  automatic-language normalization, runtime differences, and no-match fallback
  on both platforms.

The remaining items in this document that require product evidence or device
infrastructure—free-space/peak-storage checks, metered-network presentation,
validated speed and accuracy benchmarks, long-note evidence, and physical-device
QA—are intentionally not inferred from model size and remain follow-up work.

## 1. Outcome

A new user should not need to know what Whisper, Parakeet, Sherpa ONNX,
quantization, model parameters, or RAM budgets mean.

The normal path should be:

1. VocaPhone identifies one safe model that fits the phone and the user's
   language needs.
2. VocaPhone explains the recommendation in plain language.
3. The user taps one button to download and use it.
4. People with a specific preference can answer a few short questions or open
   the full catalog.

The experience should answer both questions users currently struggle with:

- **What should I use?** Show one primary recommendation, not a list to decode.
- **What can I use?** Show only compatible choices by default and give an exact
  reason for anything unavailable.

The model remains the user's choice. VocaPhone must not begin a large download,
switch a working model, or delete a model without an explicit action.

## 2. Scope

This work covers:

- the on-device model step in first-run onboarding on iOS and Android;
- the recommendation rules that choose and explain a model;
- a short optional “Help me choose” flow based on language and practical needs;
- a progressive-disclosure catalog for people who want manual control;
- compatibility, storage, download, verification, and recovery states;
- the Settings experience for revisiting the recommendation later;
- accessibility, localization, privacy-safe measurement, automated tests, and
  physical-device validation.

This plan deliberately separates **speech-to-text location** from **phone model
choice**:

- **On this phone:** VocaPhone recommends and downloads a compatible phone
  model.
- **Your gateway:** the user configures a gateway they run. Its model is chosen
  by that gateway, not by the phone onboarding.

There is no Voca-hosted transcription service. The UI must not introduce a
cloud option or imply that VocaPhone operates the user's gateway.

## 3. Current experience

The repository already contains useful foundations that should be retained.

### iOS

- `SetupView.swift` asks the user to choose “On this iPhone” or “Your gateway.”
- The on-device route opens `LocalModelPicker.swift` on a separate page.
- The picker filters models using the phone's physical memory and groups
  installed, recommended, and other compatible models.
- `LocalModelCatalog.swift` offers several roles such as “Best for English,”
  “Multilingual,” “Your language,” and “Smallest download.”
- Model size, language coverage, download progress, integrity verification,
  load state, and deletion already have real state behind them.

### Android

- `SetupScreen.kt` embeds a compact `LocalModelPicker.kt` in the Speech section.
- The compact picker leads with one recommendation, also presents alternate
  picks, and puts the rest of the catalog behind Browse.
- `DeviceProfile.kt` considers RAM, CPU information, Android media performance
  class, and the phone language.
- The full catalog supports search and engine, size, and language filters.
- The full build can use Whisper and Sherpa ONNX models. The F-Droid build is
  Whisper-only because the Sherpa ONNX native runtime is compiled out.

### What still causes indecision

The current improvements reduce the catalog, but they do not fully remove the
decision:

- iOS can still present three or four “recommended” models at once.
- Android's alternate cards still ask a first-time user to compare technical
  choices before they have completed one dictation.
- A phone locale is only a guess; it may not be the language the user dictates.
- Names, engine labels, language counts, and download sizes do not explain how a
  model will feel in daily use.
- “Recommended” is not yet a complete promise. It does not consistently cover
  free storage, download conditions, sustained performance, long dictation, or
  the confidence behind an accuracy claim.
- iOS and Android do not use the same recommendation vocabulary or the same
  level of device evidence.
- Incompatible models are generally filtered away, so an advanced user cannot
  always learn why a model is absent.
- Downloading and selecting can feel like separate decisions even though the
  onboarding goal is simply to get one working model ready.

The new work should build on the existing catalogs and download managers rather
than replacing them.

## 4. Product principles

### 4.1 Make the default path decision-light

First-run onboarding shows one recommendation and one primary action. It must
not lead with a comparison table, engine family, model size, or a quiz.

The first recommendation uses a safe balanced preference. A visible
“Personalize” action lets the user provide more information, but “VocaPhone can
choose for me” remains a complete path.

### 4.2 Ask only questions that can change the result

Do not ask about age, profession, personality type, technical skill, or other
identity proxies. Translate “personality” into practical needs:

- the languages the person speaks;
- whether they prefer a balanced choice, faster results, stronger measured
  accuracy, or a smaller download; and
- whether they mainly dictate short replies or long notes, but only when
  validated long-form performance makes that answer meaningful.

Every question must have “Let VocaPhone decide” selected by default.

### 4.3 Explain the result, not the machinery

Primary copy should say “Fast for short messages,” “Works with Hindi and
English,” or “Uses less storage.” The engine and exact model name remain
available under Details for transparency and support.

### 4.4 Compatibility is a hard rule

A recommendation must be runnable on that platform, in that build flavor, on
that phone, for every requested spoken language, with enough working storage.
Preference scoring may rank eligible models; it must never override an
eligibility failure.

### 4.5 Recommendation claims require evidence

File size is not proof of speed, and model size is not proof of accuracy. A
model can be labeled “faster,” “better for long notes,” or “more accurate” only
when that claim is backed by a versioned benchmark for the applicable platform,
device tier, language, and dictation length.

Until evidence exists, the UI may make factual claims such as language support,
download size, runtime availability, and minimum memory. It should say “Not yet
measured” rather than invent a ranking.

### 4.6 Preserve advanced choice without putting it in the way

The complete compatible catalog remains available from onboarding and Settings.
It is a secondary path for advanced users, not the first task every user must
complete.

## 5. Proposed onboarding journey

### 5.1 Choose where speech becomes text

Keep the existing source boundary:

- **On this phone** — download a model and transcribe without a gateway.
- **Your gateway** — send audio to the configured self-hosted gateway.

If the user chooses the gateway, skip phone model recommendations entirely.
Copy should say:

> Your gateway chooses its speech-to-text model. VocaPhone will not download a
> phone model for this route.

If the gateway is not configured, onboarding opens the existing gateway setup.
It must not list gateway-only models as choices that run on the phone.

### 5.2 Present one safe recommendation

The on-device screen should look conceptually like this:

```text
Set up speech to text on this phone

Recommended for you
Everyday — Balanced
Works with Hindi and English
Fits this phone · 640 MB download

Why this fits
Good balance of response time, accuracy, and storage on this phone.

[ Download and continue — 640 MB ]
[ Personalize ]
Browse all compatible models
```

The actual model name appears in a Details disclosure, for example:

```text
Model: Parakeet TDT 0.6B v3
Engine: Sherpa ONNX
```

Rules:

- “Recommended for you” means exactly one model.
- The card states the assumed dictation language and offers Change next to it.
- The primary button includes the real download size.
- Tapping it downloads, verifies, loads, selects, and enables the model as one
  continuous onboarding task.
- The app never starts the download before that tap.
- The choice can always be changed later in Settings.

### 5.3 Optional “Personalize” flow

Personalization uses at most three short screens. Most users should see two.

#### Question 1 — “What languages will you dictate?”

- Preselect the current transcription language when one is already configured.
- Otherwise suggest the device language, label it as a suggestion, and let the
  user change it.
- Allow one or several languages.
- Include “I am not sure” and Automatic where the selected engine can genuinely
  detect language.
- Search uses native language names and accessible English names.
- Never claim a model supports a language merely because the language picker
  contains it.

This is the only answer that should commonly remove a model from eligibility.

#### Question 2 — “What matters most?”

Options:

- **Let VocaPhone decide** — balanced and selected by default.
- **Faster results** — prefer validated low startup and transcription time.
- **Stronger accuracy** — prefer the best measured result for the selected
  languages while staying reasonable on this phone.
- **Smaller download** — prefer the lowest peak storage requirement that meets
  the language need.

Do not say “best accuracy” when only model size is known. If there is no
language-specific comparison, explain that and use the balanced result.

#### Conditional question — “How do you usually dictate?”

Show this only when the answer changes the recommendation:

- **Short replies** — prioritize startup time and short-clip latency.
- **Everyday mix** — balanced and selected by default.
- **Long notes** — require validated sustained performance and long-audio
  behavior.

If long-form evidence does not exist for the eligible choices, omit the question
instead of pretending to distinguish them.

### 5.4 Show a result, not another catalog

After personalization, show one recommended result with three short reasons:

```text
Your best match

Everyday multilingual
• Supports Hindi and English
• Fits this phone's memory
• Balanced for everyday dictation

[ Download and continue — 640 MB ]
Change answers       Compare alternatives
```

“Compare alternatives” may show at most two choices, each with a “Choose this
if…” sentence. It must not turn into a grid of equally recommended cards.

Example:

| Choice | User-facing explanation |
| --- | --- |
| Primary | Best overall match for the answers and this phone |
| Alternative 1 | Choose this if a smaller download matters more |
| Alternative 2 | Choose this if measured accuracy matters more and a slower result is acceptable |

If an honest distinction cannot be made, do not show an alternative.

### 5.5 Download and preparation

Treat this as one task with clear sub-states:

```text
Checking space → Downloading → Verifying → Preparing → Ready
```

Requirements:

- Calculate peak required storage, including temporary download or unpacking
  space and a safety margin, not only final model size.
- Warn before a large download on a metered connection using platform-appropriate
  network information. Offer Wait for Wi-Fi and Download now where supported.
- Preserve progress when the user navigates away and restore the same state on
  return.
- Keep Cancel and Retry available.
- Distinguish a network failure, insufficient storage, integrity failure, and
  model load failure; each needs a different recovery action.
- On successful preparation, make the model active automatically. Do not ask the
  user to tap a second “Use this model” button during onboarding.
- A completed download is not setup completion until the model has passed
  integrity verification and loaded successfully.

### 5.6 First-dictation fit check

The existing guided first dictation remains the final proof. After it succeeds,
show only a small recovery link:

> Too slow or not the language you expected? Review model recommendation.

VocaPhone may compare the locally measured transcription time with a conservative
threshold and suggest a lighter model after a clearly poor result. It must not
switch models automatically. Audio and transcript content must never be retained
for this check.

## 6. Recommendation contract

The recommendation engine should be deterministic, testable, and separate from
the SwiftUI and Compose views.

### 6.1 Inputs

```text
platform and build flavor
available native runtimes
device RAM budget and performance tier
free storage and peak-download allowance
selected spoken languages
automatic-language requirement
priority: balanced / speed / accuracy / storage
dictation pattern: short / mixed / long, when applicable
installed and currently selected models
network state for download presentation only
```

Network state must not change which model is compatible. It may change whether
the app recommends waiting for Wi-Fi before downloading it.

### 6.2 Technical compatibility and recommendation eligibility

Apply these technical compatibility filters before ranking:

1. The engine runtime ships on this platform and build flavor.
2. The model format and family are supported by that runtime.
3. The phone's conservative memory budget meets the model requirement.
4. The model supports every explicitly selected spoken language.
5. Any requested Automatic behavior is genuinely supported or is clearly
   described as punctuation-only rather than decoder control.
6. Peak working storage fits with a safety margin.
7. Required catalog files have complete immutable pins and integrity metadata.

Then apply one additional recommendation-eligibility gate: the model has passed
the minimum validation needed to enter the guided pool. A model that passes the
seven technical filters may remain manually available in the advanced catalog,
but it cannot become an automatic first-run recommendation until it passes the
guided validation gate.

The Android F-Droid flavor therefore cannot recommend a Sherpa ONNX model. A
gateway-only model can never pass the phone-runtime filter on either platform.

### 6.3 Guided pool

Do not rank every model in the full catalog merely because it can load. Create a
small, curated guided pool for each platform/build combination. A model enters
the pool only after the team has verified:

- download and integrity behavior;
- model loading and one complete transcription;
- all advertised language claims used by onboarding;
- memory behavior at its stated floor;
- short-dictation performance; and
- long-dictation behavior before receiving a long-notes label.

The advanced catalog may contain additional compatible models, but unvalidated
models are never the default first-run choice.

### 6.4 Ranking

Rank only eligible guided-pool models.

1. Prefer a model that covers all selected languages over separate specialist
   models.
2. For Balanced, choose the best validated tradeoff within the device tier.
3. For Faster results, compare measured startup plus transcription time for the
   applicable clip length; do not infer speed from bytes or parameter count.
4. For Stronger accuracy, compare a versioned language-appropriate evaluation;
   require a maximum acceptable latency and memory margin.
5. For Smaller download, compare peak working storage, then final installed
   size.
6. Prefer an already downloaded eligible model when its result is close enough
   to avoid an unnecessary large transfer.
7. Prefer the current working model on an existing install unless the user asks
   for a new recommendation or it becomes incompatible.

Tie-breakers must be stable and documented. A catalog reorder must not silently
change the default.

### 6.5 Confidence and fallback

Every recommendation result carries a confidence level:

- **High:** language, device, and requested-priority evidence all exist.
- **Good default:** compatibility is certain, but performance evidence is
  incomplete; use only factual balanced copy.
- **No guided match:** no validated guided-pool model meets every hard need.

For “No guided match”:

- do not quietly drop a selected language;
- show advanced choices only if they pass all seven technical compatibility
  filters, label them as not yet validated for guidance, and require manual
  selection;
- offer the self-hosted gateway route as an alternative, not as a promise that
  it supports the missing language; and
- give a clear way to change the answers.

### 6.6 Explanation output

The engine returns structured reason codes, not finished English sentences:

```text
supports_selected_languages
fits_device_memory
already_downloaded
balanced_for_device_tier
measured_fast_for_short_dictation
measured_for_long_dictation
smallest_peak_storage
accuracy_evidence_for_language
```

Swift and Kotlin map the same codes to platform-localized copy. This prevents
the UI from disagreeing with the ranking and prevents high-cardinality free text
from entering telemetry.

## 7. Guidance data and evidence

The existing descriptors already provide model identity, engine, size, minimum
RAM, and language coverage. Add guidance metadata without mixing it into file
integrity pins.

A conceptual record is:

```text
ModelGuidanceRecord
  model ID
  platform and build availability
  guided-pool status
  supported user priorities
  validated device tiers
  short and long dictation evidence
  language-specific accuracy evidence, where available
  peak working storage multiplier or measured requirement
  evidence revision and review date
  known limitations
```

Rules:

- Missing evidence is represented explicitly; it never receives a favorable
  default value.
- The model ID must resolve to a real catalog entry.
- Guidance records are versioned alongside app code so a model update cannot
  inherit stale claims silently.
- iOS and Android use the same intent names and reason codes, but may recommend
  different model IDs because their runtimes and model formats differ.
- Android full and F-Droid have separate expected result fixtures.
- Benchmark artifacts must contain no recordings or transcripts. Keep only
  approved public fixture identifiers, aggregate timings, app/model revisions,
  and coarse device-tier information.

## 8. Model catalog after onboarding

The full catalog remains useful in Settings, but it should no longer resemble
the onboarding decision.

### Default catalog view

- Show the active model first.
- Show Compatible with this phone by default.
- Keep search and language, size, and engine filters.
- Add user-facing filters for Faster, Stronger measured accuracy, Smaller
  download, and Long notes only where evidence supports them.
- Label the one current recommendation as “Your match,” not every role as
  “recommended.”

### Comparison view

Compare no more than three models and use the same fields in the same order:

| Field | Requirement |
| --- | --- |
| Best for | Plain-language validated use case |
| Languages | Exact supported set or truthful Automatic behavior |
| Download | Actual transfer size |
| Space needed | Peak working storage estimate |
| On this phone | Compatible, caution, or unavailable |
| Speed | Measured band or Not yet measured |
| Accuracy | Language-specific evidence or Not yet measured |
| Long notes | Verified, not recommended, or Not yet measured |
| Advanced details | Model name, engine, family, and technical limitations |

### Unavailable models

Advanced users may enable Show unavailable models. Disabled rows give one or
more exact reasons:

- Needs more memory than this phone can safely offer.
- Does not support the selected language.
- Needs more free storage.
- Requires a runtime not included in this build.
- Available on your self-hosted gateway, not on this phone.
- Not yet validated for guided setup.

“Not yet validated” does not mean incompatible; it means the model is excluded
from automatic guidance.

## 9. Platform implementation plan

### 9.1 Shared product contract

Define the same concepts on both platforms:

- `ModelGuidanceIntent` for language, priority, and dictation pattern;
- `ModelGuidanceContext` for device/build/storage facts;
- `ModelGuidanceRecord` for curated evidence;
- `ModelRecommendationResult` for the primary choice, optional alternatives,
  confidence, and reason codes; and
- a shared set of scenario fixtures to keep platform behavior aligned.

Do not force iOS and Android to share a model ID or a runtime. Cross-platform
parity means the same inputs, eligibility rules, explanation vocabulary, and
failure behavior—not identical catalogs.

### 9.2 iOS

Expected areas:

- `ios/VocaPhoneShared/LocalModelCatalog.swift` — preserve catalog and language
  truth; connect descriptors to guidance records.
- Add a pure recommendation type under `ios/VocaPhoneShared/` so it can be unit
  tested without SwiftUI or downloads.
- `ios/VocaPhoneApp/App/LocalModelPicker.swift` — split guided result from
  advanced catalog and add comparison/unavailable explanations.
- `ios/VocaPhoneApp/App/SetupView.swift` — embed the one-result onboarding path,
  Personalize flow, resume behavior, and combined download-and-use action.
- `ios/VocaPhoneApp/App/SetupStatus.swift` — keep setup incomplete until the
  chosen source is genuinely ready.
- `ios/VocaPhoneApp/Models/LocalModelManager.swift` — expose peak storage checks
  and precise recovery states without changing audio processing.
- `ios/VocaPhoneTests/` — add recommendation, state, copy, migration, and parity
  fixtures.
- Add previews for every guidance, download, and error state. If new Swift files
  are declared in `ios/project.yml`, run `just ios gen` and commit the regenerated
  Xcode project during implementation.

iOS currently uses memory and locale but has less device-performance context
than Android. Phase 0 must decide conservative iPhone tiers from verified device
results; it must not copy Android CPU assumptions.

### 9.3 Android

Expected areas:

- `android/app/src/main/java/com/vocahq/vocaphone/local/DeviceProfile.kt` — expose
  a conservative guidance context without turning hardware identifiers into
  analytics.
- `LocalModelCatalog.kt` — preserve catalog/build-flavor truth and connect it to
  curated guidance records.
- Add a pure recommendation component under `local/`.
- `ui/LocalModelPicker.kt` and `ui/ModelCatalogQuery.kt` — make onboarding a
  one-result path and keep the catalog as progressive disclosure.
- `ui/SetupScreen.kt` — add Personalize, combined download-and-use, recovery,
  and resume states without making the setup page taller than the task.
- Model manager/view-model state — expose free-space and error reason data.
- Unit tests under `android/app/src/test/` for full and F-Droid expectations,
  device tiers, languages, ranking, and UI presentation helpers.

The recommender must take runtime availability as an input so F-Droid cannot
produce a Sherpa result even if that model exists in the source catalog.

### 9.4 Gateway

No changes belong in the `gateway/` submodule for this phone-onboarding work.
The phone may link to the existing gateway setup/dashboard, but must not attempt
to download, rank, or configure gateway models through the phone catalog.

## 10. State, migration, and recovery

### Existing users

- Do not replay onboarding for someone with a working selected source.
- Keep the current model selected after an app update.
- Offer “Review your model match” in Settings when the new guidance becomes
  available.
- Do not nag after the user manually chooses a different compatible model.
- If the current model is heavier than the new recommendation, show one
  dismissible advisory; never switch or delete it automatically.

### Saved answers

- Save language and guidance preference locally so reopening the flow restores
  the result.
- Treat the preference as product configuration, not a personality profile.
- A device-language change may offer a review but must not silently change the
  spoken language or active model.
- Removing a selected language recomputes the result only after user
  confirmation.

### Interrupted download

- Restore real manager state rather than resetting the page to zero.
- If resumable download support exists, say Resume; otherwise say Download
  again.
- Keep the chosen intent after Cancel, network loss, process termination, or
  integrity failure.

### Low storage

Show the calculation in user terms:

> This model needs about 1.2 GB free while it is prepared. This phone currently
> has 700 MB available.

Actions are Free up space, Choose a smaller model, and Cancel. Do not delete a
different model without confirmation.

### Recommendation changes

A catalog or guidance update may improve the result, but the app should apply a
new recommendation automatically only before a model has been selected. After
setup, it is advice, not a migration.

## 11. Privacy and measurement

The recommendation is computed on the phone. Do not send a device model,
hardware identifier, free-space value, answer sequence, gateway address, typed
text, transcript, audio, or model path.

The MVP does not require new telemetry. Start with moderated usability sessions
and local QA.

If product measurement is later approved, it must remain opt-in and use only
closed-vocabulary events, for example:

- guidance path: default / personalized / catalog;
- recommendation accepted: yes / no;
- failure class: storage / network / integrity / load / unsupported;
- model-ready and setup-finished milestones; and
- coarse recommendation reason codes already defined in the product contract.

Do not send free-text “personality,” detailed hardware, exact storage, or exact
network information. Update `docs/privacy.md`, the telemetry allowlist, and its
regression tests before adding an event.

## 12. Content and visual behavior

Use native iOS and Material controls, system typography, solid fills, and the
existing Voca accent. Do not add gradients or decorative model artwork that
implies quality.

Copy rules:

| Avoid | Use |
| --- | --- |
| Pick an inference engine | Choose what matters to you |
| 0.6B TDT encoder | Fast for everyday English, when verified |
| Quantized Q5 | Smaller download |
| Supports 100 languages | Works with the languages you selected |
| Best model | Best match for your needs and this phone |
| Runs locally | Runs on this phone |
| Cloud model | Model on your self-hosted gateway |

Never hide uncertainty. “Not yet measured” is better than a confident but false
speed or accuracy label.

## 13. Accessibility and localization

- All guidance screens must work at the largest accessibility text sizes
  without hiding the primary action.
- Do not communicate recommended, unavailable, downloading, or failed states by
  color alone.
- VoiceOver and TalkBack should read: model outcome, why it fits, size, state,
  and action in that order.
- Comparison content must be linear and understandable without a visual table.
- Buttons must meet platform touch-target guidance.
- Respect Reduce Motion; recommendation changes need no celebratory animation.
- Language names use native names plus localized accessible labels.
- Reason codes must be fully localizable and must not assemble sentences from
  model names in grammatically fragile ways.

## 14. Research and validation before implementation

### Complaint audit

Classify real complaints without collecting transcript content:

- too many choices;
- technical names are unclear;
- unsure which languages work;
- unsure whether a model fits the phone;
- unclear size/speed/accuracy tradeoff;
- download or load failure mistaken for a bad choice; and
- selected model feels too slow after setup.

Use this audit to establish a baseline. Do not assume every model complaint is a
recommendation problem.

### Benchmark matrix

Build a small, repeatable matrix for guided-pool candidates:

- low, middle, and high supported phone tiers per platform;
- short, everyday, and long public or already-allowlisted speech fixtures;
- the main supported language groups the UI will make claims about;
- cold start, warm transcription, peak memory, sustained behavior, and failures;
  and
- app commit, model revision, OS major version, and thermal state.

Accuracy evaluation must use an approved public dataset and a documented metric.
Device testing may validate runtime and latency, but one personal recording must
not become the accuracy benchmark or enter the repository.

### Formative usability

Test the current and proposed flows with a small mix of:

- people who want the app to decide;
- multilingual users;
- users on lower-storage or older supported phones; and
- technical users who expect manual control.

Give participants the task “Set up on-device dictation for the languages you
normally speak.” Do not explain the models. Record whether they understand the
recommendation, find the expected action, and know they can change it later.

## 15. Automated test plan

### Recommendation engine

Table-driven tests must cover:

- every supported memory/device tier;
- full Android and F-Droid runtime availability;
- iOS and Android model-format differences;
- English, one non-English language, multiple languages, Automatic, unknown
  locale, and no guided match;
- balanced, speed, accuracy, and storage priorities;
- short and long dictation when evidence exists;
- enough storage, exact boundary, and insufficient peak storage;
- installed-near-match preference;
- stable tie-breaking;
- stale guidance records and missing model IDs; and
- no unsupported or unvalidated model ever returned.

Keep scenario fixtures readable. Each failure should say which eligibility rule
or ranking reason changed.

### Presentation and state

Test:

- exactly one primary recommendation;
- factual reason ordering;
- Personalize defaults and answer restoration;
- at most two explained alternatives;
- gateway route skips the phone chooser;
- download, metered network, verifying, preparing, ready, canceled, network
  failure, low storage, integrity failure, and load failure;
- download success automatically selects the model in onboarding;
- existing manual choice is not overwritten;
- unavailable reason copy; and
- accessibility labels do not expose only a technical model ID.

### Catalog integrity

Add gates that ensure:

- every guided record resolves to exactly one catalog model;
- every recommended model has complete pins and language metadata;
- reason claims are permitted by available evidence;
- no Sherpa model enters F-Droid expected results;
- iOS and Android share the same intent and reason-code vocabulary; and
- recommendation code never reads gateway credentials or audio/transcript data.

## 16. Physical-device QA

Automated tests cannot prove download behavior, memory pressure, or perceived
latency. Validate on real phones.

Minimum matrix:

- one low-memory supported Android device;
- one current mid/high Android device with the full build;
- one F-Droid build on Android;
- one lower-memory supported iPhone;
- one current iPhone; and
- at least one multilingual scenario on each platform.

For each device:

1. Start from fresh app state.
2. Accept the default recommendation and complete a first dictation.
3. Repeat with a changed language and a changed priority.
4. Test on Wi-Fi and a metered connection where the platform exposes it.
5. Test low storage, canceled download, app termination during download,
   integrity failure through a controlled test hook, and retry.
6. Confirm the model actually selected is the model shown in the result.
7. Confirm the recommendation does not appear for the gateway route.
8. Confirm large text, VoiceOver/TalkBack, dark mode, rotation where supported,
   and narrow-screen layout.
9. Record app version, OS, device class, selected languages, expected model,
   actual model, transfer size, preparation result, and observed latency. Do not
   record audio or transcripts.

Run `just ios ci`, `just android ci`, shared catalog checks, and
`git diff --check` for the eventual implementation. Keyboard/microphone changes
are not part of this plan; if implementation expands into those areas, add the
separate physical keyboard and microphone acceptance required by the repository.

## 17. Delivery phases

### Phase 0 — Evidence and product contract

- Audit complaints and establish baseline completion/indecision categories.
- Define the guided pool per platform and Android flavor.
- Build the benchmark matrix and approve which claims are safe.
- Freeze intent, eligibility, reason-code, confidence, and fallback contracts.
- Review all user-facing copy against platform, language, and gateway truth.

Exit gate: every guided model and every comparative claim has documented
evidence, or the UI is explicitly limited to factual compatibility claims.

### Phase 1 — Pure recommendation engines

- Implement hard filters, ranking, confidence, and structured explanations on
  iOS and Android without UI changes.
- Add shared scenario fixtures and platform unit tests.
- Add storage-context inputs and build-flavor/runtime gates.

Exit gate: no fixture can produce a model that fails a hard eligibility rule,
and results are stable across repeated runs.

### Phase 2 — Decision-light onboarding

- Replace multiple first-run recommendations with one result.
- Add language assumption/change, Personalize, and combined download-and-use.
- Preserve Browse as secondary progressive disclosure.
- Add all download/preparation/recovery states and resume behavior.

Exit gate: a user can complete on-device setup by making no model comparison and
tapping one model action.

### Phase 3 — Advanced catalog and Settings

- Add “Your match,” evidence-backed comparison, Compatible by default, and Show
  unavailable with exact reasons.
- Add Settings re-entry and respectful existing-user behavior.

Exit gate: advanced choice is available without weakening the first-run path or
silently overriding an existing choice.

### Phase 4 — First-use fit and measurement

- Add the post-dictation recovery link.
- Add a local slow-result advisory only after thresholds are device-validated.
- Add opt-in closed-vocabulary metrics only if separately approved and documented.

Exit gate: no model changes automatically, and no content or detailed device
information leaves the phone.

### Phase 5 — QA and rollout

- Run accessibility, localization, preview/screenshot, automated, and physical
  device matrices.
- Compare usability results with the Phase 0 baseline.
- Roll out conservatively and retain the existing manual picker as a fallback.

Exit gate: recommendation correctness, download reliability, and onboarding
completion meet the success criteria below.

## 18. Success criteria

### Correctness

- 100% of generated recommendations pass runtime, memory, language, storage,
  integrity-metadata, and guided-validation gates.
- No F-Droid scenario recommends Sherpa ONNX.
- No gateway-only model is presented as a phone model.
- Every comparative claim maps to current evidence.

### Comprehension

- In formative testing, at least 9 of 10 participants can state why the model
  was recommended without interpreting an engine or parameter name.
- At least 9 of 10 can find the default download action without opening the
  catalog.
- At least 9 of 10 know they can change the model later.
- Participants can distinguish “runs on this phone” from “runs on your gateway.”

### Effort

- Default on-device setup requires one model decision action: Download and
  continue.
- Personalization takes no more than three short answers.
- The result shows one primary choice and no more than two explained alternatives.

### Reliability

- Download, verification, preparation, and selection survive navigation and app
  relaunch according to platform capabilities.
- Every failure state identifies the failure class and offers a relevant next
  action.
- No completed setup state is shown before the selected model loads successfully.

### Accessibility and privacy

- All target screens pass VoiceOver/TalkBack, large text, contrast, touch-target,
  and reduced-motion checks.
- No transcript, audio, typed text, exact storage, detailed hardware identity,
  gateway address, token, or free-form profile data is collected.

## 19. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| A simple default is wrong for multilingual users | Make the assumed language visible and one tap to change; language is a hard filter |
| “Accuracy” becomes marketing copy | Require language-specific versioned evidence or show Not yet measured |
| More questions recreate the original indecision | Keep VocaPhone decides selected and show conditional questions only when the answer changes the result |
| iOS and Android drift | Share intent/reason vocabulary and scenario fixtures while allowing platform-specific model IDs |
| Android full guidance leaks into F-Droid | Make runtime/build flavor a required input and test F-Droid expected results |
| A large model technically fits but performs badly | Use conservative memory budgets, curated guided pools, device-tier benchmarks, and sustained tests |
| Free storage changes during download | Recheck before transfer and before final promotion; preserve recoverable state |
| Existing users feel overridden | Keep current models, make review optional, and never auto-switch after setup |
| The full catalog becomes hidden | Keep Browse in onboarding and a complete advanced catalog in Settings |
| Gateway is mistaken for a Voca cloud | Use “your gateway” and “gateway you run”; never introduce a hosted route |

## 20. Explicitly out of scope

- Adding new models, model families, native runtimes, or download sources.
- Changing model quality or decoding behavior.
- Changing microphone capture, keyboard insertion, audio retention, or gateway
  upload behavior.
- Editing the `gateway/` submodule or selecting a gateway model from the phone.
- Creating a Voca-hosted transcription service.
- Predicting a user's profession, personality, or language from private content.
- Automatically downloading, switching, or deleting a model.
- Store listing, release tagging, or version bumps.

## 21. Documentation updates when implementation ships

- Update `README.md` so setup describes one recommended phone model plus an
  optional advanced catalog.
- Update `docs/architecture.md` with the recommendation boundary, guided-pool
  evidence, and the separation between phone and gateway models.
- Update `docs/privacy.md` only if new persisted preference data or approved
  opt-in telemetry is introduced.
- Update platform setup and troubleshooting copy for storage, download,
  integrity, load, and no-compatible-model failures.
- Keep model names, language claims, Android full/F-Droid differences, and
  gateway wording aligned with the actual shipping catalogs.

## 22. Definition of done

This project is complete only when:

- both platforms offer a one-action default recommendation path;
- Personalize changes recommendations through deterministic tested rules;
- every recommendation is compatible and evidence-explained;
- Android full, Android F-Droid, iOS, and gateway-route behavior are distinct
  and correct;
- advanced users can compare compatible models and understand unavailable ones;
- download, verification, preparation, selection, resume, and failure recovery
  work on physical devices;
- accessibility and localization checks pass;
- all applicable local gates pass; and
- no onboarding implementation invents a hosted service, private-content signal,
  or unsupported model capability.
