# Open PR action board

13 August 2026. Adversarial review of every open pull request on [VocaHQ/vocaphone](https://github.com/VocaHQ/vocaphone).

Two PRs are open. Both are from Kanishk Pachauri (`Mr-Sunglasses`). Neither has a review. **Merge neither of them today.**

| PR | Title | Verdict | Merge now? |
| --- | --- | --- | --- |
| [#67](https://github.com/VocaHQ/vocaphone/pull/67) | Improve on-device transcription accuracy | Request changes | No. iOS treats sanitizer-empty output as success and deletes the WAV. Hardware still untested. |
| [#40](https://github.com/VocaHQ/vocaphone/pull/40) | Follow VocaMac and Handy model selections | Do not merge | No. Wrong repo target after the gateway submodule split. vocamac #200 is still `CHANGES_REQUESTED`. |

## What you should do this pass

1. Leave the copy-paste review on [#67](https://github.com/VocaHQ/vocaphone/pull/67). The iOS sanitizer port is missing the empty-transcript failure Android already has. Do not approve until that is fixed and someone has run the four hardware checks.
2. Close or convert [#40](https://github.com/VocaHQ/vocaphone/pull/40) after posting the retarget comment. The Python changes have to be a [vocagateway](https://github.com/VocaHQ/vocagateway) PR. The iOS timeout change can stay here, rebased, once the gateway work exists.
3. vocamac#200 is still `CHANGES_REQUESTED` (your review, 11 Aug). Kanishk replied on `4319eaf` that the bot comments were addressed. Re-review that before any gateway work that assumes the CLI exists.

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

**Request changes.** The sherpa `exit(-1)` gate, Balanced default, and WAV/gateway privacy claim all check out. The iOS sanitizer port does not. `[BLANK_AUDIO]` becomes `""`, the session is marked ready to insert, and the recording is deleted. Android already rejects that case. Until iOS matches, and until Accurate has been run on a phone, this should not merge.

What the PR actually does, checked against the code rather than the description:

- iOS WhisperKit now sets `detectLanguage` when language is Automatic. That bug is real: WhisperKit derives detection from `usePrefillPrompt`, so Automatic was silently English.
- Detected language is passed into the styler on both platforms, so Automatic + Hindi can get a danda instead of `.`.
- iOS finally has `TranscriptSanitizer`, including the repetition-loop collapse Android just grew. The failure path after `clean()` returns empty was not ported.
- whisper.cpp decode status is no longer discarded.
- Audio levelling is applied to the in-memory copy only. The WAV on disk and the gateway upload are untouched. Confirmed in `LocalModelManager` on both platforms. The sherpa incremental/IME path never calls it.
- Fast / Balanced / Accurate and a Whisper-only custom-word list. Sherpa decoding method is derived from model family, not from the setting alone. Tests walk every family × quality pair.
- Engine preload on accuracy/model change. Android names the model on the dictation status line. iOS dictation still says "Transcribing on this iPhone…" while Settings shows the load.

### Why it is not a merge today

**Blocker.** `RecordingCoordinator` at 813 (local), 679 (streaming), and 718 (gateway finish) sanitizes, then transitions to `readyToInsert` and deletes the file with no empty check. Android `deliverLocal` throws `emptyTranscript()` first. On iOS, auto-insert then calls `insert`, which returns on empty text, and the keyboard can sit on "Inserting automatically…" with nothing to retry. That is the class of output the sanitizer was added to catch.

The IME/sherpa live path never sees the new gain. `SpeechAudioConditioning` is called from the whole-file `transcribe` methods. Incremental sessions feed raw chunks into sherpa and keep non-blank text. For Parakeet that is the keyboard path. Whisper still gets levelling because it still decodes at finish.

iOS `startSherpaIncrementalSession` calls `ensureSherpaRecognizer` on the main actor after `recorder.start`. Settings preload is `try?` in a Task. Change accuracy and dictate immediately and ONNX init can hitch the UI with the mic already open. Android prepares off the main thread inside the session.

Language change does not preload. Sherpa bakes language into the recognizer. Quality change triggers a reload; `setLanguage` does not. SenseVoice/Canary switches still load on the next dictation.

No hardware run. Author's own list, still outstanding:

- Hindi dictation, Automatic, Whisper model: expect `।`
- SenseVoice Chinese: expect `。`
- Change accuracy, dictate immediately (preload)
- Any CTC model on Balanced (this is the `exit(-1)` footgun)
- Accurate on an older phone (Android beam 3; iOS is only extra temperature retries)

CI does not exercise native decode, JNI, or a real microphone. There is no test that `[BLANK_AUDIO]` fails the iOS session, and no test that a non-zero `whisper_full` status becomes an error rather than silence.

### Nits, not blockers

Android Balanced copy says "Beam search where it is cheap". For whisper.cpp, Balanced is still greedy (`whisperBeamSize = 0`). Beam is Accurate only. Sherpa transducers do get beam on Balanced.

iOS Accurate copy says "Widest search on every model". WhisperKit has no beam. Accurate only raises `temperatureFallbackCount` (3 → 5). The control does not mean the same thing as Android whisper.cpp.

JNI `fullTranscribe` does not null-check `GetFloatArrayElements` / `GetStringUTFChars`, and it still releases `language` if the get failed. Accurate now runs beam search on that same call, so an OOM get is more plausible than it was on greedy.

`throw LocalModelManagerError.modelNotDownloaded("empty transcript")` renders as "Download empty transcript before using on-device transcription." Marker-only text is not empty at that guard, so it does not save you anyway.

The sherpa bridge now copies `result->lang`. `LocalModelManager.swift` still says the bridge does not expose language.

### Copy-paste review comments for #67

Post these on the PR. First one is a top-level review summary; the rest are line comments.

**Summary (Request changes)**

```
CI is green and the exit(-1) gate looks right. Do not merge yet.

The iOS sanitizer port left out the failure path. `TranscriptSanitizer.clean` turning `[BLANK_AUDIO]` into `""` is then saved as readyToInsert and the WAV is deleted. Android throws emptyTranscript() before it deletes the file. Same hole on the iOS gateway/streaming finish paths. Auto-insert then no-ops on empty text and the keyboard can sit on "Inserting automatically…" with nothing to retry. Check emptiness after sanitize+style, fail recoverable, keep the audio. Add a test that `[BLANK_AUDIO]` does not become a successful session.

Also:

1. The new gain is not applied on the sherpa incremental/IME path. Whole-file `transcribe` levels the buffer; `startIncrementalSession` does not. Whisper still benefits because it still decodes at finish. Say that in the PR body, or fall back to the conditioned WAV when incremental text is only quiet-mic junk.
2. Hardware, as you listed, plus Accurate on a slow phone: Hindi + Automatic + Whisper (danda), SenseVoice Chinese (`。`), CTC on Balanced (must not die), accuracy change then immediate dictate.
3. Android dictation names the model being loaded. iOS dictation always says "Transcribing on this iPhone…". Hook `loadingMessage` into the dictation status / Live Activity.
```

**`ios/VocaPhoneApp/Sessions/RecordingCoordinator.swift:813`** (blocker)

```
This is the sanitizer port with the failure path left out. `clean()` turning `[BLANK_AUDIO]` into `""` is then saved as readyToInsert, the WAV is deleted, and `markTranscriptDelivered` runs. Android throws `emptyTranscript()` at `DictationController.kt:715` before it deletes the file. Same hole on the gateway paths at 679 and 718.

After this, auto-insert in KeyboardViewController.handle calls insert, which returns on empty text, then returns without render. The keyboard can sit on "Inserting automatically…" with nothing to retry. Check emptiness after sanitize+style, fail recoverable, keep the audio. Mirror the Android local path, including a test that `[BLANK_AUDIO]` does not become a successful session.
```

**`android/app/src/main/java/com/vocahq/vocaphone/local/LocalModelManager.kt:350`** (high)

```
The PR says audio is levelled before a local engine sees it. That is only true for `transcribe()`. `startIncrementalSession` feeds raw frames into sherpa and keeps the result if it is non-blank, so the quiet-mic case never hits `SpeechAudioConditioning`. iOS is the same in `startSherpaIncrementalSession`.

Either fall back to the conditioned whole file when the recording peak is below the target, or apply one gain computed from the finished WAV before accepting the incremental text. Right now the levelling fix does not run for the engine family that streams.
```

**`ios/VocaPhoneApp/Models/LocalModelManager.swift:825`** (high)

```
`throw LocalModelManagerError.modelNotDownloaded("empty transcript")` shows "Download empty transcript before using on-device transcription." Do not reuse that case. More important: this guard never sees `[BLANK_AUDIO]`, so it does not save you. The coordinator has to treat post-clean empty as a transcription failure.
```

**`ios/VocaPhoneApp/Sessions/RecordingCoordinator.swift:492`** (medium)

```
Recording has already started, then `startSherpaIncrementalSession` calls `ensureSherpaRecognizer` on the main actor with no yield. Settings preload is `try?` in a detached Task. Change accuracy and hit Dictate immediately and this can hitch the UI for the whole ONNX load, with the mic already open. Android does `prepare()` inside the incremental session off the main thread. Either await the in-flight prepare, or load off the main actor before `recorder.start`.
```

**`ios/VocaPhoneApp/Sessions/RecordingCoordinator.swift:774`** (medium)

```
Android mirrors `localModels.state.preparing` into `DictationState.statusDetail` so the status line says `Loading Parakeet TDT…` instead of looking hung. iOS dictation always says "Transcribing on this iPhone…" even while `LocalModelManager.loadingMessage` is set. Settings shows it; the dictation path does not. Hook `loadingMessage` into `message` / Live Activity here the same way.
```

**`android/app/src/main/cpp/whisper/jni.c:36`** (medium)

```
`GetFloatArrayElements` and `GetStringUTFChars` are still unchecked, and `language` is released even if the get failed. You now return `whisper_full`'s status, which is the right change, but Accurate is beam search on this same call. If the get fails you still call `whisper_full` with a NULL pointer. Check both gets, return a negative status, and only release what you actually got. The prompt path already does that.
```

**`ios/VocaPhoneShared/TranscriptionQuality.swift:34`** (medium)

```
"Widest search on every model" is not what this does for WhisperKit. Android Accurate is `whisperBeamSize = 3`. iOS Accurate is five temperature retries and no beam. Either say that in the iOS copy, or stop implying the platforms share a search strategy. Balanced vs Accurate on a Whisper model will not feel like the Android control.
```

**`android/app/src/main/java/com/vocahq/vocaphone/core/TranscriptionQuality.kt:31`** (nit)

```
"Beam search where it is cheap" is not what whisper.cpp Balanced does. `whisperBeamSize` is 0 for Fast and Balanced, 3 for Accurate.

Sherpa transducers do `modified_beam_search` on Balanced, so the sentence is half right. People reading this in Settings will think Balanced already bought them a beam on Whisper. It did not.
```

---

## #40 Follow VocaMac and Handy model selections

- URL: https://github.com/VocaHQ/vocaphone/pull/40
- Branch: `feat/follow-desktop-model-selection`
- Size: +617 / −56, 10 files, 1 commit (8 August)
- CI: iOS and server Quality passed on that commit
- Merge state: **CONFLICTING**
- Author comment: merge [vocamac#200](https://github.com/VocaHQ/vocamac/pull/200) first. That PR is still open, `CHANGES_REQUESTED`, and `BLOCKED`.

### Verdict

**Do not merge.** The idea is fine. The PR cannot land on current `main`.

On 8 August this repo still owned `server/` as a normal tree. `main` now has `server` as a gitlink to [vocagateway](https://github.com/VocaHQ/vocagateway) (`160000`). Git reports the Python files as "removed in local" against a new submodule commit. CONTRIBUTING already says gateway work goes to vocagateway, then a pin bump here.

There is no open vocagateway PR that carries this work. vocagateway `app/models/handy.py` is still the old snapshot (`self.model = model or self._read_selected_model()`).

Conflicts that are not the submodule:

- `README.md`, `docs/architecture.md`, `docs/troubleshooting.md` (docs, messy but ordinary)
- `ios/VocaPhoneApp/Networking/GatewayClient.swift`: finish timeout 90s → 540s. `main` also added streaming-support keys in #58. A sloppy "take theirs" drops that. Keep `main`'s file and change only `timeoutInterval`.

CLI contract vs vocamac#200 matches: `--transcribe-file`, `--list-models --json`, `--model`, `--language`, `--json`, and the error codes `model_not_found` / `model_not_downloaded` / `model_unsupported`. The binary scan for `--transcribe-file` is the right way to avoid launching an old GUI. Do not throw that away in the rewrite. Until #200 actually merges, `_headless_supported()` stays false on shipping VocaMac builds and users with Parakeet selected keep hitting the legacy "not a WhisperKit model" error.

### Bugs to keep when this is re-filed

Handy still ignores session language. `transcribe(..., options)` never passes `options.language` into the CLI. The PR body says "Pass each VocaPhone session's language explicitly". That is true for VocaMac headless and false for Handy. vocamac#200 does accept `--language`. Handy may not; if it does not, the PR should say so.

`is_available` is documented as a cheap sync check used while resolving `auto`. The new path calls `_headless_model()`, which runs `VocaMac --list-models --json` with a 5s timeout. Timeouts, nonzero exits, and bad JSON are swallowed as `None`, so `auto` quietly skips VocaMac and walks off to Handy or WhisperKit. The file scan in `_headless_supported()` is the side-effect-free part; `is_available` then execs the binary. Keep the scan as the cheap check. Cache the catalog. Log list-models failures instead of treating a hung CLI as "VocaMac is not installed."

iOS finish timeout becomes 9 minutes. Android `GatewayClient.finish` on current main is still 120s. A one-shot VocaMac load that needs the new timeout will still fail from Android. If you bump iOS, bump Android in the same change, or say why iOS is the only client that waits.

### Copy-paste review comments for #40

**Summary (Do not merge / close-and-retarget)**

```
Cannot merge this onto current main.

`server/` is now the vocagateway submodule. This branch still treats it as an in-tree Python tree, so Git sees a dirty conflict (submodule gitlink vs the old files) plus the docs/iOS timeout hunks. CONTRIBUTING already requires gateway changes to land in https://github.com/VocaHQ/vocagateway, then a pin bump here.

Please:

1. Wait for vocamac#200 (you already noted this). It is still CHANGES_REQUESTED / BLOCKED. The CLI flags this PR calls match that PR. Good. Do not ship an adapter against a CLI that is not on vocamac main.
2. Open the Python work against vocagateway. There is no vocagateway PR for this today; handy.py on vocagateway main is still the old "read settings once at init" code.
3. Rebase the iOS timeout onto current vocaphone main by keeping main's GatewayClient (it gained streaming-support keys in #58) and changing only timeoutInterval. Android finish is still 120s on main, so a one-shot VocaMac load that needs nine minutes will still die from the Android client.

I went through the VocaMac adapter. The `--transcribe-file` byte scan instead of probing an old binary with an unknown flag is the right approach; keep that. A couple of code comments below still apply in the vocagateway rewrite.
```

**`server/app/models/handy.py:56`** (medium)

```
`options` is unused. The PR says each VocaPhone session language is passed explicitly. VocaMac headless does that (`--language`, including `auto`). Handy does not.

If the Handy CLI accepts `--language`, pass `options.language` here. If it does not, drop the claim from the PR body so a Hindi session is not assumed to be forwarded.
```

**`server/app/models/vocamac.py:89`** (high)

```
`is_available()` is not the cheap filesystem check `build_engine` thinks it is. After `_headless_supported()` you call `_headless_model()`, which `subprocess.run`s `VocaMac --list-models --json` with a 5s timeout and swallows every failure as `None`. `auto` then quietly skips VocaMac.

The scan in `_file_contains` is the side-effect-free part. This function is not. Cache the catalog, log list-models failures, and do not treat a hung CLI as "VocaMac is not installed."
```

**`ios/VocaPhoneApp/Networking/GatewayClient.swift:124`** (high)

```
540s is the right order of magnitude for a 300s VocaMac one-shot, but this file conflicts with main, and main added streaming-support keys in #58. Rebase by keeping main's GatewayClient and changing only `timeoutInterval` (line 153 on main, currently 90). Do not take this branch's copy of the file.

Android `GatewayClient.kt` finish is still 120s. A first-load that the comment says can spend several minutes will time out on Android while iOS waits. Match Android to this timeout or drop the claim.
```

---

## Cross-PR notes

The two PRs do not touch the same runtime path. #67 is on-device iOS/Android. #40 is gateway + one iOS timeout. No merge-order coupling except that #40 cannot rebase until it is split.

No other open PRs on this repository at review time.
