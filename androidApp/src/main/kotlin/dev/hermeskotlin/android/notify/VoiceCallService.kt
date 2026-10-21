package dev.hermeskotlin.android.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.voice.VoiceCallNotice
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import org.koin.android.ext.android.inject

/**
 * Keeps a voice chat going with the screen off or another app in front, like a call: a microphone
 * foreground service, which is the only way Android lets an app keep listening out of sight. Its
 * notification is the call: how the chat is going, what it has spent, its clock, and Mute and End.
 */
class VoiceCallService : Service() {

    private val notifications: ChatNotifications by inject()
    private val keeper: VoiceCallKeeper by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Into the foreground first, always: a service started this way and stopped before it is a crash. It starts
        // with the call as the chat last left it, and each action lands back here.
        if (!start()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_END -> keeper.end()
            // The microphone goes off and the call stays: never a reason to stop the service.
            ACTION_MUTE -> keeper.toggle()
        }
        // The chat ended while it was starting: it was left to stop here.
        if (!keeper.started()) stopSelf()
        return START_NOT_STICKY
    }

    private fun start(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            ChatNotifications.VOICE_ID,
            notification(keeper.notice),
            // Types are Android 10+; the microphone one, 11+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        // From here each change of the chat is posted under the call's own id, in place of the one showing.
        keeper.attach(::show)
        true
    } catch (e: Exception) {
        // Android 14+ refuses a microphone service without the permission, or from the background.
        Log.w("VoiceCallService", "Couldn't keep the voice chat up", e)
        keeper.lost()
        stopSelf()
        false
    }

    /** The chat changed: the same call notification, rebuilt. Its id is the call's, so it replaces the old one. */
    private fun show(notice: VoiceCallNotice) {
        NotificationManagerCompat.from(this).notify(ChatNotifications.VOICE_ID, notification(notice))
    }

    private fun notification(notice: VoiceCallNotice) = notifications.voiceCall(notice, mute(), end())

    private fun mute(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, VoiceCallService::class.java).setAction(ACTION_MUTE), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun end(): PendingIntent = PendingIntent.getService(
        this, 0, Intent(this, VoiceCallService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        keeper.detach()
        keeper.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ACTION_END = "dev.hermeskotlin.voice.END"
        const val ACTION_MUTE = "dev.hermeskotlin.voice.MUTE"
    }
}

/**
 * [VoiceKeepAlive] on Android: holds [VoiceCallService] up for the voice chat, and is what the call's notification
 * talks to when it's tapped — the chat itself is the screen's, out of reach from the shade. All on the main thread,
 * as the service's callbacks and the voice chat are.
 */
class VoiceCallKeeper(private val context: Context) : VoiceKeepAlive {

    private var onEnd: (() -> Unit)? = null
    private var onLost: (() -> Unit)? = null
    private var onMute: ((Boolean) -> Unit)? = null
    private var onOpen: (() -> Unit)? = null
    /** A voice chat holds it. */
    private var wanted = false
    /** The service is in the foreground, so it may be stopped. */
    private var foreground = false
    /** Posts the call's notification, once the service is showing it. */
    private var poster: ((VoiceCallNotice) -> Unit)? = null

    /** What the call's notification says: the chat's state as the controller last left it. */
    var notice: VoiceCallNotice = VoiceCallNotice(line = "Voice chat")
        private set

    /** A voice chat or a call is on, holding the microphone. */
    val holding: Boolean get() = wanted

    override fun hold(
        onEnd: () -> Unit,
        onLost: () -> Unit,
        onMute: (Boolean) -> Unit,
        onOpen: () -> Unit,
    ): Boolean = try {
        this.onEnd = onEnd
        this.onLost = onLost
        this.onMute = onMute
        this.onOpen = onOpen
        wanted = true
        ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java))
        true
    } catch (e: Exception) {
        // Refused, e.g. from the background; the chat then ends when the app goes away.
        Log.w("VoiceCallService", "Couldn't keep the voice chat up", e)
        forget()
        false
    }

    override fun release() {
        forget()
        // One still starting stops itself once it's in the foreground (started).
        if (foreground) context.stopService(Intent(context, VoiceCallService::class.java))
    }

    override fun show(notice: VoiceCallNotice) {
        if (notice == this.notice) return
        this.notice = notice
        // Before the service is up, the notification it starts with reads this; after, each change is posted.
        if (foreground) poster?.invoke(notice)
    }

    override fun open() {
        onOpen?.invoke()
    }

    /** The notification's Mute or Unmute: the chat's microphone, the other way round from the call's own. */
    internal fun toggle() {
        onMute?.invoke(!notice.muted)
    }

    /** The service is showing the call; it posts each change from here. */
    internal fun attach(poster: (VoiceCallNotice) -> Unit) {
        this.poster = poster
    }

    internal fun detach() {
        poster = null
    }

    /** The service is in the foreground; false when the chat ended meanwhile, so it should stop. */
    internal fun started(): Boolean {
        foreground = wanted
        return wanted
    }

    internal fun stopped() {
        foreground = false
        poster = null
    }

    /** The service couldn't go into the foreground after all. */
    internal fun lost() {
        val lost = onLost
        forget()
        lost?.invoke()
    }

    /** The notification's End. */
    fun end() {
        val end = onEnd
        if (end == null) release() else end()
    }

    private fun forget() {
        wanted = false
        onEnd = null
        onLost = null
        onMute = null
        onOpen = null
    }
}
