plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
android {
    namespace = "de.millione.schulranzen.study"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(project(":design"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    // Reminders survive restarts of the app and the device.
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    // The explanation helper; only touched once the learner has entered a key.
    // Apache HttpComponents only serves the SDK's file uploads, unused here; it also clashes in packaging.
    implementation("com.anthropic:anthropic-java:2.34.0") {
        exclude(group = "org.apache.httpcomponents.client5")
        exclude(group = "org.apache.httpcomponents.core5")
    }
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
