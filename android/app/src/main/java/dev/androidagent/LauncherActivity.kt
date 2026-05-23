package dev.androidagent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.canDrawOverlays(this)) {
            runCatching {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AgentForegroundService::class.java)
                        .setAction(AgentForegroundService.ACTION_ENSURE_SERVICE)
                )
            }
            startActivity(
                Intent(this, AppShellActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
