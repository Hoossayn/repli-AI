package dev.patrickgold.florisboard.repli.diagnostics

import android.content.Context

/** Whether anonymous crash reports may leave the device. On by default; the user can turn it off. */
class CrashReportingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, true)
        set(value) { preferences.edit().putBoolean(KEY_ENABLED, value).apply() }

    private companion object {
        const val PREFERENCES = "repli_crash_reporting"
        const val KEY_ENABLED = "enabled"
    }
}
