plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "de.millione.schulranzen.backup"
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
    // Automatic backup (exact stable versions, own coordinates per AGENTS.md).
    implementation("androidx.work:work-runtime:2.11.0")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
