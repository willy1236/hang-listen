package dev.willy1236.hanglisten

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一個前景服務裡同時跑 N 個 ExoPlayer。
 *
 * - 每個播放器都關掉自己的音訊焦點處理（不然會互相搶焦點、互相暫停），
 *   改由服務統一向系統要一次焦點，失去焦點時一起暫停。
 * - 只播 audio_only 串流，不解碼影像，省電省流量。
 * - 斷線、token 過期、未開台都會自動重新解析並重試。
 */
class PlaybackService : Service() {

    enum class Status(val label: String) {
        RESOLVING("解析中"),
        BUFFERING("緩衝中"),
        PLAYING("播放中"),
        PAUSED("已暫停"),
        OFFLINE("未開台，每分鐘檢查一次"),
        ERROR("發生錯誤，稍後重試"),
    }

    data class StreamState(
        val id: Long,
        val channel: String,
        val status: Status,
        val volume: Float,
        val message: String?,
    )

    private class Entry(val id: Long, val channel: String, val player: ExoPlayer) {
        var status = Status.RESOLVING
        var message: String? = null
        var volume = 1f
        var job: Job? = null
        var retryCount = 0
    }

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val entries = LinkedHashMap<Long, Entry>()
    private var nextId = 1L

    private val _streams = MutableStateFlow<List<StreamState>>(emptyList())
    val streams: StateFlow<List<StreamState>> = _streams.asStateFlow()

