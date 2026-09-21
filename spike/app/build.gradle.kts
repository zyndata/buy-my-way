import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Public ids, read from the git-ignored spike.properties (see spike.properties.example).
val spikeProps = Properties().apply {
    val f = rootProject.file("spike.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun spikeProp(name: String) = "\"" + (spikeProps.getProperty(name) ?: "") + "\""

android {
    namespace = "dev.gorny.buymyway.spike"
    compileSdk = 36

    defaultConfig {
        // The real app id, so the spike uses the same Firebase app and Android OAuth clients.
        applicationId = "dev.gorny.buymyway"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0-spike"
        buildConfigField("String", "FIREBASE_API_KEY", spikeProp("firebaseApiKey"))
        buildConfigField("String", "FIREBASE_APP_ID", spikeProp("firebaseAppId"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", spikeProp("firebaseProjectId"))
        buildConfigField("String", "FIREBASE_DATABASE_URL", spikeProp("firebaseDatabaseUrl"))
        buildConfigField("String", "FIREBASE_SENDER_ID", spikeProp("firebaseSenderId"))
        buildConfigField("String", "WEB_CLIENT_ID", spikeProp("webClientId"))
        buildConfigField("String", "PUSH_ENDPOINT", spikeProp("pushEndpoint"))
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.4.0")
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
