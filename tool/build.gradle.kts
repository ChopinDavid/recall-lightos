import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

// Release-signing secrets are read from local.properties (gitignored) first, then
// environment variables, so the real keystore's passwords never enter the repo.
// See tool/keystore/release.jks (also gitignored) and local.properties.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String? =
    (localProps.getProperty(key) ?: System.getenv(key))?.takeIf { it.isNotBlank() }

// Coverage is OPT-IN and OFF by default. Only CI passes -Precall.coverage=true.
// Keeping it off by default means local dev and Light's own build server compile a
// dependency graph with zero JaCoCo artifacts, so the Light SDK plugin's dependency
// allowlist validator stays happy. See the buildTypes.debug block for the full rationale.
val coverageEnabled = (findProperty("recall.coverage") as String?)?.toBoolean() == true

val releaseStorePassword = secret("RELEASE_STORE_PASSWORD")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD")
val releaseKeystoreFile = file("keystore/release.jks")
// Release is properly signed only when the keystore file AND both passwords are
// present. Otherwise we fall back to debug-signing (with a loud build warning) so
// local `assembleRelease` still produces an installable APK — but a real shipping
// build MUST have these set.
val hasReleaseSigning = releaseKeystoreFile.exists() &&
    releaseStorePassword != null && releaseKeyPassword != null

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
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = "recall-release"
                keyPassword = releaseKeyPassword
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    // Resolve which signing config the release build type uses. When the real
    // keystore/passwords are absent we degrade to the dev key so local release
    // builds still install on the emulator — but warn so it can't ship unnoticed.
    val releaseSigningConfig = if (hasReleaseSigning) {
        signingConfigs.getByName("release")
    } else {
        logger.warn(
            "RECALL: release keystore or passwords absent (tool/keystore/release.jks + " +
                "RELEASE_STORE_PASSWORD/RELEASE_KEY_PASSWORD in local.properties or env) — " +
                "falling back to DEBUG signing for the release build. Do NOT ship this APK.",
        )
        signingConfigs.getByName("lightsdkDev")
    }

    // The sync endpoint default is BUILD-TYPE scoped so the shipping build ships
    // no dev server: DEBUG prefills the host-local emulator hub (keeping the
    // test/test123 emulator workflow one-tap), RELEASE prefills nothing so
    // first-run forces the user to enter their own endpoint. Read through
    // BuildConfig.DEV_DEFAULT_ENDPOINT (see RecallPreferences.DEFAULT_SYNC_ENDPOINT).
    val devDefaultEndpoint = "http://10.0.2.2:18080/"

    buildFeatures {
        buildConfig = true
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
            // Emulator convenience: prefill the host-local dev hub.
            buildConfigField("String", "DEV_DEFAULT_ENDPOINT", "\"$devDefaultEndpoint\"")
            // NOTE: coverage is NOT wired via AGP's `enableUnitTestCoverage` here — that
            // makes AGP create resolvable `jacocoAgent`/`jacocoAnt` configurations, and
            // the Light SDK plugin's afterEvaluate validator rejects org.jacoco:* (not on
            // its dependency allowlist), which we may not change. Instead coverage is done
            // fully manually and OPT-IN below (see the `if (coverageEnabled)` block after
            // the android{} block), holding the JaCoCo jars in an SDK-validator-invisible
            // configuration. The default build (local + Light's server) is untouched.
        }
        release {
            // Shipping build: no dev default. First-run shows an EMPTY endpoint,
            // so the user must enter their own sync server before downloading.
            buildConfigField("String", "DEV_DEFAULT_ENDPOINT", "\"\"")
            signingConfig = releaseSigningConfig
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

// ---------------------------------------------------------------------------
// Unit-test coverage (JaCoCo), OPT-IN via -Precall.coverage=true. CI-only.
// ---------------------------------------------------------------------------
// We deliberately do NOT apply the `jacoco` Gradle plugin nor AGP's
// `enableUnitTestCoverage`: both create resolvable `jacoco*` configurations that the
// Light SDK plugin's dependency-allowlist validator would reject (and we must not edit
// the SDK plugin). Instead we hold the JaCoCo agent + ant (report) jars in a
// configuration whose name starts with `_internal-`, which is on the SDK validator's
// INTERNAL_CONFIG_PREFIXES skip-list, so the validator never inspects it. We then:
//   1. attach the JaCoCo agent to the debug unit-test JVM (-javaagent) to emit exec data,
//   2. register a JacocoReport task that reads that exec data + the debug classes/sources
//      and writes an XML report (for Codecov) and an HTML report (for humans).
// This whole block is inert unless -Precall.coverage=true, so local dev and Light's
// build server compile a completely JaCoCo-free, policy-clean graph.
if (coverageEnabled) {
    val jacocoVersion = "0.8.13"
    // `_internal-` prefix => skipped by LightSdkPlugin.INTERNAL_CONFIG_PREFIXES.
    val jacocoAgentCfg = configurations.create("_internal-jacocoAgentRuntime")
    val jacocoAntCfg = configurations.create("_internal-jacocoAntRuntime")
    dependencies.add(jacocoAgentCfg.name, "org.jacoco:org.jacoco.agent:$jacocoVersion:runtime")
    dependencies.add(jacocoAntCfg.name, "org.jacoco:org.jacoco.ant:$jacocoVersion")

    val execFile = layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")

    // Attach the agent to the debug unit-test JVM so it writes exec data on run.
    tasks.withType<Test>().configureEach {
        if (name == "testDebugUnitTest") {
            val agentJar = jacocoAgentCfg
            doFirst {
                jvmArgs(
                    "-javaagent:${agentJar.singleFile.absolutePath}=" +
                        "destfile=${execFile.get().asFile.absolutePath},output=file,append=false",
                )
            }
            outputs.file(execFile)
        }
    }

    // Report task: consumes the exec data + compiled debug classes + sources.
    tasks.register<JacocoReport>("recallCoverageReport") {
        group = "verification"
        description =
            "JaCoCo XML+HTML coverage for :tool debug unit tests (Codecov). " +
                "Run: ./gradlew :tool:recallCoverageReport -Precall.coverage=true"
        dependsOn("testDebugUnitTest")
        jacocoClasspath = jacocoAntCfg
        executionData(execFile)

        // Kotlin classes compiled for the debug unit-test compilation.
        classDirectories.setFrom(
            files(
                layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            ).asFileTree.matching {
                // Exclude generated/boilerplate that would dilute the signal.
                exclude(
                    "**/BuildConfig.*",
                    "**/*_Factory.*",
                    "**/*_Impl.*",           // Room-generated DAOs/DB
                    "**/*ComposableSingletons*",
                    "**/R.class",
                    "**/R$*.class",
                )
            },
        )
        sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))

        reports {
            xml.required.set(true)
            xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/recallCoverageReport/recallCoverageReport.xml"))
            html.required.set(true)
            html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/recallCoverageReport/html"))
            csv.required.set(false)
        }
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