    private val _pausedByUser = MutableStateFlow(false)
    val pausedByUser: StateFlow<Boolean> = _pausedByUser.asStateFlow()

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnFocusGain = false
    private var duckFactor = 1f
    private var inForeground = false
    private var savedSession: String? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "播放中", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> if (_pausedByUser.value) resumeAll() else pauseAll()
            ACTION_STOP_ALL -> stopAll()
        }
        return START_NOT_STICKY
    }

    // 滑掉 App 不停止播放，這正是掛台要的行為
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        entries.values.forEach {
            it.job?.cancel()
            it.player.release()
        }
        entries.clear()
        abandonFocus()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 給 UI 呼叫的操作 ----------

    /** 成功回傳 null，失敗回傳錯誤訊息。 */
    fun addChannel(input: String, volume: Float = 1f): String? {
        val channel = TwitchResolver.normalizeChannel(input) ?: return "頻道 ID 格式不對"
        if (entries.values.any { it.channel == channel }) return "$channel 已經在清單裡了"

        ensureForeground()
        if (!_pausedByUser.value) requestFocus()

        val entry = Entry(nextId++, channel, buildPlayer())
        entry.volume = volume
        entry.player.addListener(listenerFor(entry))
        entries[entry.id] = entry
        load(entry)
        return null
    }

    /**
     * 接著播上次沒停掉的台。按「全部停止」或移除到一台不剩時會清空紀錄，
     * 所以只有 App 被滑掉、被系統砍掉或重開機後才會接著播。
     */
    fun restoreLastSession() {
        if (entries.isNotEmpty()) return
        prefs().getString(KEY_SESSION, null).orEmpty().lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                val volume = parts.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
                parts[0] to volume
            }
            .toList()
            .forEach { (channel, volume) -> addChannel(channel, volume) }
    }

    fun remove(id: Long) {
        val entry = entries.remove(id) ?: return
        entry.job?.cancel()
        entry.player.release()
        publish()
        if (entries.isEmpty()) shutdown()
    }

    fun setVolume(id: Long, volume: Float) {
        val entry = entries[id] ?: return
        entry.volume = volume
        entry.player.volume = volume * duckFactor
        publish(updateNotification = false)
    }

    fun pauseAll() {
        _pausedByUser.value = true
        resumeOnFocusGain = false
        entries.values.forEach { it.player.pause() }
        updateNotification()
    }

    fun resumeAll() {
        _pausedByUser.value = false
        if (!requestFocus()) {
            updateNotification()
            return
        }
        entries.values.forEach { it.player.play() }
        updateNotification()
    }

    fun stopAll() {
        entries.values.forEach {
            it.job?.cancel()
            it.player.release()
        }
        entries.clear()
        publish()
        shutdown()
    }

    // ---------- 播放與重試 ----------

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(this)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ false,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .apply {
            // Twitch 插播廣告時，audio_only 清單裡也會夾帶 1080p 影像片段；
            // 不關掉影像軌的話每台都會開一個硬體影像解碼器，多台時互搶而卡在緩衝
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }

    private fun load(entry: Entry) {
        entry.job?.cancel()
        setStatus(entry, Status.RESOLVING)
        entry.job = scope.launch {
            try {
                val url = TwitchResolver.resolveAudioUrl(entry.channel)
                if (!entries.containsKey(entry.id)) return@launch
                entry.player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build(),
                )
                entry.player.volume = entry.volume * duckFactor
                entry.player.playWhenReady = hasFocus && !_pausedByUser.value
                entry.player.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwitchResolver.OfflineException) {
                setStatus(entry, Status.OFFLINE)
                retryLater(entry, OFFLINE_POLL_MS)
            } catch (e: Exception) {
                setStatus(entry, Status.ERROR, e.message)
                retryLater(entry, nextBackoff(entry))
            }
        }
    }

    private fun retryLater(entry: Entry, delayMs: Long) {
        entry.job = scope.launch {
            delay(delayMs)
            if (entries.containsKey(entry.id)) load(entry)
        }
    }

    /** 5s、10s、20s… 最多 2 分鐘。 */
    private fun nextBackoff(entry: Entry): Long {
        val delayMs = (5_000L shl entry.retryCount.coerceAtMost(5)).coerceAtMost(120_000L)
        entry.retryCount++
        return delayMs
    }

    private fun listenerFor(entry: Entry) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> setStatus(entry, Status.BUFFERING)
                Player.STATE_READY -> {
                    entry.retryCount = 0
                    setStatus(entry, if (entry.player.playWhenReady) Status.PLAYING else Status.PAUSED)
                }
                // 直播結束（下播）
                Player.STATE_ENDED -> {
                    setStatus(entry, Status.OFFLINE)
                    retryLater(entry, OFFLINE_POLL_MS)
                }
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // 拔耳機時 ExoPlayer 會自己暫停；把它當成使用者暫停，全部一起停
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                pauseAll()
            }
            if (entry.player.playbackState == Player.STATE_READY) {
                setStatus(entry, if (playWhenReady) Status.PLAYING else Status.PAUSED)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                entry.player.seekToDefaultPosition()
                entry.player.prepare()
                return
            }
            // 其他錯誤（含 token 過期、網路斷線）一律重新解析，順便換新 token
            setStatus(entry, Status.ERROR, error.errorCodeName)
            retryLater(entry, nextBackoff(entry))
        }
    }

    private fun setStatus(entry: Entry, status: Status, message: String? = null) {
        entry.status = status
        entry.message = message
        publish()
    }

    private fun publish(updateNotification: Boolean = true) {
        _streams.value = entries.values.map {
            StreamState(it.id, it.channel, it.status, it.volume, it.message)
        }
        if (updateNotification) updateNotification()
        saveSession()
    }

    private fun saveSession() {
        val session = entries.values.joinToString("\n") { "${it.channel}\t${it.volume}" }
        if (session == savedSession) return
        savedSession = session
        prefs().edit().putString(KEY_SESSION, session).apply()
    }

    private fun prefs() = getSharedPreferences("session", MODE_PRIVATE)

    // ---------- 音訊焦點：整個服務共用一份 ----------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                applyDuck(1f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    entries.values.forEach { it.player.play() }
                }
            }
            // 其他 App 開始播放：全部暫停，等使用者自己按繼續
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pauseAll()
            }
            // 來電、導航語音等短暫中斷：暫停，結束後自動恢復
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                if (!_pausedByUser.value) {
                    resumeOnFocusGain = true
                    entries.values.forEach { it.player.pause() }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> applyDuck(0.2f)
        }
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasFocus = false
    }

    private fun applyDuck(factor: Float) {
        duckFactor = factor
        entries.values.forEach { it.player.volume = it.volume * factor }
    }

    // ---------- 前景服務與通知 ----------

    private fun ensureForeground() {
        if (inForeground) return
        // 先「啟動」服務，這樣 Activity 解除綁定後服務仍會繼續跑
        ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        inForeground = true
    }

    private fun shutdown() {
        abandonFocus()
        _pausedByUser.value = false
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf()
    }

    private fun updateNotification() {
        if (!inForeground) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val playing = entries.values.count { it.status == Status.PLAYING }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("掛台中：$playing / ${entries.size} 台播放中")
            .setContentText(entries.values.joinToString("、") { it.channel })
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (_pausedByUser.value) "繼續" else "暫停", serviceIntent(ACTION_TOGGLE))
            .addAction(0, "全部停止", serviceIntent(ACTION_STOP_ALL))
            .build()
    }

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val KEY_SESSION = "channels"
        private const val ACTION_TOGGLE = "dev.willy1236.hanglisten.TOGGLE"
        private const val ACTION_STOP_ALL = "dev.willy1236.hanglisten.STOP_ALL"
        private const val OFFLINE_POLL_MS = 60_000L
    }
}
