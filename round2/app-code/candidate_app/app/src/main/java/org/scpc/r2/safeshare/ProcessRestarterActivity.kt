package org.scpc.r2.safeshare

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Foreground, app-local bridge that lives in a process separate from [MainActivity].
 *
 * Android 15 blocks an alarm-fired PendingIntent from bringing an activity to the
 * foreground in several background-launch cases. This activity is already visible
 * when it terminates the old main process, so its explicit relaunch remains a
 * user-initiated foreground transition rather than a background activity launch.
 */
class ProcessRestarterActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var restartStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
                addView(ProgressBar(this@ProcessRestarterActivity))
                addView(
                    TextView(this@ProcessRestarterActivity).apply {
                        text = "앱을 다시 시작하고 있습니다…"
                        textSize = 17f
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(20), 0, 0)
                    },
                )
            },
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !restartStarted) {
            restartStarted = true
            handler.postDelayed(::terminateOldMainProcess, BRIDGE_VISIBLE_DELAY_MS)
        }
    }

    private fun terminateOldMainProcess() {
        val previousPid = intent.getIntExtra(
            ProcessRestartProtocol.EXTRA_PREVIOUS_PID,
            -1,
        )
        if (!ProcessRestartProtocol.isPidChangeVerified(previousPid, Process.myPid())) {
            clearPendingRestart()
            showFailure("재시작 대상 PID가 올바르지 않습니다.")
            return
        }

        Process.killProcess(previousPid)
        handler.postDelayed(::relaunchMainActivity, MAIN_RELAUNCH_DELAY_MS)
    }

    private fun relaunchMainActivity() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }.onSuccess {
            finish()
        }.onFailure { error ->
            clearPendingRestart()
            showFailure("메인 화면 재실행 실패: ${error.message}")
        }
    }

    private fun clearPendingRestart() {
        getSharedPreferences(ProcessRestartProtocol.PREFERENCES, MODE_PRIVATE)
            .edit()
            .remove(ProcessRestartProtocol.KEY_RESTART_PENDING)
            .remove(ProcessRestartProtocol.KEY_PREVIOUS_PID)
            .remove(ProcessRestartProtocol.KEY_RESTART_SCHEDULED_AT)
            .commit()
    }

    private fun showFailure(message: String) {
        setContentView(
            TextView(this).apply {
                text = message
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
            },
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BRIDGE_VISIBLE_DELAY_MS = 250L
        private const val MAIN_RELAUNCH_DELAY_MS = 650L
    }
}
