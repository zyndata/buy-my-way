package dev.gorny.buymyway.spike

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialised from BuildConfig instead of google-services.json so the spike needs no
        // Gradle plugin and no file in the repository.
        if (FirebaseApp.getApps(this).isEmpty() && BuildConfig.FIREBASE_APP_ID.isNotEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Spike", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL = "spike"
    }
}
