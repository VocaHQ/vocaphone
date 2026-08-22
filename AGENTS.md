# Agent instructions

This file is for coding agents working in this repository. Humans should start
at [README.md](README.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

VocaPhone is a voice keyboard for iPhone and Android. Speech-to-text runs on
the phone or on optional self-hosted VocaGateway, never on a cloud speech
service. Typing intelligence (completions, corrections, swipe, emoji) is
on-device and must not grow a network path.

## Layout

| Path | What |
|---|---|
| `android/` | IME + companion app. Read [android/AGENTS.md](android/AGENTS.md) before changing the keyboard. |
| `ios/` | Keyboard extension + containing app. Typing design is in [docs/architecture.md](docs/architecture.md) under “Typing intelligence”. |
| `assets/keyboard/` | One English word list, bigram table, and emoji catalog. Both platforms read these files. Do not fork a second copy. |
| `gateway/` | Pinned [vocagateway](https://github.com/VocaHQ/vocagateway) submodule. Gateway code changes go there, then bump the pin here if this repo needs the new revision. |
| `docs/` | Architecture, privacy, releasing, Play, TestFlight. |

## Scope

- Keep platform work on that platform. An Android IME PR does not change iOS,
  and the reverse, unless the user asked for both.
- Stacked GitHub PRs: Android follow-ups branch off the open Android PR (for
  example `android/predictive-text`), not off `main`, so review stays stacked.
- Do not weaken loopback gateway binding, bearer auth, upload limits,
  retention, or microphone indicators without an explicit tradeoff.

## Commands

From the repository root, `just --list` shows recipes. Android lives in
`android/justfile` (`just build`, `just run`, `just keyboard-benchmark`). iOS
lives in `ios/justfile`. `just doctor` reports missing toolchains and never
fails because nobody has all three.

## Tests

Match the code you touched. Android IME behavior is unit-tested under
`android/app/src/test/java/com/vocahq/vocaphone/ime/`. Do not load the
10k-word list on the composition thread to make a test pass.
