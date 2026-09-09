# Sherpa ONNX Android runtime

`libsherpa-onnx-jni.so` is the prebuilt native library behind the `full`
flavor's Sherpa model engine. The `fdroid` flavor drops this directory and
builds without it.

- Source: [sherpa-onnx v1.13.6](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.6)
  (`1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911`)
- ONNX Runtime: **1.28.0**, taken from
  [csukuangfj/onnxruntime-libs](https://github.com/csukuangfj/onnxruntime-libs/releases/tag/v1.28.0),
  which is the same source sherpa-onnx's own build script uses
- Toolchain: NDK 27.2.12479018, `android-21`, `CMAKE_BUILD_TYPE=Release`

`libonnxruntime.so` is **not** in this directory. It is byte for byte the file
inside that release archive, so Gradle fetches it instead: the version is pinned
in `gradle/libs.versions.toml`, the archive is addressed through the ivy
repository in `settings.gradle.kts`, and `UnpackOnnxRuntime` in
`app/build.gradle.kts` checks its SHA-256 before unpacking the two Arm ABIs into
the `full` variants' JNI libraries. Nothing downloads for an `fdroid` build.

The two libraries can still only be replaced as a pair: `libsherpa-onnx-jni.so`
imports exactly one symbol from ONNX Runtime, `OrtGetApiBase`, and it is version
tagged (`@VERS_1.28.0`). Raising the version in the catalog without rebuilding
the JNI library here fails at `dlopen`, on the phone, not in the build.

## Why the JNI library is built here rather than downloaded

sherpa-onnx's published Android release pins ONNX Runtime 1.27.1, and the
version of ONNX Runtime matters on Snapdragon 8 Elite Gen 5 (SM8850), which is
the first Arm SoC we ship to that implements SME but **not** SME2:

- Up to and including **1.23.2** — what we shipped through 0.1.0-beta.17 —
  `MLAS_PLATFORM` installs the KleidiAI GEMM and convolution overrides whenever
  `HasArm_SME()` is true, but every ukernel those overrides call is an SME2 one
  (`..._sme2_mopa`, `..._sme2_mla`). On an SME-without-SME2 core the first
  matmul executes an instruction the CPU does not have and the process dies with
  `SIGILL`. Upstream:
  [#26377](https://github.com/microsoft/onnxruntime/issues/26377),
  [#26678](https://github.com/microsoft/onnxruntime/issues/26678) (OnePlus 15 on
  1.23.2, our exact crash), fixed by
  [#27403](https://github.com/microsoft/onnxruntime/pull/27403).
- **1.27.x** no longer crashes, but miscomputes zipformer encoders on the same
  SoC — no error, just wrong numbers
  ([sherpa-onnx#3845](https://github.com/k2-fsa/sherpa-onnx/issues/3845)).
- **1.28.0** is the first release clear of both. It detects SME (HWCAP2 bit 23)
  and SME2 (bit 37) separately and picks the matching ukernel, and it adds a
  `mlas.disable_kleidiai` session option as an escape hatch.

That is also why Microsoft's `com.microsoft.onnxruntime:onnxruntime-android`
AAR is not what we pull, even though it exists on Maven Central at the same
version: it is a different build of 1.28.0, it carries x86 and x86_64 runtimes
this app has no JNI library for, and the whole point of the version pin is that
we know which build of it was tested on SM8850.

## Rebuilding

```sh
curl -LO https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.0/onnxruntime-android-1.28.0.zip
# Must match onnxRuntimeSha256 in app/build.gradle.kts.
shasum -a 256 onnxruntime-android-1.28.0.zip
unzip -q onnxruntime-android-1.28.0.zip -d onnxruntime-1.28.0

git clone --depth 1 --branch v1.13.6 https://github.com/k2-fsa/sherpa-onnx.git
cd sherpa-onnx

export ANDROID_NDK="$HOME/Library/Android/sdk/ndk/27.2.12479018"
export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"
# Without this the scripts fetch their own default, which is 1.27.1.
export SHERPA_ONNX_ONNXRUNTIME_ROOT="$PWD/../onnxruntime-1.28.0"
# Same 16 KB page alignment the whisper libraries get from CMakeLists.txt.
export LDFLAGS="-Wl,-z,max-page-size=16384"

./build-android-arm64-v8a.sh
./build-android-armv7-eabi.sh
```

The SDK's CMake 3.22.1 is on the path deliberately: several of sherpa-onnx's
vendored dependencies still declare a `cmake_minimum_required` that CMake 4
rejects.

Then copy `libsherpa-onnx-jni.so` out of each `build-android-<abi>/install/lib/`
into the matching directory here — leave `libonnxruntime.so` where it is, Gradle
supplies that one — and run `just ci`, which checks the APK's segment alignment
among everything else.

Everything else is left at the script's defaults, so these match the upstream
release in every respect but the ONNX Runtime version — TTS and speaker
diarization are compiled in even though VocaPhone only calls
`OfflineRecognizer`.

## Keeping the Kotlin bindings in step

`app/src/main/java/com/k2fsa/sherpa/onnx/` holds five files copied verbatim from
`sherpa-onnx/kotlin-api/` at the tag above. The JNI looks its config fields up
by name at runtime, so a library bump that adds a field — v1.13.6 added
`qnnConfig`, `hotwords` and `cohereTranscribe` — has to bring those files with
it or the lookup fails on a model it never used to touch.
