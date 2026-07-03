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

    // On-device Anki engine (rslib via JNI). Its generated proto messages
    // (CardAnswer, SchedulingStates, …) that LocalEngineApi constructs/reads extend
    // com.google.protobuf.GeneratedMessageLite, which lives in the backend's
    // transitive protobuf-javalite. That base class must be on the main *compile*
    // classpath, so javalite is left in as a natural transitive of this (allowlisted)
    // dependency — no explicit protobuf coordinate, which the Light SDK allowlist
    // would reject. At *runtime* the SDK's unifiedpush→tink chain already supplies
    // full protobuf-java (which also carries GeneratedMessageLite), and shipping BOTH
    // editions is a hard duplicate-class dex failure — so javalite is excluded from
    // the runtime/packaged classpath only (see the configurations block below),
    // leaving exactly one protobuf edition in the APK.
    implementation("io.github.david-allison:anki-android-backend:0.1.64-anki25.09.2")

    testImplementation(libs.kotlin.test)
    // Desktop natives + loader for JVM-side engine tests (spike, Task 0).
    testImplementation("io.github.david-allison:anki-android-backend-testing:0.1.64-anki25.09.2")
    // Backend in test scope so JVM unit tests can drive it (the -testing artifact
    // above supplies the desktop natives). protobuf-javalite reaches the test compile
    // classpath transitively (as it does for main), giving the generated messages'
    // GeneratedMessageLite base class.
    //
    // KNOWN SKEW (test scope only): the test classpath carries BOTH protobuf-javalite
    // 4.33.4 (from the backend) AND full protobuf-java 4.33.0 (from the SDK's tink
    // chain) at once. Those two artifacts share ~513 fully-qualified class names (e.g.
    // com.google.protobuf.*) at DIFFERENT versions, so the JVM resolves each duplicated
    // class by classpath order rather than version — a latent hazard, but confined to
    // tests. The shipped APK is unaffected: its runtime classpath excludes javalite
    // (see the configurations block below), leaving a single protobuf edition. The
    // durable fix is upstream — a single allowlisted protobuf artifact.
    testImplementation("io.github.david-allison:anki-android-backend:0.1.64-anki25.09.2")
    ksp(libs.androidx.room.compiler)
}

// Keep protobuf-javalite on the *compile* classpath (it carries the proto message
// base class GeneratedMessageLite that the backend's generated messages extend, and
// it is a transitive of the allowlisted anki-android-backend), but strip it from the
// *runtime/packaged* classpath: the SDK's tink chain already ships full protobuf-java,
// and packaging both editions is a duplicate-class dex failure. This runtime-only
// exclude leaves exactly one protobuf edition in the APK while main still compiles.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath", ignoreCase = true)) {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
}
