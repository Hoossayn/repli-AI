package dev.patrickgold.florisboard.ime.keyboard3.ui

import android.os.Trace

/** Constant section names identify rendering work without recording key labels or editor text. */
internal inline fun <T> traceImeRender(section: String, block: () -> T): T {
    Trace.beginSection(section)
    return try { block() } finally { Trace.endSection() }
}
