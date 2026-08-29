package com.vocahq.vocaphone.dictation

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vocahq.vocaphone.VocaPhoneApplication
import com.vocahq.vocaphone.R
import com.vocahq.vocaphone.core.DictationPhase
import com.vocahq.vocaphone.core.DictationState
import com.vocahq.vocaphone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds a foreground service for the whole dictation and shows the ongoing
 * notification while capture or on-device transcription is running.
 */
class DictationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = VocaPhoneApplication.container(this).dictation
        when (intent?.action) {
            ACTION_START -> {
                // Called before any capture begins: Android requires the microphone
                // foreground service to be visible for the whole recording.
                startForeground(
                    NOTIFICATION_ID,
                    notification("Listening"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                val source = intent.getStringExtra(EXTRA_SOURCE)
                    ?.let { runCatching { DictationSource.valueOf(it) }.getOrNull() }
                    ?: DictationSource.COMPANION_APP
                controller.start(source)
                observeUntilIdle()
            }

            ACTION_FINISH -> {
                controller.finish()
                stopUnlessHoldingMicrophone()
            }

            ACTION_CANCEL -> {
                controller.cancel()
                stopUnlessHoldingMicrophone()
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Keep the service alive through delivery and local inference. In particular,
     * Whisper is CPU-heavy; stopping the foreground service at the microphone
     * boundary lets Android treat the same process as background work just as
     * decoding begins. The observer below owns the service lifetime and stops it
     * at a terminal dictation state.
     */
    private fun stopUnlessHoldingMicrophone() {
        if (observer == null) stopSelf()
    }

    private fun observeUntilIdle() {
        val previous = observer
        val controller = VocaPhoneApplication.container(this).dictation
        observer = scope.launch {
            try {
                // Capture starts a moment after `start` returns, so the service
                // must wait for the controller to move off the idle snapshot it
                // subscribed with. StateFlow conflates, so a short tap can skip
                // LISTENING/FINALIZING and land on a new IDLE — that new instance
                // is still progress, and treating only hasSettled phases as the
                // end of the wait left "Listening" up until the timeout.
                val initial = controller.state.value
                val progressed = withTimeoutOrNull(START_TIMEOUT_MILLIS) {
                    controller.state.first { DictationStartWatch.hasProgressed(it, initial) }
                }
                if (progressed != null && !progressed.phase.isTerminal) {
                    var lastStatus: String? = null
                    controller.state
                        .onEach { state ->
                            if (state.statusText != lastStatus) {
                                lastStatus = state.statusText
                                publish(state.statusText)
                            }
                        }
                        .first { it.phase.isTerminal }
                }
            } finally {
                if (observer === coroutineContext[Job]) {
                    stopForegroundAndSelf()
                }
            }
        }
        previous?.cancel()
    }

    private fun publish(status: String) {
        startForeground(
            NOTIFICATION_ID,
            notification(status),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun stopForegroundAndSelf() {
        observer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(status: String): Notification {
        // Destination named, and nothing else set.
        //
        // The action and category this used to carry were copied from what
        // `packageManager.getLaunchIntentForPackage` builds internally, and they
        // were doing nothing: an action and a category exist so the system can
        // *resolve* an intent, and resolution does not happen once a component
        // is named. What they did do was make CodeQL read this as an implicit
        // intent handed to a third party (CWE-927), because its rule keys on
        // `setAction` and does not care that the constructor set a component.
        //
        // The rule is crude there but the direction is right: a PendingIntent
        // runs with this app's identity, an intent with an unfilled destination
        // can have one supplied by whoever holds it, and a notification's
        // content intent is held by the system UI. FLAG_IMMUTABLE already closed
        // that door. This leaves nothing for anyone to fill in.
        //
        // FLAG_ACTIVITY_NEW_TASK is not decoration: `getLaunchIntentForPackage`
        // sets it too, and starting an activity from a Service context needs it.
        // With the activity at the root of an existing task it brings that task
        // forward rather than stacking a second copy, which is what tapping an
        // ongoing notification should do.
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DictationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dictation)
            .setContentTitle("VocaPhone is working")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_cancel, "Cancel", cancel)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while VocaPhone is recording or transcribing."
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_START = "com.vocahq.vocaphone.START"
        const val ACTION_FINISH = "com.vocahq.vocaphone.FINISH"
        const val ACTION_CANCEL = "com.vocahq.vocaphone.CANCEL"
        const val EXTRA_SOURCE = "source"
        private const val CHANNEL_ID = "vocaphone.recording"
        private const val NOTIFICATION_ID = 4101

        /** How long the service waits for capture to begin before giving up. */
        private const val START_TIMEOUT_MILLIS = 10_000L

        /**
         * Starts recording. Newer Android versions refuse some background service
         * starts even when Android rejects a background launch, so the documented
         * fallback is a no-animation activity that starts the service while
         * visible and closes immediately.
         */
        fun start(context: Context, source: DictationSource) {
            val intent = Intent(context, DictationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE, source.name)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: ForegroundServiceStartNotAllowedException) {
                context.startActivity(
                    Intent(context, DictationLauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(EXTRA_SOURCE, source.name)
                )
            }
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, DictationService::class.java).setAction(action))
        }
    }
}

/**
 * Whether a start attempt has resolved, either way.
 *
 * [DictationService] starts before capture begins, so it needs to recognise the
 * outcomes that never reach the microphone at all — otherwise the only thing
 * that ends the wait is a timeout the user spends looking at a notification.
 *
 * Phase checks alone are not enough: the controller publishes on Default and
 * the service collects on Main, so StateFlow can skip LISTENING/FINALIZING.
 * [hasProgressed] is the StateFlow-safe test.
 */
internal object DictationStartWatch {
    fun hasSettled(phase: DictationPhase): Boolean = when (phase) {
        // Capture is running, which is what the service exists to cover.
        DictationPhase.LISTENING, DictationPhase.FINALIZING -> true
        // Resolved without ever recording: a permission to repair, or a
        // microphone that could not be opened.
        DictationPhase.PERMISSION_REPAIR, DictationPhase.FAILED -> true
        else -> false
    }

    /**
     * The service subscribed with [initial], the idle value already in the
     * flow. Any later instance — including a new Idle from a short-tap reset,
     * or Transcribing when Listening was conflated away — means the start
     * attempt has moved. Identity, not [DictationState.equals]: two Idle
     * snapshots compare equal, and that was the hang.
     */
    fun hasProgressed(current: DictationState, initial: DictationState): Boolean =
        current !== initial
}

/**
 * The predetermined fallback path only: visible for a single frame so the
 * microphone service can be started from the foreground.
 */
class DictationLauncherActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme.VocaPhone.Invisible suppresses the transition; there is nothing to
        // animate and nothing for the user to see.
        val source = intent.getStringExtra(DictationService.EXTRA_SOURCE)
            ?.let { runCatching { DictationSource.valueOf(it) }.getOrNull() }
            ?: DictationSource.COMPANION_APP
        ContextCompat.startForegroundService(
            this,
            Intent(this, DictationService::class.java)
                .setAction(DictationService.ACTION_START)
                .putExtra(DictationService.EXTRA_SOURCE, source.name),
        )
        finish()
    }
}
