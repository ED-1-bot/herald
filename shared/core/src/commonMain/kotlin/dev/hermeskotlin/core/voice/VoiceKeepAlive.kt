package dev.hermeskotlin.core.voice

/**
 * Keeps a voice chat going while Herald is out of sight (the screen off, another app in front), the way a
 * phone call does. On Android, a microphone foreground service with an ongoing notification.
 */
interface VoiceKeepAlive {
    /**
     * Holds the voice chat up until [release]. [onEnd] runs when the person ends it from outside the app (the
     * notification's End); [onMute] when the notification's Mute or Unmute is tapped, with the state wanted;
     * [onOpen] when the call itself is tapped, so the screen comes back to it. False when the platform refused at
     * once, and [onLost] runs when it refuses later; either way the chat should end when the app goes away.
     */
    fun hold(
        onEnd: () -> Unit,
        onLost: () -> Unit = {},
        onMute: (Boolean) -> Unit = {},
        onOpen: () -> Unit = {},
    ): Boolean

    fun release()

    /** What the call's ongoing notification says now; called as the chat changes, and often. */
    fun show(notice: VoiceCallNotice) {}

    /** The call's notification was tapped: bring the chat back to the screen, where it's still running. */
    fun open() {}
}
