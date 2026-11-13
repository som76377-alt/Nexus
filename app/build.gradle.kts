plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val nexusVersionName = providers.gradleProperty("nexusVersionName").orElse("0.0.0-dev").get()
val configuredVersionCode = providers.gradleProperty("nexusVersionCode").orNull
val nexusVersionCode = configuredVersionCode?.toIntOrNull()
    ?: if (configuredVersionCode == null) 1 else throw GradleException("nexusVersionCode must be a valid integer.")
if (nexusVersionCode !in 1..2_100_000_000) {
    throw GradleException("nexusVersionCode must be between 1 and 2100000000.")
}
val updateRepositoryOwner = providers.gradleProperty("nexusUpdateOwner").orElse("som76377-alt").get()
val updateRepositoryName = providers.gradleProperty("nexusUpdateRepository").orElse("Nexus").get()
val pipedApiEndpoints = providers.gradleProperty("nexusPipedApiEndpoints").orElse("").get()
val invidiousApiEndpoints = providers.gradleProperty("nexusInvidiousApiEndpoints").orElse("").get()
val streamProviderOrder = providers.gradleProperty("nexusStreamProviderOrder").orElse("newpipe,piped,invidious").get()

val nexusKeystorePath = providers.environmentVariable("NEXUS_KEYSTORE_PATH").orNull
val nexusKeystorePassword = providers.environmentVariable("NEXUS_KEYSTORE_PASSWORD").orNull
val nexusKeyAlias = providers.environmentVariable("NEXUS_KEY_ALIAS").orNull
val nexusKeyPassword = providers.environmentVariable("NEXUS_KEY_PASSWORD").orNull
val hasNexusSigningKey = listOf(nexusKeystorePath, nexusKeystorePassword, nexusKeyAlias, nexusKeyPassword).all { !it.isNullOrBlank() }
val hasExplicitReleaseVersion =
    providers.gradleProperty("nexusVersionName").isPresent &&
        providers.gradleProperty("nexusVersionCode").isPresent
val releaseBuildRequested = gradle.startParameter.taskNames.any { task ->
    val taskName = task.substringAfterLast(':').lowercase()
    taskName in setOf("build", "assemble", "bundle", "package") ||
        ((taskName.startsWith("assemble") ||
            taskName.startsWith("bundle") ||
            taskName.startsWith("package")) && taskName.contains("release"))
}
if (releaseBuildRequested && !hasNexusSigningKey) {
    throw GradleException("Release APKs must be signed with the permanent Nexus keystore. Configure all four NEXUS_KEYSTORE/NEXUS_KEY_* environment variables.")
}
if (releaseBuildRequested && !hasExplicitReleaseVersion) {
    throw GradleException("Release APKs must receive explicit nexusVersionName and nexusVersionCode values derived from a vMAJOR.MINOR.PATCH tag.")
}
if (releaseBuildRequested) {
    val parts = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$").matchEntire(nexusVersionName)
        ?: throw GradleException("Release versionName must use canonical MAJOR.MINOR.PATCH format.")
    val major = parts.groupValues[1].toLong()
    val minor = parts.groupValues[2].toLong()
    val patch = parts.groupValues[3].toLong()
    if (major > 2_100L || minor > 999L || patch > 999L) {
        throw GradleException("Release version components exceed the supported range.")
    }
    val expectedVersionCode = major * 1_000_000L + minor * 1_000L + patch
    if (expectedVersionCode != nexusVersionCode.toLong()) {
        throw GradleException("Release versionCode does not match its semantic versionName.")
    }
}

android {
    namespace = "com.nexus.music"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nexus.music"
        minSdk = 24
        targetSdk = 36
        versionCode = nexusVersionCode
        versionName = nexusVersionName
        buildConfigField("String", "UPDATE_REPOSITORY_OWNER", "\"$updateRepositoryOwner\"")
        buildConfigField("String", "UPDATE_REPOSITORY_NAME", "\"$updateRepositoryName\"")
        buildConfigField("String", "SUPABASE_URL", "\"https://fdptukvfcpkpbutgoolj.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_jYtp6H2E2SKakHkb-Z1u4g_-ZlAai42\"")
        buildConfigField("String", "PIPED_API_ENDPOINTS", "\"${pipedApiEndpoints.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "INVIDIOUS_API_ENDPOINTS", "\"${invidiousApiEndpoints.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "STREAM_PROVIDER_ORDER", "\"${streamProviderOrder.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("nexus") {
            if (hasNexusSigningKey) {
                storeFile = file(nexusKeystorePath!!)
                storePassword = nexusKeystorePassword
                keyAlias = nexusKeyAlias
                keyPassword = nexusKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasNexusSigningKey) signingConfig = signingConfigs.getByName("nexus")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("nexus.liveExtractorTests", providers.gradleProperty("nexus.liveExtractorTests").orNull ?: "false")
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation("androidx.media3:media3-database:1.11.1")
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation("org.json:json:20240303")
}
