# Running the iOS app on your own machine

A plain-language walkthrough for getting VocaPhone building and running on
your Mac. If you've never opened this project in Xcode before, start here.
[Device setup](device-setup.md) picks up afterward with the full acceptance
checklist for a real iPhone; this page is just about getting to a running
build.

## What you actually need

- A Mac with **Xcode 26** (or newer) installed, plus its command-line tools.
- [`just`](https://just.systems) — the command runner every recipe below goes
  through. `brew install just`.
- [`xcodegen`](https://github.com/yonaskolb/XcodeGen) — this project doesn't
  commit a hand-edited Xcode project; `ios/project.yml` is the real source and
  `xcodegen` turns it into `VocaPhone.xcodeproj`. `brew install xcodegen`.
- [Git LFS](https://git-lfs.com/) — the on-device speech models under
  `ios/ThirdParty/SherpaOnnx/` are too big for a normal Git blob. If you
  haven't already: `brew install git-lfs && git lfs install`, then from the
  repo root, `git lfs pull`.

You do **not** need an Apple Developer account, a paid membership, or any
signing setup to build and run the app in the Simulator. That's only needed
once you want it on a physical iPhone (§3 below).

Run `just ios doctor` at any point — it checks for all of the above and tells
you exactly what's missing instead of failing partway through a build.

## 1. Get it running in the Simulator

From the repository root:

```sh
cd ios
just doctor   # confirms Xcode, xcodegen, and a simulator runtime are present
just run      # generates the project, builds, boots a simulator, installs, launches
```

The first run takes a few minutes — it's compiling WhisperKit and the app's
dependencies from scratch. After that, `just run` is quick.

What just happened: `just run` read `ios/project.yml` and generated
`ios/VocaPhone.xcodeproj` for you (this happens before every build, so the
checked-in project file always matches `project.yml` — don't hand-edit the
`.xcodeproj`). Then it built the app, unsigned, for the Simulator, and
launched it.

If you'd rather work in Xcode directly:

```sh
just edit    # regenerates the project, then opens it in Xcode
```

## 2. Add the keyboard and try dictating

The app itself is mostly a settings screen; the interesting part is the
custom keyboard. To see it:

```sh
just settings   # opens iOS Settings on the same simulator
```

Then, in Settings: **General → Keyboard → Keyboards → Add New Keyboard →
vocaphone**, and turn on **Allow Full Access** (needed so the keyboard can
read the shared state the app writes — see [privacy.md](privacy.md#full-access)
for exactly what that does and doesn't mean). Open any app with a text field
— Notes is the easiest — and switch to the vocaphone keyboard with the globe
key.

Typing, autocorrect, and swipe work immediately, no setup required. Actual
**dictation** needs a transcription source, which the app's guided setup
walks you through:

- **Fastest for local development:** in the app, go to **Settings →
  Transcription**, choose **On this iPhone**, and download a model. No
  gateway, no network, nothing else to run.
- **If you're also working on the gateway:** point the app at a gateway
  you're running locally — see the [main README's quick
  start](../README.md#quick-start) to get one up, then enter its URL and
  token under **Settings → Transcription → Gateway**.

If you just want to confirm the build works, the on-device model path is the
one to reach for — it's one screen and a download, with nothing else to
configure.

Useful while you're iterating:

```sh
just logs     # streams the app's and keyboard's console output
just stop     # kills the app on the simulator
just reset-sim  # wipes the simulator back to clean (drops the added keyboard)
```

## 3. Running it on your own iPhone

The Simulator can't exercise everything — background audio, App Group
sharing between the app and the keyboard, and real memory limits only show up
on a device. When you're ready to test on your own iPhone:

```sh
just device   # builds, installs, and launches on a connected iPhone
```

This needs your iPhone plugged in (or on the same network with Wi-Fi
debugging enabled), unlocked, and set to trust this Mac. It also needs
**code signing to actually work in Xcode first** — `just device` doesn't set
that up for you.

The project is already configured with VocaHQ's own identifiers
(`com.vocahq.vocaphone` and friends, on Apple Developer team `92962VK378` —
see [decisions.md](decisions.md) for the full list). What that means for you:

- **If you're a VocaHQ collaborator with access to that team,** open
  `ios/VocaPhone.xcodeproj` in Xcode, sign in with your Apple ID under Xcode
  → Settings → Accounts, and select the team on each of the three targets
  (VocaPhoneApp, VocaPhoneKeyboard, VocaPhoneLiveActivity) in **Signing &
  Capabilities**. Automatic signing handles the rest.
- **If you're not** — most outside contributors — you can't sign as
  `com.vocahq.vocaphone` and shouldn't try. Two options:
  - Ask a maintainer to comment `/build ios` on your pull request. CI
    produces a signed, ad-hoc IPA testers can install directly (see
    [CONTRIBUTING.md](../CONTRIBUTING.md#on-demand-pr-builds-build) for how
    that works), and you never need local signing at all.
  - Or, to run it on your own phone under your own free Apple ID: pick your
    own bundle ID prefix and App Group name, and change them in three
    places — `bundleIdPrefix` and each target's `PRODUCT_BUNDLE_IDENTIFIER`
    in `ios/project.yml`, the App Group string in all three
    `.entitlements` files, and `appGroupIdentifier` /
    `keyboardBundleIdentifier` in `ios/VocaPhoneShared/AppConfiguration.swift`.
    Run `just gen`, then sign in to Xcode with your own Apple ID. **Don't
    commit that change** — it's only for your own device.

Either way, once it's installed: open the app, grant microphone access, add
the keyboard under **Settings → General → Keyboard** the same way as in the
Simulator, and turn on Full Access. From there, [device
setup](device-setup.md) has the full pass — worth running through at least
once if you're touching audio, the keyboard, or App Group code, since that's
exactly the class of bug the Simulator can't catch.

## If something doesn't build

- `just doctor` first — it catches the most common causes (missing
  `xcodegen`, no simulator runtime downloaded) with a specific fix for each.
- `just ios gen` regenerates `VocaPhone.xcodeproj` from `project.yml`. Do this
  after pulling changes that touched `project.yml`, or if the project looks
  out of sync with what's on disk — `just build`/`just run`/`just test` all
  do this automatically, so it's rarely needed by hand.
- Missing model archives under `ios/ThirdParty/SherpaOnnx/` almost always
  means `git lfs pull` hasn't been run.
- Broader keyboard, microphone, or gateway-connection problems are covered in
  [troubleshooting.md](troubleshooting.md).
