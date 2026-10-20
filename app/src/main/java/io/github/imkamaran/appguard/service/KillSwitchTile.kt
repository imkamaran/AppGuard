package io.github.imkamaran.appguard.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.imkamaran.appguard.AppGuardApp

class KillSwitchTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val app = application as? AppGuardApp ?: return
        val current = app.policies.state.value.global.masterEnabled
        app.policies.updateGlobal { it.copy(masterEnabled = !current) }
        updateTile()
    }

    private fun updateTile() {
        val app = application as? AppGuardApp ?: return
        val enabled = app.policies.state.value.global.masterEnabled
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "AppGuard"
            subtitle = if (enabled) "Enforcing" else "Paused"
            updateTile()
        }
    }
}
