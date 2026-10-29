import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
android {
    namespace = "de.millione.schulranzen.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.millione.schulranzen.neu"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
    }
    // One fixed key, so a new APK installs over the old one and keeps the
    // notebooks. The key stays off GitHub (public repo): signing/ holds the
    // .jks and keystore.properties locally and is git-ignored. Without them
    // (e.g. on CI) the normal debug key is used.
    val keyProps = rootProject.file("signing/keystore.properties")
    val ranzenKey = keyProps.exists()
    if (ranzenKey) signingConfigs {
        create("ranzen") {
            val props = Properties().apply { keyProps.inputStream().use { load(it) } }
            storeFile = rootProject.file("signing/" + props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }
    buildTypes {
        val key = signingConfigs.findByName(if (ranzenKey) "ranzen" else "debug")
        debug { signingConfig = key }
        release {
            isMinifyEnabled = false
            signingConfig = key
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(project(":ink"))
    implementation(project(":feature:rechnen"))
    implementation(project(":feature:books"))
    implementation(project(":feature:study"))
    implementation(project(":feature:backup"))
    implementation(project(":feature:handwriting"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
