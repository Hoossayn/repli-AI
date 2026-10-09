package dev.patrickgold.florisboard.repli.diagnostics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.patrickgold.florisboard.BuildConfig

/**
 * Firebase Crashlytics for the keyboard and app processes.
 *
 * Reports carry stack traces, device/OS model and app version only. Typed text, captured
 * chat content, suggestions and account identifiers are never attached: no custom keys,
 * no logs, no user ID. Collection is off in debug builds and whenever the user turns it off
 * under Settings → Repli. Crashlytics needs the default [FirebaseApp]; the account manager
 * keeps using its own named app, so both share the same public Firebase options.
 */
object RepliCrashReporting {
    @Volatile private var crashlytics: FirebaseCrashlytics? = null

    fun initialize(context: Context) {
        if (crashlytics != null) return
        val configured = listOf(
            BuildConfig.REPLI_FIREBASE_API_KEY,
            BuildConfig.REPLI_FIREBASE_APP_ID,
            BuildConfig.REPLI_FIREBASE_PROJECT_ID,
        ).all(String::isNotBlank)
        if (!configured) return
        try {
            val appContext = context.applicationContext
            if (FirebaseApp.getApps(appContext).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
                val options = FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.REPLI_FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.REPLI_FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.REPLI_FIREBASE_PROJECT_ID)
                    .build()
                FirebaseApp.initializeApp(appContext, options)
            }
            crashlytics = FirebaseCrashlytics.getInstance().also { instance ->
                instance.isCrashlyticsCollectionEnabled = collectionAllowed(appContext)
            }
        } catch (_: Exception) {
            // Crash reporting is best effort; the keyboard must start without it.
            crashlytics = null
        }
    }

    /** Applies the user's choice immediately; takes effect for the current process too. */
    fun setEnabled(context: Context, enabled: Boolean) {
        CrashReportingPreferences(context).enabled = enabled
        crashlytics?.isCrashlyticsCollectionEnabled = collectionAllowed(context)
        if (!enabled) crashlytics?.deleteUnsentReports()
    }

    fun isAvailable(): Boolean = crashlytics != null

    private fun collectionAllowed(context: Context): Boolean =
        !BuildConfig.DEBUG && CrashReportingPreferences(context).enabled
}
