pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets `updateDaemonJvm` regenerate gradle/gradle-daemon-jvm.properties and
    // lets a machine without a local JDK 21 provision one from foojay. The
    // pinned JDK version itself lives in that properties file.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // ONNX Runtime for the `full` flavor's sherpa-onnx engine, fetched as a
        // dependency rather than committed as a 37 MB pair of .so files.
        //
        // It cannot come from Maven Central. k2-fsa publishes nothing there at
        // all, and Microsoft's com.microsoft.onnxruntime:onnxruntime-android is
        // a different build of the same version -- our libsherpa-onnx-jni.so is
        // linked against this one, so the two can only be replaced as a pair.
        // This is the archive sherpa-onnx's own build script pulls, and the
        // libraries it carries are byte-for-byte the ones that used to be in
        // git; app/build.gradle.kts checks its SHA-256 before unpacking it.
        //
        // An ivy repository with an explicit layout is how Gradle addresses a
        // plain file behind a URL.
        //
        // exclusiveContent rather than a plain content filter, because the two
        // directions are different guarantees. A filter would stop this
        // repository being consulted for anything else; exclusiveContent also
        // stops anything else being consulted for this module. Without it
        // google() and mavenCentral() are asked for it first -- two 404s on
        // every cold resolve, and whoever registered the coordinate on Maven
        // Central would win over the pin. The SHA-256 check downstream would
        // turn that into a build failure rather than a bad APK, but it should
        // not get that far.
        exclusiveContent {
            forRepository {
                ivy("https://github.com/csukuangfj/onnxruntime-libs/releases/download") {
                    name = "onnxruntime-libs"
                    patternLayout {
                        artifact("v[revision]/[artifact]-android-[revision].[ext]")
                    }
                    // A GitHub release is not a Maven repository: there is no
                    // POM or ivy.xml beside the asset, so the artifact itself
                    // is the metadata.
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.github.csukuangfj", "onnxruntime") }
        }
    }
}

rootProject.name = "VocaPhone"
include(":app")
