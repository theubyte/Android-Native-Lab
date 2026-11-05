# Keep rules for the release build. Libraries ship most of what they need; the
# rules here cover what they reach through JNI or reflection and what R8 cannot see.
# Without a device these can only be checked statically (docs/audit/release.md),
# so the native libraries are kept generously: a few MB are cheaper than a crash
# on a user's tablet in code that only runs with a stylus or a scanned book.

# Crash reports must be readable: line numbers stay, the mapping file decodes the rest.
# Keep app/build/outputs/mapping/release/mapping.txt per release (it is also inside the bundle for Play).
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,EnclosingMethod,Signature,Exceptions
-renamesourcefileattribute SourceFile

# --- Room: the generated _Impl class is found by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- WorkManager: the worker class name is stored in its database and survives updates.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Startup: initializers are named in the manifest.
-keep class * implements androidx.startup.Initializer { public <init>(); }

# --- ML Kit: native code calls back into these classes, their protos use reflection.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
# The document scanner loads its module from Play services by a descriptor class name.
-keep class com.google.android.gms.dynamite.descriptors.** { *; }
-keep class com.google.android.gms.common.moduleinstall.** { *; }
-keep class com.google.android.datatransport.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**

# --- Jetpack Ink and graphics: JNI in both directions.
-keep class androidx.ink.** { *; }
-keep class androidx.graphics.** { *; }
-keep class androidx.input.motionprediction.** { *; }

# --- pdfbox-android: filters, fonts and encodings are found by name; JP2 decoding is optional.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.gemalto.jp2.**

# --- Bouncy Castle: encrypted PDFs reach ciphers through provider mappings by string.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# --- kotlinx.serialization: the rules ship with the library. Companion and serializer()
# of our own classes stay, in case a generic lookup reaches them.
-keepclassmembers class de.millione.schulranzen.** {
    *** Companion;
}
-keepclasseswithmembers class de.millione.schulranzen.** {
    kotlinx.serialization.KSerializer serializer(...);
}
