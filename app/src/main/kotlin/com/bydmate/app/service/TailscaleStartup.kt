package com.bydmate.app.service

import android.content.Context
import android.content.Intent
import android.util.Log

/** Requests an already configured Tailscale VPN without opening its activity. */
internal object TailscaleStartup {
    private const val TAG = "TailscaleStartup"

    internal data class ConnectionRequestResult(
        val receiverSupported: Boolean,
        val sent: Boolean,
    )

    // This optional external-app boundary must not abort foreground-service creation.
    @Suppress("TooGenericExceptionCaught")
    fun requestConnection(context: Context): ConnectionRequestResult {
        val request = Intent("com.tailscale.ipn.CONNECT_VPN")
            .setClassName("com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver")
        val receiverSupported = try {
            context.packageManager.queryBroadcastReceivers(request, 0).isNotEmpty()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "VPN receiver resolution failed: ${failure.javaClass.simpleName}")
            false
        }
        val sent = try {
            context.sendBroadcast(
                request
            )
            Log.i(TAG, "VPN connection requested; delivery and connection are not confirmed")
            true
        } catch (failure: RuntimeException) {
            // Another app or a head-unit restriction must not prevent our service startup.
            Log.w(TAG, "VPN connection request failed: ${failure.javaClass.simpleName}")
            false
        }

        return ConnectionRequestResult(
            receiverSupported = receiverSupported,
            sent = sent,
        )
    }
}
