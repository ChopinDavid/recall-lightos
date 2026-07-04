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
-keep class net.ankiweb.rsdroid.BackendException$** { *; }
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
# $serializer and a companion serializer() accessor per @Serializable type;
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
# $serializer. The RenderNode sealed hierarchy is polymorphic via a custom
# JsonContentPolymorphicSerializer (RenderNodeSerializer) that dispatches on the
# "t" discriminator and calls TextNode.serializer(), OcclusionNode.serializer(),
# etc. by hand — if those companion serializer() accessors or the $serializer
# classes are stripped/renamed, polymorphic decode fails SILENTLY at runtime
# (returns/throws on the wrong branch), which is exactly the occlusion/card
# render path. Keep the whole api package's serializer surface.
-keep class com.dvdutch.recall.api.**$$serializer { *; }
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
-keep class com.thelightphone.sdk.ui.LightIcons$* { *; }

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
