package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.data.automation.CancelOrTimeout
import com.bydmate.app.data.automation.ConfirmOverlayManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Production [ActionConfirmer]: the on-screen confirm overlay, bridged to one suspend call.
 * The overlay has no dismiss handle, so after the caller is cancelled it stays up until a tap or
 * its timeout; a late tap finds the continuation gone and does nothing.
 */
class OverlayActionConfirmer(private val context: Context) : ActionConfirmer {
    override suspend fun confirm(title: String, summary: String): ConfirmResult =
        suspendCancellableCoroutine { cont ->
            val answered = AtomicBoolean(false)
            fun answer(result: ConfirmResult) {
                if (answered.compareAndSet(false, true) && cont.isActive) cont.resume(result)
            }
            val shown = ConfirmOverlayManager.show(
                context = context,
                ruleName = title,
                actionsSummary = summary,
                onConfirm = { answer(ConfirmResult.CONFIRMED) },
                onCancel = CancelOrTimeout(
                    onCancel = { answer(ConfirmResult.DECLINED) },
                    onTimeout = { answer(ConfirmResult.TIMED_OUT) },
                ),
            )
            if (!shown) answer(ConfirmResult.UNAVAILABLE)
        }
}
