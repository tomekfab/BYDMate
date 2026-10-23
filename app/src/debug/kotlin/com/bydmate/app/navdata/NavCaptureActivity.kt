package com.bydmate.app.navdata

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bydmate.app.R
import kotlinx.coroutines.delay

/** Manual debug-only capture control. Saving is impossible until the user picks a document URI. */
class NavCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selected by remember { mutableStateOf("com.google.android.apps.maps") }
            var state by remember { mutableStateOf(NavCaptureBridge.snapshot()) }
            val scrollState = rememberScrollState()
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/jsonl")) { uri ->
                if (uri != null) NavCaptureBridge.saveTo(this, uri)
                state = NavCaptureBridge.snapshot()
            }
            LaunchedEffect(Unit) {
                while (true) {
                    state = NavCaptureBridge.snapshot()
                    delay(1_000)
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.nav_capture_title))
                Text(stringResource(R.string.nav_capture_disclosure))
                Text(stringResource(R.string.nav_capture_app, if (selected == "com.waze") "Waze" else "Google Maps"))
                Button(onClick = { selected = "com.google.android.apps.maps" }) { Text("Google Maps") }
                Button(onClick = { selected = "com.waze" }) { Text("Waze") }
                Text(stringResource(R.string.nav_capture_events, state.eventCount, state.queuedRecords, state.droppedRecords, state.remainingMs / 1000))
                Text(stringResource(R.string.nav_capture_state))
                NavCaptureMarker.entries.forEach { marker ->
                    Button(
                        onClick = {
                            state.packageName?.let { recordNavCaptureMarker(it, marker) }
                            state = NavCaptureBridge.snapshot()
                        },
                        enabled = state.recording,
                    ) { Text(stringResource(markerLabel(marker))) }
                }
                Button(onClick = { NavCaptureBridge.start(selected, this@NavCaptureActivity); state = NavCaptureBridge.snapshot() }) { Text(stringResource(R.string.nav_capture_start)) }
                Button(onClick = { NavCaptureBridge.stop(); state = NavCaptureBridge.snapshot() }) { Text(stringResource(R.string.nav_capture_stop)) }
                Button(onClick = { NavCaptureBridge.delete(); state = NavCaptureBridge.snapshot() }) { Text(stringResource(R.string.nav_capture_delete)) }
                Button(onClick = { save.launch("bydmate-nav-capture.jsonl") }, enabled = state.canSave) { Text(stringResource(R.string.nav_capture_save)) }
                state.error?.let { Text(stringResource(errorLabel(it))) }
                state.stopReason?.let { Text(stringResource(R.string.nav_capture_stopped, stopReasonLabel(it))) }
            }
        }
    }

    private fun errorLabel(error: NavCaptureError): Int = when (error) {
        NavCaptureError.DELETE_FAILED -> R.string.nav_capture_error_delete
        NavCaptureError.RESET_FAILED -> R.string.nav_capture_error_reset
        NavCaptureError.EXPORT_FAILED -> R.string.nav_capture_error_export
        NavCaptureError.WRITE_FAILED -> R.string.nav_capture_error_write
    }

    private fun stopReasonLabel(reason: NavCaptureStopReason): String = getString(when (reason) {
        NavCaptureStopReason.TIME_LIMIT -> R.string.nav_capture_reason_time
        NavCaptureStopReason.FILE_LIMIT -> R.string.nav_capture_reason_file
        NavCaptureStopReason.RECORD_TOO_LARGE -> R.string.nav_capture_reason_record
        NavCaptureStopReason.STOPPED -> R.string.nav_capture_reason_stop
        NavCaptureStopReason.DELETED -> R.string.nav_capture_reason_delete
    })

    private fun markerLabel(marker: NavCaptureMarker): Int = when (marker) {
        NavCaptureMarker.GUIDING -> R.string.nav_capture_marker_guiding
        NavCaptureMarker.FOREGROUND -> R.string.nav_capture_marker_foreground
        NavCaptureMarker.BACKGROUND -> R.string.nav_capture_marker_background
        NavCaptureMarker.TURN -> R.string.nav_capture_marker_turn
        NavCaptureMarker.REROUTE -> R.string.nav_capture_marker_reroute
        NavCaptureMarker.ARRIVED -> R.string.nav_capture_marker_arrived
        NavCaptureMarker.CANCEL -> R.string.nav_capture_marker_cancel
    }
}
