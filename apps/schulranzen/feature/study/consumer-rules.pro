# The Anthropic SDK maps JSON by reflection through Jackson; shrinking it breaks the explanation helper.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# Only needed by the SDK for file uploads, which the app does not use; left out on purpose (see build.gradle.kts).
-dontwarn org.apache.hc.**
-dontwarn com.fasterxml.jackson.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn java.beans.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Only the SDK's JSON schema generator touches these; it is never called here (no structured outputs).
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
