# R8 rules for the release build (PLAN.md Phase 10, task 1; STATE.md decision 112).
#
# Room, Firebase, WorkManager, DataStore and the AndroidX libraries ship their own consumer
# rules, so nothing here repeats them. What is below is what R8 cannot see for itself: things
# reached by name at runtime. Each rule says who breaks without it — a wrong keep rule
# compiles perfectly and crashes on a phone (decision 107's lesson), so every one of these was
# checked on the minified APK, not reasoned about.

# --- kotlinx.serialization ---------------------------------------------------------------
# The plugin generates a `Companion.serializer()` / `$$serializer` for every @Serializable
# class and the runtime looks them up by name. Without this the dictionary read from assets
# (products-pl.json), the ops in the outbox, the photo outbox's index and the notification
# tally all fail to decode — that is `Categorizer`, `Op`, `PhotoOutbox` and `PushSignal`.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# The app's own serializable types and their generated serializers.
-keep,includedescriptorclasses class dev.gorny.buymyway.**$$serializer { *; }
-keepclassmembers class dev.gorny.buymyway.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.gorny.buymyway.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Entry points the framework creates from the manifest ---------------------------------
# The manifest names these as strings, and R8 keeps manifest entries already; this makes the
# contract explicit so a rename cannot quietly break the push service or the camera's writer.
-keep class dev.gorny.buymyway.MainActivity { *; }
-keep class dev.gorny.buymyway.BuyMyWayApp { *; }
-keep class dev.gorny.buymyway.data.push.PushService { *; }
-keep class dev.gorny.buymyway.data.push.NotificationDismissed { *; }

# --- WorkManager -------------------------------------------------------------------------
# A worker is instantiated by class name from the job WorkManager persisted before the app was
# updated, so an obfuscated name would strand work enqueued by the previous version.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Noise --------------------------------------------------------------------------------
# Firebase and the Google identity libraries reference optional server-side classes that are
# not on the classpath of an Android app. They are not called; R8 only needs to be told.
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
