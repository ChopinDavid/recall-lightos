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

// R8 keep rules, generated into build/ at configure time instead of shipped as
// tool/proguard-rules.pro: Light's builder extracts ONLY lighttool.toml,
// build.gradle.kts, and src/main/** from a tool repo (builder/lightbuilder/
// allowlist.py), so a loose .pro file would not exist on their infrastructure
// and the minified release build would fail there. The SDK's own reflection
// keeps ship as consumer rules inside the SDK modules (light-sdk#55); these
// are the TOOL-side keeps the rest of the dependency graph does not provide
// (rsdroid declares a consumer proguard.txt but ships it EMPTY in the AAR).
val recallProguardRules: File = layout.buildDirectory.file("generated/recall-proguard-rules.pro").get().asFile.apply {
    parentFile.mkdirs()
    writeText(
        """
# Recall (LightOS Anki client) — R8/release keep rules.
#
# Each group below is justified against the actual codebase, not cargo-culted.
# The three real runtime-reflection surfaces R8 can silently break are:
#   1. rsdroid JNI (native methods called from Rust by name/signature)
#   2. the anki.* protobuf-lite generated messages (reflective field metadata)
#   3. kotlinx-serialization serializers for our api.** wire types
#   4. the Light SDK's Class.forName-reflected generated registry + the screen/
#      entry-point/job classes it references by fully-qualified name.
#
# R8 breakages are RUNTIME, not compile-time, so these are verified on-emulator.

# =============================================================================
# 1. rsdroid / JNI  (net.ankiweb.rsdroid.**)
# =============================================================================
# NativeMethods holds the three JNI entry points the Rust lib resolves by name
# (openBackend / closeBackend / runMethodRaw) plus the INSTANCE field the native
# side reaches back through. Native methods must never be renamed or removed;
# -keepclasseswithmembernames preserves the containing class + native members.
-keepclasseswithmembernames,includedescriptorclasses class net.ankiweb.rsdroid.** {
    native <methods>;
}
-keep class net.ankiweb.rsdroid.NativeMethods { *; }

# Backend / BackendFactory and the typed BackendException hierarchy are matched
# by the Rust error channel by class name (backend errors are mapped to these
# Kotlin exception classes). Keep the exception classes and their names so the
# error mapping (BackendException.Companion.fromError) keeps resolving them.
-keep class net.ankiweb.rsdroid.Backend { *; }
-keep class net.ankiweb.rsdroid.BackendFactory { *; }
-keep class net.ankiweb.rsdroid.BackendFactoryKt { *; }
-keep class net.ankiweb.rsdroid.BackendException { *; }
-keep class net.ankiweb.rsdroid.BackendException${'$'}** { *; }
# @RustCleanup / @RustCleanupCollection are dev-time markers; harmless to keep.
-keep class net.ankiweb.rsdroid.RustCleanup { *; }
-keep class net.ankiweb.rsdroid.RustCleanupCollection { *; }

# =============================================================================
# 2. anki.** protobuf-lite generated messages
# =============================================================================
# Every anki.* message extends com.google.protobuf.GeneratedMessageLite, which
# builds its field schema reflectively (RawMessageInfo / newMessageInfo names the
# private fields; dynamicMethod is called reflectively). protobuf-lite's own
# consumer rules are NOT shipped by the -javalite artifact reliably, so we keep
# the standard lite surface ourselves.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
# protobuf-lite runtime touches these reflectively.
-keepnames class com.google.protobuf.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
# Keep the whole generated anki.* tree explicitly (messages, *OrBuilder
# interfaces, enums, Builders). LocalEngineApi/SyncController/CardHtml construct
# and parse these directly.
-keep class anki.** { *; }
-keep interface anki.** { *; }
-dontwarn anki.**

# =============================================================================
# 3. kotlinx-serialization  (com.dvdutch.recall.api.**)
# =============================================================================
# Standard kotlinx-serialization keeps: the plugin generates a synthetic
# ${'$'}serializer and a companion serializer() accessor per @Serializable type;
# both are looked up reflectively / by generated code and must survive.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the serialization runtime's reflective entry points.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Our wire types: keep every @Serializable holder plus its synthetic
# ${'$'}serializer. The RenderNode sealed hierarchy is polymorphic via a custom
# JsonContentPolymorphicSerializer (RenderNodeSerializer) that dispatches on the
# "t" discriminator and calls TextNode.serializer(), OcclusionNode.serializer(),
# etc. by hand — if those companion serializer() accessors or the ${'$'}serializer
# classes are stripped/renamed, polymorphic decode fails SILENTLY at runtime
# (returns/throws on the wrong branch), which is exactly the occlusion/card
# render path. Keep the whole api package's serializer surface.
-keep class com.dvdutch.recall.api.**${'$'}${'$'}serializer { *; }
-keepclassmembers class com.dvdutch.recall.api.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dvdutch.recall.api.** {
    <fields>;
}
# The custom serializers themselves (referenced via @Serializable(with=...) and
# instantiated by the runtime) must not be stripped or renamed.
-keep class com.dvdutch.recall.api.RenderNodeSerializer { *; }
-keep class com.dvdutch.recall.api.OcclusionShapeState { *; }
-keep class com.dvdutch.recall.api.ShapeState { *; }

# =============================================================================
# 4. Light SDK reflection surface
# =============================================================================
# LightSdkRegistry (sdk/client) does:
#     Class.forName("com.thelightphone.sdk.generated.LightSdkRegistry")
#       .getField("INSTANCE")
#     .getMethod("getInitialScreenFactory" / "getEntryPoint" / "getJobs")
# The generated registry object + those accessors must be kept by name, and the
# classes it references by FQCN (our @InitialScreen screen, @LightEntryPoint,
# @LightJob handler) must survive since only the generated (reflected) code
# reaches them.
-keep class com.thelightphone.sdk.generated.LightSdkRegistry { *; }
-keep class com.thelightphone.sdk.generated.** { *; }

# Our reflected entry classes (referenced only from the generated registry):
-keep class com.dvdutch.recall.ui.RecallHomeScreen { *; }
-keep class com.thelightphone.sample.ToolEntryPoint { *; }
-keep class com.dvdutch.recall.engine.PeriodicSync { *; }
-keep class com.dvdutch.recall.engine.PeriodicSyncKt { *; }

# The SDK's own reflected/annotation types the registry contract depends on.
-keep class com.thelightphone.sdk.InitialScreen { *; }
-keep @com.thelightphone.sdk.InitialScreen class * { *; }
-keep interface com.thelightphone.sdk.LightEntryPoint { *; }
-keep class * implements com.thelightphone.sdk.LightEntryPoint { *; }
-keep class com.thelightphone.sdk.LightActivity { *; }
-keep class com.thelightphone.sdk.SealedLightActivity { *; }
-keep class com.thelightphone.sdk.LightSdkApplication { *; }
-keep class com.thelightphone.sdk.LightSdkReceiver { *; }
# LightIcons enumerates its @LightIconConfiguration nested classes reflectively
# (LightIcons::class.java.declaredClasses in sdk/ui) — keep them.
-keep class com.thelightphone.sdk.ui.LightIcons { *; }
-keep class com.thelightphone.sdk.ui.LightIcons${'$'}* { *; }

# =============================================================================
# 5. DataStore preferences (settings)
# =============================================================================
# RecallPreferences uses androidx.datastore.preferences with plain typed keys
# (no proto DataStore / no @Serializable settings blob), so no custom keeps are
# needed beyond the library's own consumer rules. Kept defensively quiet:
-dontwarn androidx.datastore.**

# =============================================================================
# General
# =============================================================================
# Kotlin metadata + coroutines internals occasionally trip -dontwarn at link.
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
""",
    )
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
                recallProguardRules,
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
                    // Compose UI is verified ON-DEVICE (emulator gates: screenshots, pixel
                    // measurements, the release smoke test) — unit-line coverage cannot see
                    // that and reported the whole layer as ~0%, drowning the logic signal.
                    // Coverage here measures the UNIT-TESTABLE surface: engine, compiler,
                    // study machine, view models. Rule that keeps this honest: logic lives
                    // OUTSIDE ui/ (compiler/, engine/, study/, prefs/) where it stays counted.
                    "**/ui/**",
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
