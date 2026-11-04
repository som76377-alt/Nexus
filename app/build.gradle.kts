plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nexus.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexus.music"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty(
        "nexus.liveExtractorTests",
        providers.gradleProperty("nexus.liveExtractorTests").orNull ?: "false"
    )
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
