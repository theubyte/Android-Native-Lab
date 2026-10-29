plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "de.millione.schulranzen.ink"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    api(libs.ink.authoring)
    api(libs.ink.brush)
    api(libs.ink.geometry)
    api(libs.ink.rendering)
    api(libs.ink.strokes)
    api(libs.ink.storage)
    implementation(libs.graphics.core)
    implementation(libs.motion.prediction)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
