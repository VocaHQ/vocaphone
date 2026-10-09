# Threat model: VocaPhone (Android)

## Scope
- **In scope:** the Android app in `android/`. That covers the Kotlin sources in
  `android/app/src/main/java/com/vocahq/vocaphone/`, the JNI glue in
  `android/app/src/main/cpp/whisper/jni.c`, and the pinned whisper.cpp submodule
  under `android/third_party/whisper.cpp`, as the app compiles and calls it.
- **Out of scope:**
  - `ios/` (needs Xcode and macOS, so it can't be built in this Linux image).
  - `gateway/` (a submodule of VocaGateway, which is a separate project).
  - `web/`, `brand/`, `tools/` and `docs/`.
  - The prebuilt sherpa-onnx `.so` files in `android/app/src/full/jniLibs` (third-party binaries). Their Kotlin wrapper in `com.k2fsa.sherpa.onnx` *is* in scope.
- There are three build flavors. `full` is the default and what the image builds. `x` swaps the keyboard for a
  floating bubble plus an accessibility service. `fdroid` drops sherpa-onnx and telemetry.

## What the app does
VocaPhone is an on-device dictation keyboard (an Android IME). The user speaks
and the text is typed into whatever app has focus. Speech is transcribed on the
phone by whisper.cpp or sherpa-onnx. Optionally, the audio can go to a
**VocaGateway** that the user runs themselves (on their LAN, mDNS or a tailnet).
The app talks to the gateway over HTTP(S) and WebSocket, using a bearer token.

## Where untrusted input enters
1. **Gateway responses.** Everything in `gateway/` (`GatewayClient`, `GatewayAudioStream`, the upload paths) is
   untrusted: JSON, status codes and WebSocket frames. A malicious gateway, or an attacker on the LAN when the
   connection is cleartext, controls all of it, including transcripts that the app then types into other apps.
2. **Gateway address and pairing.** `core/GatewayEndpoint.validate` must refuse `http://` for any host that is
   publicly reachable, and must refuse URLs with userinfo, a query or a fragment. `core/PairingPayload.parse`
   reads JSON decoded from an arbitrary QR code (`ui/QrPairingActivity`). A bypass that sends the bearer token
   or the audio in cleartext to a public host, or that lets an attacker swap in their own gateway address,
   is a vulnerability.
3. **Model downloads.** `local/ModelDownloadService`, `LocalModelManager` and `LocalModelIntegrity` fetch models
   from Hugging Face and GitHub releases. Each file is pinned by size and SHA-256 in `LocalModelCatalog` /
   `SherpaModelCatalog`. Anything that lets an unverified, truncated or substituted file be loaded is in
   scope. So are path traversal through archive entries or file names, and TOCTOU between verifying a file
   and loading it.
4. **Model files and audio reaching native code.** whisper.cpp parses GGML model files, and `jni.c` passes
   audio buffers, lengths and prompt strings from Kotlin. Memory-safety bugs at that boundary count, even
   when the file passed integrity checks, because pins can be updated.
5. **Other apps on the device.**
   - The exported surfaces are `MainActivity` (the launcher entry), `VocaPhoneInputMethodService` (guarded by
     `BIND_INPUT_METHOD`) and, in the `x` flavor, `VocaPhoneAccessibilityService` (guarded by
     `BIND_ACCESSIBILITY_SERVICE`).
   - Everything else is `exported="false"`, including the `FileProvider` at `${applicationId}.clipboard`,
     `DictationService`, `ModelDownloadService`, `DictationLauncherActivity` and `QrPairingActivity`.
   - Intents that a third-party app can deliver or influence are in scope: extras read in `MainActivity` and
     `DictationService`, and `grantUriPermissions` on the clipboard provider.
6. **Text fields in other apps.** The IME and the accessibility service see the focused field's metadata and a
   bounded cursor context. `core/ImeInputPolicy` and `core/FieldEligibility` must keep dictation and
   suggestions out of password and other sensitive fields.

## Security goals
- The microphone records only after an explicit user action, and audio never leaves the device unless a
  gateway is configured.
- No transcript, typed text or field content goes to telemetry. `telemetry/` sends only enumerated events to
  Aptabase, and the committed app key is intentionally public (see `app/build.gradle.kts`).
- The gateway bearer token is sealed with an Android Keystore AES-GCM key (`security/TokenVault`). It is never
  logged, never written in plaintext, and never sent in cleartext to a public host.
- A hostile gateway can return wrong transcripts. That is expected. It must not be able to crash the app in a
  loop, execute code, read local files, or type into fields other than the one being dictated into.

## Known, intentional decisions (not vulnerabilities by themselves)
- `network_security_config.xml` permits cleartext and trusts user-installed CAs, because self-hosted gateways
  usually lack public certificates. The app-level `GatewayEndpoint` check is the control, so report bypasses
  of that check, not the config itself.
- The Aptabase ingest key is public on purpose.
- `allowBackup="false"`. Release signing keys are not in the repository.

## How to exercise it
- The image has the debug APK built and the Gradle cache filled. From `/src/android`:
  `./gradlew --offline testFullDebugUnitTest`. The JVM unit tests live in `app/src/test/java` and use
  MockWebServer for the gateway client. A single test runs with `--tests '<class>'`.
- To get a host (x86-64) build of whisper.cpp plus `jni.c`, for harnessing the JNI boundary:
  `cmake -S app/src/test/cpp -B build/host-whisper && cmake --build build/host-whisper -j`.
- There is no emulator, so instrumented tests (`app/src/androidTest`) don't run here.

## How we rate severity
- **Critical:**
  - Remote code execution in the app from a gateway response, a QR code or a model file.
  - The bearer token or audio/transcripts going to an attacker-controlled host without user action.
- **High:**
  - Memory corruption in `jni.c` or whisper.cpp reachable from app-supplied input.
  - Bypass of the cleartext/public-host rule in `GatewayEndpoint`.
  - Model integrity bypass that loads an attacker-chosen file.
  - Another app reading files through the clipboard `FileProvider`, or starting recording without consent.
  - Dictation landing in password or other sensitive fields.
- **Medium:**
  - Persistent denial of service: crash loops from gateway data, or a broken keyboard until the app data is
    cleared.
  - Leaking transcripts or field content to logs or telemetry.
  - Path traversal confined to app-private storage.
- **Low:** a one-off crash, or a resource exhaustion that needs the user's own malicious gateway and clears
  itself.
- Attacks that need root, a compromised OS or physical access to an unlocked phone are out of scope.
- Native crashes without a plausible control path are capped at High.

## Reports and patches
- Patches should be minimal Kotlin or C changes with a JVM unit test where possible. Do not modify vendored
  whisper.cpp in place. Report upstream bugs as upstream issues.
- Treat issues already filed on GitHub as duplicates.
