import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val unsignedRelease = providers.gradleProperty("astraUnsigned").orNull == "true"
val localSigningFile = providers.gradleProperty("astraSigningProperties").orNull
    ?.let { rootProject.file(it) }
    ?: File(System.getProperty("user.home"), ".android/astra-sec/signing.properties")
val environmentStore = providers.environmentVariable("ASTRA_SIGNING_STORE_FILE").orNull
    ?.takeIf { it.isNotBlank() }
val localSigning = Properties().apply {
    if (!unsignedRelease && environmentStore == null && localSigningFile.isFile) {
        localSigningFile.inputStream().use { load(it) }
    }
}
fun signingValue(name: String): String? = (if (environmentStore != null) {
    providers.environmentVariable(name).orNull
} else {
    localSigning.getProperty(name)
})?.takeIf { it.isNotBlank() }
val signingStore = if (unsignedRelease) null else signingValue("ASTRA_SIGNING_STORE_FILE")
val signingKeyFile = signingStore?.let {
    val path = File(it)
    when {
        path.isAbsolute -> path
        environmentStore != null -> rootProject.file(it)
        else -> localSigningFile.parentFile.resolve(it)
    }
}

android {
    namespace = "org.astrasec.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.astrasec.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 11
        versionName = "0.4.2"
    }
    buildFeatures { buildConfig = true }
    val configuredSigning = if (signingKeyFile != null) {
        signingConfigs.create("astraRelease") {
            storeFile = signingKeyFile
            fun requiredValue(name: String) = signingValue(name)
                ?: error("$name is required for release signing")
            storePassword = requiredValue("ASTRA_SIGNING_STORE_PASSWORD")
            keyAlias = requiredValue("ASTRA_SIGNING_KEY_ALIAS")
            keyPassword = requiredValue("ASTRA_SIGNING_KEY_PASSWORD")
        }
    } else null
    buildTypes {
        release {
            signingConfig = configuredSigning
            isMinifyEnabled = false
            // Preserve the supplied PNG artwork without AAPT PNG re-encoding.
            isCrunchPngs = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; checkReleaseBuilds = true }
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        if (!unsignedRelease) {
            check(signingKeyFile != null) {
                "Release signing is not configured. Use ~/.android/astra-sec/signing.properties, " +
                    "set ASTRA_SIGNING_* environment variables, or pass -PastraUnsigned=true."
            }
            check(signingKeyFile.isFile) { "The configured release keystore does not exist." }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(requireReleaseSigning)
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation(project(":ffmpeg-audio"))
    testImplementation("junit:junit:4.13.2")
}
