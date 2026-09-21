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
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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

    // On the classpath from Phase 1 so FirebaseApp initialises; first called in Phase 4.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.firebase.messaging)

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
}
