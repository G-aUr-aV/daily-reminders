import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.gaurav.dailyrhythm"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.gaurav.dailyrhythm"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.7.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("reviewDevelopment") {
            // No fallback to the machine's debug key. Packaging requires explicit credentials.
            val keyPath = providers.environmentVariable("DAILY_RHYTHM_KEYSTORE").orNull
            if (keyPath != null) storeFile = file(keyPath)
            storePassword = providers.environmentVariable("DAILY_RHYTHM_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("DAILY_RHYTHM_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("DAILY_RHYTHM_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        create("hardened") {
            initWith(getByName("release"))
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            // Development identity supplied by the caller for fresh installs or in-place updates.
            signingConfig = signingConfigs.getByName("reviewDevelopment")
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

// Full allowlist/component/backup checks run against the packaged APK, not only source manifests.
val verifyHardenedApk by tasks.registering(Exec::class) {
    dependsOn("assembleHardened")
    commandLine(
        providers.gradleProperty("verifierPython").getOrElse("python3"),
        rootProject.file("tools/verify_apk.py").absolutePath,
        "--apk", layout.buildDirectory.file("outputs/apk/hardened/app-hardened.apk").get().asFile.absolutePath,
        "--sdk", android.sdkDirectory.absolutePath,
        "--output-dir", layout.buildDirectory.dir("reports/offline-verification").get().asFile.absolutePath
    )
}
tasks.named("check") { dependsOn(verifyHardenedApk) }

val writeHardenedDependencyInventory by tasks.registering {
    doLast {
        val artifacts = configurations.getByName("hardenedRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val output = layout.buildDirectory.file("reports/hardened-runtime-artifacts.tsv").get().asFile
        output.parentFile.mkdirs()
        output.writeText("coordinate\tfile\tsha256\n" + artifacts.sortedBy { it.moduleVersion.id.toString() }.joinToString("\n") {
            val digest = MessageDigest.getInstance("SHA-256").digest(it.file.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            "${it.moduleVersion.id}\t${it.file.name}\t$digest"
        } + "\n")
    }
}

ksp { arg("room.schemaLocation", file("schemas").path) }
