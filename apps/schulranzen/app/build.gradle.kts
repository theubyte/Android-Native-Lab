import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// One place for the version. Code = major*1 000 000 + minor*10 000 + patch*100 + build,
// so it always rises with the name and leaves two spare digits for a rebuild of the
// same version. 0.4.2 was the last hand-numbered release (versionCode 7).
val appVersion = listOf(0, 5, 2)
val appBuild = 0

android {
    namespace = "de.millione.schulranzen.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.millione.schulranzen.neu"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion[0] * 1_000_000 + appVersion[1] * 10_000 + appVersion[2] * 100 + appBuild
        versionName = appVersion.joinToString(".")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = key
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // The app is German; libraries otherwise bring their strings in about 80 languages.
    androidResources { localeFilters += "de" }
    // The app is installed directly, so the APK itself has to be small: a release
    // assemble writes one APK per ABI (the updater picks the right one) next to a
    // universal one. All carry the same versionCode, which is fine here: a device
    // only ever installs the APK of its own ABI. Debug stays a single APK, so the
    // emulator workflow does not change.
    val abiApks = gradle.startParameter.taskNames.any { it.endsWith("assembleRelease") }
    splits {
        abi {
            isEnable = abiApks
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        // 32-bit x86: practically no Android 8+ device, 22 MB in a universal APK.
        // x86_64 stays for emulators and Chromebooks.
        jniLibs {
            excludes += "lib/x86/**"
            // Compressed: the APK (and every update download) is 11 MB smaller for arm64, at the
            // price of about 8 MB more on the device, where Android unpacks the libraries.
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                // In the "Lizenzen" screen instead, once rather than once per library.
                "META-INF/androidx/**/LICENSE.txt",
                // Post-quantum tables of Bouncy Castle (4 MB). pdfbox-android only needs its classic ciphers.
                "org/bouncycastle/pqc/**",
            )
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Lints the feature modules through the app, so CI needs one task only.
        checkDependencies = true
        baseline = file("lint-baseline.xml")
    }
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
    // Installs the crash capture before anything else runs (about/StartupInitializer).
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

/**
 * Writes what the "Über" screen shows, from the real build: the libraries with
 * the licences their POMs declare and Google's bundled third-party notices.
 * Generated, so the list cannot drift from the dependencies.
 */
abstract class LegalAssetsTask : DefaultTask() {
    @get:Input abstract val libraries: ListProperty<Map<String, String>>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val aars: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.resolve("legal").apply { deleteRecursively(); mkdirs() }
        out.resolve("libraries.json").writeText(JsonOutput.toJson(libraries.get()))
        // Google's AARs carry the notices of their native parts: name -> {start, length} into a text file.
        val notices = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER)
        for (aar in aars.files.filter { it.extension == "aar" }) ZipFile(aar).use { zip ->
            val index = zip.getEntry("third_party_licenses.json") ?: return@use
            val text = zip.getEntry("third_party_licenses.txt") ?: return@use
            val bytes = zip.getInputStream(text).use { it.readBytes() }
            @Suppress("UNCHECKED_CAST")
            val entries = JsonSlurper().parse(zip.getInputStream(index)) as Map<String, Map<String, Int>>
            for ((name, at) in entries) notices.getOrPut(name) {
                String(bytes, at.getValue("start"), at.getValue("length"), Charsets.UTF_8)
            }
        }
        out.resolve("third_party_notices.txt").writeText(
            notices.entries.joinToString("\n\n") { (name, text) -> "=== $name ===\n${text.trim()}" },
        )
    }
}

/** What a POM declares, walking up to the parent POMs where the licence is usually kept. */
fun pomInfo(group: String, name: String, version: String, depth: Int = 0): Map<String, String> {
    val pom = dependencies.createArtifactResolutionQuery()
        .forModule(group, name, version)
        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
        .execute().resolvedComponents
        .flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
        .filterIsInstance<ResolvedArtifactResult>().firstOrNull()?.file ?: return emptyMap()
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
    fun Element.child(tag: String): String? = childNodes.let { list ->
        (0 until list.length).map { list.item(it) }.filterIsInstance<Element>()
            .firstOrNull { it.tagName == tag }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
    val licenses = root.getElementsByTagName("license").let { list -> (0 until list.length).map { list.item(it) as Element } }
    val own = buildMap {
        root.child("name")?.let { put("title", it) }
        root.child("url")?.let { put("url", it) }
        if (licenses.isNotEmpty()) {
            put("license", licenses.mapNotNull { it.child("name") }.distinct().joinToString(" / "))
            licenses.firstNotNullOfOrNull { it.child("url") }?.let { put("licenseUrl", it) }
        }
    }
    val parent = root.getElementsByTagName("parent").item(0) as? Element
    if (parent == null || depth > 4 || "license" in own) return own
    val up = pomInfo(parent.child("groupId").orEmpty(), parent.child("artifactId").orEmpty(), parent.child("version").orEmpty(), depth + 1)
    return up + own
}

/** The fonts in design/src/main/res/font come from no POM. */
val fontLibraries = listOf(
    mapOf("id" to "font:bricolage", "title" to "Bricolage Grotesque (Schrift)", "version" to "", "url" to "https://github.com/ateliertriay/bricolage", "license" to "SIL Open Font License 1.1", "licenseUrl" to "https://openfontlicense.org"),
    mapOf("id" to "font:inter", "title" to "Inter (Schrift)", "version" to "", "url" to "https://github.com/rsms/inter", "license" to "SIL Open Font License 1.1", "licenseUrl" to "https://openfontlicense.org"),
    mapOf("id" to "font:plexmono", "title" to "IBM Plex Mono (Schrift)", "version" to "", "url" to "https://github.com/IBM/plex", "license" to "SIL Open Font License 1.1", "licenseUrl" to "https://openfontlicense.org"),
)

val legalAssets = tasks.register<LegalAssetsTask>("generateLegalAssets") {
    val runtime = configurations.named("releaseRuntimeClasspath")
    libraries.set(provider {
        val modules = runtime.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .sortedWith(compareBy({ it.group }, { it.module }))
        modules.map { id ->
            val info = pomInfo(id.group, id.module, id.version)
            buildMap {
                put("id", "${id.group}:${id.module}")
                put("title", info["title"] ?: id.module)
                put("version", id.version)
                put("license", info["license"] ?: "Siehe Projektseite")
                info["url"]?.let { put("url", it) }
                info["licenseUrl"]?.let { put("licenseUrl", it) }
            }
        } + fontLibraries
    })
    aars.from(runtime.map { c ->
        c.incoming.artifactView {
            attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
            lenient(true)
        }.files
    })
    outputDir.set(layout.buildDirectory.dir("generated/legalAssets"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssetsTask::outputDir) }
}
