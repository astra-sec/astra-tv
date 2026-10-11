plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    val unsignedRelease = providers.gradleProperty("astraUnsigned").orNull == "true"
    val signingStore = providers.environmentVariable("ASTRA_SIGNING_STORE_FILE").orNull
    val configuredSigning = if (!unsignedRelease && !signingStore.isNullOrBlank()) {
        signingConfigs.create("configuredRelease") {
            storeFile = file(signingStore)
            fun signingValue(name: String) = providers.environmentVariable(name).orNull
                ?.takeIf { it.isNotBlank() } ?: error("$name is required with ASTRA_SIGNING_STORE_FILE")
            storePassword = signingValue("ASTRA_SIGNING_STORE_PASSWORD")
            keyAlias = signingValue("ASTRA_SIGNING_KEY_ALIAS")
            keyPassword = signingValue("ASTRA_SIGNING_KEY_PASSWORD")
        }
    } else null
    buildTypes {
        release {
            // Keep local signing unchanged; CI either uses an explicit key or builds unsigned.
            signingConfig = when {
                unsignedRelease -> null
                configuredSigning != null -> configuredSigning
                else -> signingConfigs.getByName("debug")
            }
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
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation(project(":ffmpeg-audio"))
    testImplementation("junit:junit:4.13.2")
}
