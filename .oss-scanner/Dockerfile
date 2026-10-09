# OSS Scanner build for VocaPhone's Android app (android/). The iOS app needs Xcode
# and cannot be built here; see threat_model.md.
#
# Mirrors .github/actions/setup-android: JDK 21 (pinned by
# android/gradle/gradle-daemon-jvm.properties), NDK 27.2.12479018, CMake 3.22.1.
# The network is gone after this build, so Gradle runs once here to fill its cache;
# afterwards `./gradlew --offline testFullDebugUnitTest --rerun` works from android/.
FROM eclipse-temurin:21-jdk-noble

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_OPTS="-Dorg.gradle.daemon=false"
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git unzip python3 build-essential clang cmake ninja-build pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Android command-line tools, then the NDK and CMake the app's native build names.
# Platforms and build-tools are left to AGP, which fetches the ones it wants.
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
         https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager --install "platform-tools" "ndk;27.2.12479018" "cmake;3.22.1" >/dev/null

# scanner contract: the checkout lives inside the image, at /src
COPY . /src
WORKDIR /src

# The scanner clones without submodules. whisper.cpp is compiled into the app's JNI
# library, so it is in scope; gateway/ is VocaGateway, a separate project.
RUN git config --global --add safe.directory '*' \
    && git submodule update --init --depth 1 android/third_party/whisper.cpp

# Build the debug app (Kotlin + whisper.cpp JNI for every ABI) and run the JVM unit
# tests, which also resolves every test dependency into the Gradle cache. A failing
# test must not fail the image.
WORKDIR /src/android
RUN ./gradlew --no-daemon assembleFullDebug \
    && (./gradlew --no-daemon --continue testFullDebugUnitTest \
        || echo "WARNING: unit tests reported failures")
