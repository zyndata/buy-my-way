import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.google.services)
}

// versionName and versionCode come from git (STATE.md decision 28): a tag vX.Y.Z is
// X*1_000_000 + Y*10_000 + Z*100, and each commit after it adds one, up to 99.
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val describe = git("describe", "--tags", "--match", "v[0-9]*", "--dirty")
val tagged = describe?.let {
    Regex("""^v(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g\p{XDigit}+)?(?:-dirty)?$""").matchEntire(it)
}
val appVersionName = tagged?.value?.removePrefix("v") ?: "0.0.0-dev"
val appVersionCode = if (tagged != null) {
    val (major, minor, patch, ahead) = tagged.destructured
    major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 +
        (ahead.toIntOrNull() ?: 0).coerceAtMost(99)
} else {
    (git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1).coerceIn(1, 99)
}

/**
 * A release-signing value, from `~/.gradle/gradle.properties` locally or from the environment
 * in CI, where GitHub Secrets fill it (PLAN.md Phase 10, task 1). Never from the repository.
 */
fun secret(property: String, environment: String): String? =
    providers.gradleProperty(property).orNull ?: providers.environmentVariable(environment).orNull

// Absent on any machine that is not cutting a release — a fork, a fresh clone, CI's debug
// jobs. Then `assembleRelease` simply produces an unsigned APK (STATE.md decision 111).
val releaseKeystore = secret("buymyway.keystore", "BUYMYWAY_KEYSTORE")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "dev.gorny.buymyway"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.gorny.buymyway"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Apps Script push endpoint (PLAN.md Phase 9, task 2). It is a public id, not a
        // secret (CLAUDE.md), and an empty value simply switches pushing off — which is what a
        // fork with no script of its own, and every test build, gets.
        buildConfigField(
            "String",
            "PUSH_URL",
            "\"${providers.gradleProperty("buymyway.pushUrl").getOrElse("")}\"",
        )
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = secret("buymyway.keystorePassword", "BUYMYWAY_KEYSTORE_PASSWORD")
                keyAlias = secret("buymyway.keyAlias", "BUYMYWAY_KEY_ALIAS")
                keyPassword = secret("buymyway.keyPassword", "BUYMYWAY_KEY_PASSWORD")
                // v1 as well as v2/v3: the app installs on API 26, where v2 alone is enough,
                // but a sideloaded APK is also inspected by tooling that still reads v1.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null without a keystore: an unsigned APK, which will not install, rather than a
            // build that fails on a file nobody outside this household has (decision 111).
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The UI is Polish only, so the ~80 locales AndroidX and Play services ship are dead
    // weight in resources.arsc; the default (Polish) resources are kept whatever this says.
    androidResources {
        localeFilters += "pl"
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME for Ustawienia.
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Version freshness is dependabot's job, not a reason for a red build
        // (STATE.md decision 29).
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

// The exported schema is committed (app/schemas) and is what the migration tests start from.
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    // On the classpath from Phase 1; first called in Phase 9.
    implementation(libs.firebase.messaging)

    // Sign in with Google (STATE.md decisions 53 and 57).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.work.runtime)
    // A photo's EXIF orientation (STATE.md decision 73).
    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
