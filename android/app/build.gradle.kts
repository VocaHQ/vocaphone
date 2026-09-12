plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

android {
    namespace = "com.vocahq.vocaphone"
    // Current AndroidX releases require compiling against API 37. targetSdk
    // stays at 36, which is what Play requires from 31 August 2026.
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.vocahq.vocaphone"
        minSdk = 33
        targetSdk = 36
        versionCode = 28
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // The keyboard's word list and bigram table are one file each, shared with
    // the iOS keyboard from the repository root. Two hand-maintained copies of
    // a 10 000-word list drift, and nothing would notice until the two
    // platforms started suggesting different words.
    sourceSets {
        getByName("main") {
            assets.srcDir("${rootDir}/../assets/keyboard")
        }
    }

    flavorDimensions += "distribution"

    // Anonymous usage reporting goes to a self-hosted Aptabase instance.
    //
    // The key is committed on purpose, and it is not in the same class as the
    // signing keystore above. It is an append-only ingest credential: it can
    // add events and cannot read the dashboard, change the app, or reach
    // anything else. It also ships inside every APK, so it can be extracted
    // from a Play download in seconds -- keeping it out of the repository would
    // hide it from contributors and from nobody else, while making release
    // builds depend on an environment variable that is easy to forget and
    // silently disables reporting when it is missing.
    //
    // The real exposure is someone posting junk events to skew the beta
    // numbers. That is handled where it can actually be handled -- rate
    // limiting at the reverse proxy -- and by rotating this key if it ever
    // happens, which costs one release.
    //
    // Forks override it with -PaptabaseAppKey= or APTABASE_APP_KEY. An empty
    // value disables transmission entirely rather than falling back to ours.
    val aptabaseHost = "https://telemetry.vocahq.com"
    val aptabaseKey = providers.gradleProperty("aptabaseAppKey").orNull
        ?: System.getenv("APTABASE_APP_KEY")
        ?: "A-SH-3275173609"

    productFlavors {
        // Everything the project ships itself. sherpa-onnx reaches Android as a
        // prebuilt JNI library, so its .so files live in this flavor's source
        // set rather than in src/main.
        create("full") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "SHERPA_ONNX", "true")
            buildConfigField("boolean", "TELEMETRY", "true")
            buildConfigField("String", "APTABASE_HOST", "\"$aptabaseHost\"")
            buildConfigField("String", "APTABASE_KEY", "\"$aptabaseKey\"")
        }
        // F-Droid builds every byte it ships from source, which rules out the
        // prebuilt sherpa-onnx and ONNX Runtime libraries. This flavor drops
        // them and leaves whisper.cpp, which is compiled from the pinned
        // submodule, as the on-device engine.
        //
        // It also compiles usage reporting out. TELEMETRY is a constant
        // false, so R8 removes AptabaseSink, the host, the app key and every
        // control that could switch reporting on. Scanning the release dex
        // finds no host, no ingest path and no App-Key header; what survives is
        // an unreachable queue whose sink is the no-op. That keeps this flavor
        // clear of F-Droid's Tracking anti-feature without anyone having to
        // trust a runtime check, and it leaves the flavor's dependency graph --
        // and so the reproducible build -- exactly as it was.
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "SHERPA_ONNX", "false")
            buildConfigField("boolean", "TELEMETRY", "false")
            buildConfigField("String", "APTABASE_HOST", "\"\"")
            buildConfigField("String", "APTABASE_KEY", "\"\"")
        }
    }

    if (!project.hasProperty("skipNative")) {
        ndkVersion = "27.2.12479018"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/whisper/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    signingConfigs {
        create("release") {
            // Reads android/keystore.properties (gitignored) on dev machines and
            // falls back to CI env vars injected from GitHub secrets.
            val props = Properties().apply {
                val propFile = rootProject.file("keystore.properties")
                if (propFile.exists()) propFile.inputStream().use { load(it) }
            }
            val env = System.getenv()
            val storePath = props.getProperty("storeFile") ?: env["KEYSTORE_FILE"]
            val storePass = props.getProperty("storePassword") ?: env["KEYSTORE_PASSWORD"]
            val alias = props.getProperty("keyAlias") ?: env["KEY_ALIAS"]
            val keyPass = props.getProperty("keyPassword") ?: env["KEY_PASSWORD"]
            if (storePath != null) storeFile = file(storePath)
            if (storePass != null) storePassword = storePass
            if (alias != null) keyAlias = alias
            if (keyPass != null) keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Builders without the keystore -- F-Droid, or anyone checking out
            // the tree -- get an unsigned release APK instead of a Gradle
            // failure over a signing config that could not be populated.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP embeds a "Dependency metadata" block in the APK signing block that
    // lists every dependency and its hash. Google Play reads it for tracking,
    // and F-Droid's scanner rejects APKs that carry it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("vocaphone-${variant.name}.apk")
            }
        }
    }

    compileOptions {
        // The bytecode level is not the reproducibility knob: F-Droid rebuilds
        // this APK on JDK 21, and byte-identical output requires compiling with
        // the same JDK, whatever the target level. The project builds on JDK 21
        // (gradle-daemon-jvm.properties, setup-android) for that reason.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        // Gradle does not pass the daemon's system properties to the test JVM,
        // so the keyboard's timing harness could not be switched on from the
        // command line without this. Absent by default, which is what keeps the
        // harness out of an ordinary `test` run.
        unitTests.all { test ->
            System.getProperty("vocaphone.benchmark")?.let {
                test.systemProperty("vocaphone.benchmark", it)
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        // targetSdk 36 is deliberate: it is what Play requires from 31 August
        // 2026, and moving further would opt the app into runtime behaviour it
        // has not been tested against on a physical Pixel.
        disable += "OldTargetApi"
        // The Compose compiler plugin has to match the Kotlin version AGP builds
        // with, so it cannot simply track the newest release.
        disable += "NewerVersionAvailable"
    }
}

// ONNX Runtime for the `full` flavor's sherpa-onnx engine.
//
// `libsherpa-onnx-jni.so` stays committed in app/src/full/jniLibs and has to:
// it is rebuilt against ONNX Runtime 1.28.0 because sherpa-onnx's published
// Android build pins 1.27.1, which miscomputes zipformer encoders on SM8850, so
// ours matches no released artifact. `libonnxruntime.so` is the opposite --
// byte for byte the file inside the archive below -- so 37 MB of it sat in git
// for no reason. Gradle fetches it from the ivy repository declared in
// settings.gradle.kts instead, which caches and shares it like any other
// dependency and honours `--offline`.
//
// The two libraries are replaceable only as a pair: the JNI library imports
// exactly one symbol from the runtime, `OrtGetApiBase`, and it is version
// tagged `@VERS_1.28.0`. Raising the version here means rebuilding the JNI
// library against it -- app/src/full/jniLibs/README.md has the recipe.
//
// Only `full` variants wire the task in, so an F-Droid build resolves nothing
// and reaches no network: it neither ships sherpa-onnx nor configures a variant
// that asks for it.

// Gradle caches a downloaded artifact but does not check its integrity unless
// the whole project opts into dependency verification, and this archive decides
// whether dictation works, dies on SIGILL, or silently returns wrong text on a
// whole class of device. So the task hashes it before unpacking.
//
// "Before unpacking", not "on every build": once the task is up to date Gradle
// treats files under caches/modules-2 as immutable and does not re-read them,
// so corrupting the cached zip afterwards is not detected. What the check
// guarantees is that nothing reaches the APK without having been verified on
// the way in -- which is the property that matters, since the unpacked output
// is what gets packaged.
//
// Bumping the version in gradle/libs.versions.toml means changing this too.
val onnxRuntimeSha256 = "7fb1d81f1fbb3e660e34baf20d8edbe5aebaa0df2793ae27d24aeade39290d1f"

// sherpa-onnx ships its JNI library for these two ABIs only, so the x86 and
// x86_64 runtimes in the archive -- 52 MB of them -- would be weight in the APK
// that nothing can call. x86_64 is in abiFilters for the emulator, where
// whisper.cpp is the engine.
val onnxRuntimeAbis = setOf("arm64-v8a", "armeabi-v7a")

// Gradle 9 splits the two halves of what used to be one configuration: the
// dependency-scope one is what `dependencies` may declare against, and the
// resolvable one is what a task may read files from.
val onnxRuntimeArchive = configurations.dependencyScope("onnxRuntimeArchive")
val onnxRuntimeArchiveFiles = configurations.resolvable("onnxRuntimeArchiveFiles") {
    extendsFrom(onnxRuntimeArchive.get())
    isTransitive = false
}

androidComponents {
    onVariants(selector().withFlavor("distribution", "full")) { variant ->
        val unpack = tasks.register<UnpackOnnxRuntime>(
            "unpack${variant.name.replaceFirstChar(Char::titlecase)}OnnxRuntime",
        ) {
            description = "Verifies and unpacks the pinned ONNX Runtime libraries."
            archive.from(onnxRuntimeArchiveFiles)
            version.set(libs.versions.onnxruntime)
            sha256.set(onnxRuntimeSha256)
            abis.set(onnxRuntimeAbis)
        }
        // AGP picks the output directory and wires the task dependency, which
        // is why this is registered per variant rather than shared: two
        // variants cannot be handed the same generated directory.
        //
        // checkNotNull rather than `?.`: a `full` variant with no jniLibs source
        // container would drop the runtime out of the APK, and the failure would
        // land at `dlopen` on a user's phone rather than here.
        checkNotNull(variant.sources.jniLibs) {
            "${variant.name} has no jniLibs sources to add the ONNX Runtime to"
        }.addGeneratedSourceDirectory(unpack, UnpackOnnxRuntime::outputDir)
    }
}

abstract class UnpackOnnxRuntime : DefaultTask() {
    // NONE: the archive is identified by the content hash checked below, and
    // its path inside the Gradle cache is not something a build should be
    // sensitive to.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: ConfigurableFileCollection

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val abis: SetProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun unpack() {
        val zip = archive.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        zip.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != sha256.get()) {
            throw GradleException(
                """
                ONNX Runtime ${version.get()} does not match its pinned SHA-256.
                  file     $zip
                  expected ${sha256.get()}
                  actual   $actual
                If the version in gradle/libs.versions.toml was just raised,
                onnxRuntimeSha256 in app/build.gradle.kts has to move with it --
                that is this failure, and re-downloading will not change it.
                Otherwise delete the file and build again; a hash that is still
                wrong means the release asset itself changed, and nothing should
                ship against it until that is explained.
                """.trimIndent(),
            )
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        ZipFile(zip).use { opened ->
            for (abi in abis.get()) {
                val path = "jni/$abi/libonnxruntime.so"
                val entry = opened.getEntry(path)
                    ?: throw GradleException("$zip does not contain $path")
                val target = out.resolve(abi).apply { mkdirs() }.resolve("libonnxruntime.so")
                opened.getInputStream(entry).use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        // Kept at 17 on purpose, matching compileOptions above; see the comment
        // there on why the compiling JDK (21), not this level, is what has to
        // agree with the F-Droid buildserver.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // Artifact-only notation: a GitHub release asset has no metadata
    // module beside it, so the extension is what names the file. The
    // `full` flavor unpacks two libraries out of it; see UnpackOnnxRuntime.
    add(onnxRuntimeArchive.name, "com.github.csukuangfj:onnxruntime:${libs.versions.onnxruntime.get()}@zip")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // TelemetryVocabularyTest walks Telemetry's public signatures to assert none
    // of them can carry free text. Test-only: nothing in the APK reflects.
    testImplementation(kotlin("reflect"))
    testImplementation(libs.okhttp.mockwebserver)
    // The unit-test android.jar only stubs org.json; the real implementation lets
    // the gateway client be tested against MockWebServer on a plain JVM.
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
