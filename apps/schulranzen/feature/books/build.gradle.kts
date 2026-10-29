plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "de.millione.schulranzen.books"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(project(":ink"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.motion.prediction)
    // Embedded outline and text layer for the table of contents (as in V15).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device text recognition for scanned books; the Latin model ships with the app (no download).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
