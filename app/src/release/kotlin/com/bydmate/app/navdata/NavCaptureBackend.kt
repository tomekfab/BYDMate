package com.bydmate.app.navdata

import android.content.Context
import android.content.Intent

/** Release builds intentionally have no recorder, UI or persistent capture state. */
class DefaultNavCaptureBackend : NavCaptureLifecycleBackend {
    override fun notification(record: NavCaptureRecord) = Unit
    override fun accessibility(record: NavCaptureRecord) = Unit
    override fun captureActivityIntent(context: Context): Intent? = null
}
