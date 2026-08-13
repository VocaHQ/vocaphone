# Open PR action board

13 August 2026. Adversarial review of every open pull request on [VocaHQ/vocaphone](https://github.com/VocaHQ/vocaphone).

Two PRs are open. Both are from Kanishk Pachauri (`Mr-Sunglasses`). Neither has a review. **Merge neither of them today.**

| PR | Title | Verdict | Merge now? |
| --- | --- | --- | --- |
| [#67](https://github.com/VocaHQ/vocaphone/pull/67) | Improve on-device transcription accuracy | Request changes, then merge | No. Code is close. Needs your review plus a device smoke. |
| [#40](https://github.com/VocaHQ/vocaphone/pull/40) | Follow VocaMac and Handy model selections | Do not merge | No. Wrong repo target after the gateway submodule split, and vocamac #200 is still open. |

## What you should do this pass

1. Leave the copy-paste review on [#67](https://github.com/VocaHQ/vocaphone/pull/67). Ask Kanishk (or run it yourself) for the four hardware checks already listed in that PR. Approve once those land or you have run them.
2. Close or convert [#40](https://github.com/VocaHQ/vocaphone/pull/40) after posting the retarget comment. The Python changes have to be a [vocagateway](https://github.com/VocaHQ/vocagateway) PR. The iOS timeout change can stay here, rebased, once the gateway work exists.
3. Merge [vocamac#200](https://github.com/VocaHQ/vocamac/pull/200) first if you still want the headless VocaMac path. Kanishk already flagged that on #40. It is still open.

An HTML version of this board is in `reviews/open-pr-action-board.html`. Open it locally and print to PDF if you want a one-pager.

---

## #67 Improve on-device transcription accuracy

- URL: https://github.com/VocaHQ/vocaphone/pull/67
- Branch: `feat/on-device-transcription-quality`
- Size: +2529 / −192, 51 files, 4 commits
- Base: `main` (clean merge)
- CI: Android Quality pass, iOS Quality pass
- Reviews: none. GitHub reports `BLOCKED`, almost certainly the required-review rule.
- Author: not run on a phone yet.

### Verdict

**Request changes.** The design is careful and the unit tests cover the scary parts (sherpa `exit(-1)`, language-to-styler, sanitizer loops). I would merge this after a device smoke, not before. The author already named the four checks that matter.

What the PR actually does, checked against the code rather than the description:

- iOS WhisperKit now sets `detectLanguage` when language is Automatic. That bug is real: WhisperKit derives detection from `usePrefillPrompt`, so Automatic was silently English.
- Detected language is passed into the styler on both platforms, so Automatic + Hindi can get a danda instead of `.`.
- iOS finally has `TranscriptSanitizer`, including the repetition-loop collapse Android just grew.
- whisper.cpp decode status is no longer discarded.
- Audio levelling is applied to the in-memory copy only. The WAV on disk and the gateway upload are untouched. Confirmed in `LocalModelManager` on both platforms.
- Fast / Balanced / Accurate and a Whisper-only custom-word list. Sherpa decoding method is derived from model family, not from the setting alone. Tests walk every family × quality pair.
- Engine preload on accuracy/model change, with a loading label.

### Why it is not a merge today

The IME/sherpa live path never sees the new gain. `SpeechAudioConditioning` is called from the whole-file `transcribe` methods. `startIncrementalSession` / `startSherpaIncrementalSession` feed raw chunks straight into sherpa. For Parakeet and the other sherpa models, that is the path the keyboard actually uses. Whisper still gets levelling because it still decodes at finish. This is documented in comments. It is also a hole in the accuracy claim, and it should be in the PR text so QA does not test the wrong path.

No hardware run. Author’s own list, still outstanding:

- Hindi dictation, Automatic, Whisper model: expect `।`
- SenseVoice Chinese: expect `。`
- Change accuracy, dictate immediately (preload)
- Any CTC model on Balanced (this is the `exit(-1)` footgun)

CI does not exercise native decode, JNI, or a real microphone.

### Nits, not blockers

Android Balanced copy says "Beam search where it is cheap". For whisper.cpp, Balanced is still greedy (`whisperBeamSize = 0`). Beam is Accurate only. Sherpa transducers do get beam on Balanced. The string is true for one engine and misleading for the other.

iOS WhisperKit has no beam search at all. Accurate only raises `temperatureFallbackCount` (3 → 5). Fine, just different from Android whisper.cpp.

JNI `fullTranscribe` does not null-check `GetFloatArrayElements` / `GetStringUTFChars`. Same shape as the old function. A failed get would crash native rather than return a status. Pre-existing pattern; not new risk unique to this PR.

### Copy-paste review comments for #67

Post these on the PR. First one is a top-level review summary; the rest are line comments.

**Summary (Request changes)**

```
The code is in good shape and CI is green. I do not want this on main until someone has actually dictated on a phone.

Please run the four checks you already listed, or I will:

1. Hindi + Automatic + Whisper: Devanagari should end in a danda, not a Latin full stop.
2. SenseVoice Chinese: terminator should be `。`.
3. Change Fast/Balanced/Accurate and dictate immediately. The wait should name the model, not look like a hang.
4. A CTC sherpa model on Balanced. This is the path that used to be `exit(-1)`.

Two other things, neither of them a rewrite:

The new gain is not applied on the sherpa incremental/IME path, which is the path the keyboard uses for Parakeet and friends. Whole-file `transcribe` levels the buffer; `startIncrementalSession` does not. Whisper still benefits because it still decodes at finish. Please say that in the PR body so QA does not only test the in-app finish path.

Android Balanced copy says "Beam search where it is cheap". whisper.cpp Balanced is still greedy. Only Accurate sets `whisperBeamSize = 3`. Sherpa transducers do beam on Balanced. The sentence is true for one engine and wrong for the other. Tighten the string.
```

**`android/app/src/main/java/com/vocahq/vocaphone/local/LocalModelManager.kt:308`** (medium)

```
This is the keyboard’s sherpa path, and it never calls `SpeechAudioConditioning`. The whole-file `transcribe` below does.

I get why: gain has to be computed over the full recording, and per-chunk AGC is what you were trying to avoid. The result is that Parakeet/IME dictation does not get the levelling this PR is selling. Whisper does, because it still waits for the WAV.

Please call that out in the PR and in the accuracy footer. If there is a cheap way to level once at `session.finish()` before a last decode, that would close the gap; if not, say so rather than leaving QA to discover it.
```

**`android/app/src/main/java/com/vocahq/vocaphone/core/TranscriptionQuality.kt:31`** (nit)

```
"Beam search where it is cheap" is not what whisper.cpp Balanced does. `whisperBeamSize` is 0 for Fast and Balanced, 3 for Accurate.

Sherpa transducers do `modified_beam_search` on Balanced, so the sentence is half right. People reading this in Settings will think Balanced already bought them a beam on Whisper. It did not.
```

**`ios/VocaPhoneApp/Models/LocalModelManager.swift:354`** (medium)

```
Same gap as Android: `startSherpaIncrementalSession` consumes raw chunks. Levelling only happens in `transcribe(audioURL:)` at line 771.

WhisperKit is fine (finish-time decode). Sherpa IME is not. Please keep the two platforms’ PR notes in sync on this, and include a sherpa keyboard dictation in the hardware list, not only a whole-file in-app test.
```

---

## #40 Follow VocaMac and Handy model selections

- URL: https://github.com/VocaHQ/vocaphone/pull/40
- Branch: `feat/follow-desktop-model-selection`
- Size: +617 / −56, 10 files, 1 commit (8 August)
- CI: iOS and server Quality passed on that commit
- Merge state: **CONFLICTING**
- Author comment: merge [vocamac#200](https://github.com/VocaHQ/vocamac/pull/200) first. That PR is still open (`feat: add headless file transcription CLI`).

### Verdict

**Do not merge.** The idea is fine. The PR cannot land on current `main`.

On 8 August this repo still owned `server/` as a normal tree. `main` now has `server` as a gitlink to [vocagateway](https://github.com/VocaHQ/vocagateway) (`160000`). Git reports the Python files as "removed in local" against a new submodule commit. CONTRIBUTING already says gateway work goes to vocagateway, then a pin bump here.

There is no open vocagateway PR that carries this work. vocagateway `app/models/handy.py` is still the old snapshot (`self.model = model or self._read_selected_model()`).

Conflicts that are not the submodule:

- `README.md`, `docs/architecture.md`, `docs/troubleshooting.md` (docs, messy but ordinary)
- `ios/VocaPhoneApp/Networking/GatewayClient.swift`: finish timeout 90s → 540s. Real behaviour change, one-hunk conflict.

CLI contract vs vocamac#200 matches: `--transcribe-file`, `--list-models --json`, `--model`, `--language`, `--json`, and the error codes `model_not_found` / `model_not_downloaded` / `model_unsupported`. The binary scan for `--transcribe-file` is the right way to avoid launching an old GUI. Do not throw that away in the rewrite.

### Bugs to keep when this is re-filed

Handy still ignores session language. `transcribe(..., options)` never passes `options.language` into the CLI. The PR body says "Pass each VocaPhone session's language explicitly". That is true for VocaMac headless and false for Handy. vocamac#200 does accept `--language`. Handy may not; if it does not, the PR should say so.

`is_available` is documented as a cheap sync check used while resolving `auto`. The new path calls `_headless_model()`, which runs `VocaMac --list-models --json` with a 5s timeout. That is a process spawn on the auto-engine probe. Engine probes are cached for five seconds, so this will bite on a cold start and then sit in the cache. Move the spawn to `health()` / `transcribe()`, and keep `is_available` to "binary exists and looks headless".

iOS finish timeout becomes 9 minutes. Android `GatewayClient.finish` on current main is still 120s. A one-shot VocaMac load that needs the new timeout will still fail from Android. If you bump iOS, bump Android in the same change, or say why iOS is the only client that waits.

### Copy-paste review comments for #40

**Summary (Do not merge / close-and-retarget)**

```
Cannot merge this onto current main.

`server/` is now the vocagateway submodule. This branch still treats it as an in-tree Python tree, so Git sees a dirty conflict (submodule gitlink vs the old files) plus the docs/iOS timeout hunks. CONTRIBUTING already requires gateway changes to land in https://github.com/VocaHQ/vocagateway, then a pin bump here.

Please:

1. Wait for vocamac#200 (you already noted this). The CLI flags this PR calls (`--transcribe-file`, `--list-models --json`, `--model`, `--language`) match that PR. Good.
2. Open the Python work against vocagateway. There is no vocagateway PR for this today; handy.py on vocagateway main is still the old "read settings once at init" code.
3. Rebase the iOS 90s → 540s finish timeout onto current vocaphone main as a tiny follow-up, or fold it into the pin-bump PR. Android finish is still 120s on main, so a one-shot VocaMac load that needs nine minutes will still die from the Android client.

I went through the VocaMac adapter. The `--transcribe-file` byte scan instead of probing an old binary with an unknown flag is the right approach; keep that. A couple of code comments below still apply in the vocagateway rewrite.
```

**`server/app/models/handy.py:56`** (medium)

```
`options` is unused. The PR says each VocaPhone session language is passed explicitly. VocaMac headless does that (`--language`, including `auto`). Handy does not.

If the Handy CLI accepts `--language`, pass `options.language` here. If it does not, drop the claim from the PR body so a Hindi session is not assumed to be forwarded.
```

**`server/app/models/vocamac.py:89`** (medium)

```
The docstring still says this is a cheap synchronous check for `auto` engine resolution. The new branch calls `_headless_model()`, which `subprocess.run`s `--list-models --json` with a 5s timeout.

That is a cold-start stall on every auto probe until the 5s engine cache fills. Capability can stay in `_headless_supported()` (the file scan). Availability of the selected model belongs in `health()` / `transcribe()`.
```

**`ios/VocaPhoneApp/Networking/GatewayClient.swift:124`** (medium)

```
90s → 540s is a real product change, not just a conflict hunk.

Android `GatewayClient.finish` is still 120s. If a first VocaMac one-shot can sit in model load for several minutes, Android will time out while iOS waits. Bump both clients, or document that only iOS is expected to use this engine for the slow path.
```

---

## Cross-PR notes

The two PRs do not touch the same runtime path. #67 is on-device iOS/Android. #40 is gateway + one iOS timeout. No merge-order coupling except that #40 cannot rebase until it is split.

No other open PRs on this repository at review time.
