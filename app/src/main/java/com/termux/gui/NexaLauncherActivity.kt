package com.termux.gui

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.termux.gui.Util.Companion.runOnUIThreadBlocking
import com.termux.gui.protocol.shared.v0.DataClasses
import java.lang.ref.WeakReference

class NexaLauncherActivity : GUIActivity() {
    companion object {
        private const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val REQUEST_RUN_COMMAND = 1
        private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        @Volatile private var pendingHost = WeakReference<NexaLauncherActivity>(null)

        fun adoptPendingHost(connectionId: Long, aid: Int, state: DataClasses.ActivityState,
                             listener: GUIActivity.Listener, activities: MutableMap<Int, DataClasses.ActivityState>,
                             tasks: MutableList<ActivityManager.AppTask>): GUIActivity? {
            val host = pendingHost.get() ?: return null
            if (host.isFinishing || host.isDestroyed || host.intent.data != null) return null
            var adopted: GUIActivity? = null
            runOnUIThreadBlocking(Runnable {
                if (host.isFinishing || host.isDestroyed || host.intent.data != null) return@Runnable
                host.intent.data = Uri.parse("$connectionId-$aid")
                host.listener = listener
                state.a = host
                activities[aid] = state
                val task = host.getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
                    it.taskInfo?.id == host.taskId
                }
                if (task != null && tasks.none { it.taskInfo?.id == host.taskId }) tasks.add(task)
                host.setContentView(R.layout.activity_gui)
                listener.onActivityCreated(state)
                listener.onActivityStarted(state)
                listener.onActivityResumed(state)
                adopted = host
            })
            return adopted
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showBooting()
        pendingHost = WeakReference(this)
        if (showExistingNexa()) return
        if (checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startNexa()
        } else {
            requestPermissions(arrayOf(RUN_COMMAND_PERMISSION), REQUEST_RUN_COMMAND)
        }
    }

    private fun showBooting() {
        val label = TextView(this).apply {
            text = "Booting Nexa"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        setContentView(FrameLayout(this).apply { addView(label, FrameLayout.LayoutParams(-1, -1)) })
    }

    private fun showExistingNexa(): Boolean {
        val windowActivities = setOf(
            GUIActivity::class.java.name,
            GUIActivityDialog::class.java.name,
            GUIActivityLockscreen::class.java.name,
        )
        val task = getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
            val taskInfo = it.taskInfo
            listOf(taskInfo.baseActivity, taskInfo.topActivity, taskInfo.baseIntent.component)
                .any { component -> component?.packageName == packageName && component.className in windowActivities }
        } ?: return false
        task.moveToFront()
        finish()
        return true
    }

    private fun startNexa() {
        val command = """
            NEXA_DIR="${'$'}HOME/nexa"
            STATE_DIR="${'$'}HOME/.cache/nexa"
            PID_FILE="${'$'}STATE_DIR/pid"
            LAUNCH_FILE="${'$'}STATE_DIR/launch"
            ACTIVE_FILE="${'$'}STATE_DIR/active"
            mkdir -p "${'$'}STATE_DIR" || exit 1

            PID=$(cat "${'$'}PID_FILE" 2>/dev/null || true)
            RUNNING=0
            if [ -n "${'$'}PID" ] && kill -0 "${'$'}PID" 2>/dev/null; then
                PROCESS=$(ps -p "${'$'}PID" -o args= 2>/dev/null || true)
                case "${'$'}PROCESS" in *"${'$'}NEXA_DIR/main.py"*) RUNNING=1;; esac
            fi
            if [ "${'$'}RUNNING" -eq 1 ]; then
                if [ -e "${'$'}ACTIVE_FILE" ] || [ -e "${'$'}LAUNCH_FILE" ]; then exit 0; fi
                touch "${'$'}LAUNCH_FILE"
                exit 0
            fi

            if [ ! -d "${'$'}NEXA_DIR/.git" ]; then
                git clone https://github.com/encdata/nexa.git "${'$'}NEXA_DIR" || exit 1
            else
                git -C "${'$'}NEXA_DIR" fetch origin || exit 1
                REMOTE_HEAD=$(git -C "${'$'}NEXA_DIR" rev-parse refs/remotes/origin/main) || exit 1
                git -C "${'$'}NEXA_DIR" reset --hard "${'$'}REMOTE_HEAD" || exit 1
            fi

            rm -f "${'$'}ACTIVE_FILE"
            touch "${'$'}LAUNCH_FILE"
            echo "${'$'}${'$'}" > "${'$'}PID_FILE"
            cd "${'$'}NEXA_DIR" || exit 1
            exec python "${'$'}NEXA_DIR/main.py" --background-wait
        """.trimIndent()

        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/sh")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, "~/")
            putExtra(EXTRA_BACKGROUND, true)
        }

        try {
            startService(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Could not start Nexa")
                .setMessage("Termux could not run the launcher. Check that Termux is installed and external app commands are enabled.")
                .setPositiveButton("ok") { _, _ -> finish() }
                .show()
        }
    }

    override fun onDestroy() {
        if (pendingHost.get() === this) pendingHost.clear()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RUN_COMMAND) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startNexa()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Termux permission needed")
            .setMessage("Allow this app to run commands in Termux so it can start Nexa.")
            .setPositiveButton("open app settings") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
                finish()
            }
            .setNegativeButton("close") { _, _ -> finish() }
            .show()
    }
}
