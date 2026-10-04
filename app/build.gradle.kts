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
        versionCode = 10
        versionName = "0.4.1"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            // Local sideload build: stable Android debug signing key also allows updates.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation(project(":ffmpeg-audio"))
    testImplementation("junit:junit:4.13.2")
}
