package com.termux.gui

import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.*
import java.util.concurrent.*

/**
 * The foreground service that prevents the process from being killed when no Activity is shown.
 * It receives the connection requests gathered by the GUIReceiver and hands them to executor Threads and ConnectionHandler
 */
class GUIService : Service() {
    class ConnectionRequest(val mainSocket: String?, val eventSocket: String?, val nexa: Boolean = false)
    
    companion object {
        private val TAG: String? = GUIService::class.java.canonicalName
        const val NOTIFICATION_ID = 100
        private const val REQUEST_POLL_INTERVAL_MS = 100L
        val requests = LinkedBlockingQueue<ConnectionRequest>()
    }


    /**
     * A list of Runnables that are run on service destroy, because the handler threads waiting for IO can't be easily killed otherwise.
     */
    val destroywatch: MutableSet<Runnable> = Collections.synchronizedSet(HashSet())

    /**
     * The pool of connection threads
     */
    private val pool = ThreadPoolExecutor(10, 30, 1, TimeUnit.SECONDS, ArrayBlockingQueue(10))

    /**
     * Listens for requests and passes them to the thread pool.
     */
    private val requestWatcher: Thread

    /**
     * Constructs the foreground service notification
     */
    private val notification: Notification
        get() {
            NotificationManagerCompat.from(this).createNotificationChannel(
                    NotificationChannelCompat.Builder("service", NotificationManagerCompat.IMPORTANCE_LOW)
                            .setName("Service").setLightsEnabled(false).setVibrationEnabled(false).build())
            val b = NotificationCompat.Builder(this, "service")
            b.setSmallIcon(R.drawable.ic_service_notification)
            b.setContentTitle("Termux:GUI")
            b.setOnlyAlertOnce(true)
            b.setOngoing(true)
            b.setShowWhen(false)
            b.setAllowSystemGeneratedContextualActions(false)
            b.setSilent(true)
            b.priority = NotificationCompat.PRIORITY_LOW
            b.addAction(NotificationCompat.Action.Builder(0, "Close all",
                    PendingIntent.getBroadcast(this, 0, Intent(this, ServiceShutdownReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
            b.setContentText("Connections: " + pool.activeCount)
            return b.build()
        }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification)
        if (! requestWatcher.isAlive) {
            // clean up any old task stacks
            getSystemService(ActivityManager::class.java).let {
                for (t in it.appTasks) {
                    if (t.taskInfo.baseIntent.component == ComponentName(this, GUIActivity::class.java) ||
                            t.taskInfo.baseIntent.component == ComponentName(this, GUIActivityDialog::class.java) ||
                            t.taskInfo.baseIntent.component == ComponentName(this, GUIActivityLockscreen::class.java))
                        t.finishAndRemoveTask()
                }
            }
            requestWatcher.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // This is needed because the Android LocalSocket implementation doesn't close itself when the Thread is interrupted.
        // The Runnables close the connections, making the handler Threads throw an exception to terminate them.
        for (r in destroywatch) {
            try {
                r.run()
            } catch (ignored: Exception) {}
        }
        requestWatcher.interrupt()
        
        pool.shutdownNow()
        Logger.log(1, TAG, "service destroyed")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    init {
        requestWatcher = Thread (fun() {
            var noClientsSince: Long? = SystemClock.elapsedRealtime()
            // the number of active connection on the last loop run
            var lastConnections = 0
            var foreground = true
            while (!Thread.currentThread().isInterrupted) {
                val r = try {
                    requests.poll(REQUEST_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                val count = pool.activeCount
                val now = SystemClock.elapsedRealtime()
                val background = Settings.instance.background
                val timeout = Settings.instance.timeout
                if (r != null) noClientsSince = now
                if (count > 0 || timeout < 0) noClientsSince = null
                else if (noClientsSince == null) noClientsSince = now

                // update the connection count in the notification
                if (lastConnections != count) {
                    lastConnections = count
                    if (!background || count > 0) {
                        startForeground(NOTIFICATION_ID, notification)
                        foreground = true
                    }
                }
                // automatically close the service if no clients are connected and the setting is active
                if (background && count == 0 && foreground) {
                    stopForeground(true)
                    foreground = false
                } else if (!background && !foreground) {
                    startForeground(NOTIFICATION_ID, notification)
                    foreground = true
                }
                // submit a new connection to the worker pool
                if (r != null) {
                    Logger.log(1, TAG, "new connection")
                    try {
                        pool.submit(ConnectionHandler(r, this))
                    } catch (ignored: RejectedExecutionException) {}
                } else if (timeout >= 0 && count == 0 &&
                    now - (noClientsSince ?: now) >= timeout * 1000L) {
                    stopSelf()
                    return
                }
            }
        }, "nexa-request-watcher")
    }
}
