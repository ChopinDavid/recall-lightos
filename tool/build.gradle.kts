plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String

        // The anki backend ships native libs for every ABI; the tool only
        // targets arm64-v8a devices, so trim the rest out of the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }

    testOptions {
        unitTests.all {
            // Forward the render-compiler parity corpus path to the test JVM.
            // The corpus is a personal deck kept out of git; ParityTest skips
            // cleanly when this property is absent (e.g. on CI).
            //   ./gradlew :tool:testDebugUnitTest -Precall.parityCorpus=/abs/path/corpus.jsonl
            (findProperty("recall.parityCorpus") as String?)?.let { corpus ->
                it.systemProperty("recall.parityCorpus", corpus)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))

    // On-device Anki engine (rslib via JNI). The SDK's unifiedpush→tink chain
    // already supplies full protobuf-java, so exclude the backend's bundled
    // protobuf-javalite to avoid a duplicate-class conflict.
    implementation("io.github.david-allison:anki-android-backend:0.1.64-anki25.09.2") {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }

    testImplementation(libs.kotlin.test)
    // No catalog alias for the mock engine; pin to the catalog ktor version.
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    // Desktop natives + loader for JVM-side engine tests (spike, Task 0).
    testImplementation("io.github.david-allison:anki-android-backend-testing:0.1.64-anki25.09.2")
    // Also depend on the backend in test scope WITHOUT the javalite exclude, so the
    // generated protobuf messages' base classes (GeneratedMessageLite, resolved
    // here via the backend's own protobuf-javalite) are on the test *compile*
    // classpath. The app's `implementation` above still excludes javalite for the
    // APK; this test-only edition affects unit tests only.
    //
    // KNOWN SKEW (test scope only): this re-declaration puts BOTH protobuf-javalite
    // 4.33.4 (from the backend, needed above) AND full protobuf-java 4.33.0 (from the
    // SDK's unifiedpush→tink chain) on the test classpath at once. Those two artifacts
    // share ~513 fully-qualified class names (e.g. com.google.protobuf.*) at DIFFERENT
    // versions, so the JVM resolves each duplicated class by classpath order rather
    // than by version — a latent hazard. It is tolerated here because it is confined
    // to the test classpath: the shipped APK is unaffected (it excludes javalite and
    // was verified to carry only the single protobuf-java edition). The durable fix is
    // upstream — either allowlist a single protobuf artifact so this re-declaration can
    // collapse to one `testImplementation`, or have the backend's `-testing` chain grow
    // a matching protobuf so the base classes come in without pulling javalite here.
    testImplementation("io.github.david-allison:anki-android-backend:0.1.64-anki25.09.2")
    ksp(libs.androidx.room.compiler)
}
