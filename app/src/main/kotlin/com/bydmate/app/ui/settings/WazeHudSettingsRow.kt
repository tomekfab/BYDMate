package com.bydmate.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.hud.HudController

/** Experimental Waze guidance on the HUD (distance and next street, no arrow); off by default. The toggle
 *  is the [HudController] pref: the controller decides when the feed may run. */
@Composable
fun WazeHudSettingsRow(hudController: HudController) {
    var wazeEnabled by remember { mutableStateOf(hudController.isWazeEnabled()) }
    SettingToggleRow(
        title = stringResource(R.string.settings_hud_waze_title),
        traceId = "hud_waze",
        description = stringResource(R.string.settings_hud_waze_desc),
        checked = wazeEnabled,
        onCheckedChange = {
            wazeEnabled = it
            hudController.setWazeEnabled(it)
        },
    )
}
