package com.homs.autoreply

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object ServiceController {
    fun start(ctx: Context) {
        AppState.setEnabled(true)
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java))
        } catch (e: Exception) {
            AppState.setEnabled(false)
            AppState.addLog("تعذر تشغيل الخدمة: ${e.message}")
        }
    }

    fun stop(ctx: Context) {
        AppState.setEnabled(false)
        ctx.stopService(Intent(ctx, CallService::class.java))
    }

    fun restart(ctx: Context) {
        ctx.stopService(Intent(ctx, CallService::class.java))
        start(ctx)
    }
}

class CallService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var telephony: TelephonyManager? = null
    private var modernCallback: Any? = null
    private var legacyListener: PhoneStateListener? = null

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var inCall = false
    private var answeredByUs = false
    private var playStarted = false
    private var logged = false
    private var player: MediaPlayer? = null
    private var audioChanged = false
    private var speakerBefore = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceController.stop(this)
            return START_NOT_STICKY
        }
        startAsForeground()
        if (telephony == null) register()
        if (telephony != null) AppState.serviceRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        restoreAudio()
        unregister()
        AppState.serviceRunning.value = false
        AppState.status.value = "لا توجد مكالمة حاليًا"
        super.onDestroy()
    }

    // ---------- الخدمة الأمامية ----------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "HOMS AUTO REPLY", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("HOMS AUTO REPLY")
            .setContentText("الرد التلقائي نشط")
            .setContentIntent(open)
            .addAction(0, "إيقاف", stop)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    // ---------- مراقبة حالة المكالمة ----------
    private fun register() {
        try {
            val tm = getSystemService(TelephonyManager::class.java)
            val subId = AppState.simSubId()
            val target = if (subId >= 0) tm.createForSubscriptionId(subId) else tm
            telephony = target
            if (Build.VERSION.SDK_INT >= 31) registerModern(target) else registerLegacy(target)
        } catch (e: SecurityException) {
            telephony = null
            AppState.addLog("تعذر بدء المراقبة: صلاحية READ_PHONE_STATE غير ممنوحة")
            ServiceController.stop(this)
        }
    }

    @RequiresApi(31)
    private fun registerModern(tm: TelephonyManager) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleState(state)
            }
        }
        modernCallback = cb
        tm.registerTelephonyCallback(mainExecutor, cb)
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(tm: TelephonyManager) {
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleState(state)
            }
        }
        legacyListener = l
        tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    private fun unregister() {
        val tm = telephony ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                (modernCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {
        }
        telephony = null
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING ->
                if (lastState == TelephonyManager.CALL_STATE_IDLE) onRinging()
            TelephonyManager.CALL_STATE_OFFHOOK ->
                if (answeredByUs && !playStarted) onAnswered()
            TelephonyManager.CALL_STATE_IDLE -> onIdle()
        }
        lastState = state
    }

    // ---------- مراحل المكالمة ----------
    private fun onRinging() {
        inCall = true
        logged = false
        answeredByUs = false
        playStarted = false
        AppState.status.value = "مكالمة واردة…"
        handler.postDelayed({ tryAnswer() }, ANSWER_DELAY)
    }

    private fun tryAnswer() {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            report("لم يتم الرد: صلاحية الرد على المكالمات غير ممنوحة")
            return
        }
        if (!AppState.greetingFile(this).exists()) {
            report("لم يتم الرد: لا يوجد تسجيل محفوظ")
            return
        }
        try {
            getSystemService(TelecomManager::class.java).acceptRingingCall()
            answeredByUs = true
            AppState.status.value = "جارٍ الرد…"
        } catch (e: SecurityException) {
            report("فشل الرد: ${e.message}")
        }
    }

    private fun onAnswered() {
        playStarted = true
        AppState.status.value = "تم الرد على المكالمة"
        handler.postDelayed({ startPlayback() }, PLAY_DELAY)
    }

    private fun startPlayback() {
        try {
            routeToSpeaker()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(AppState.greetingFile(this).absolutePath)
            mp.setOnCompletionListener {
                handler.postDelayed(
                    { finishCall("تم الرد وتشغيل التسجيل عبر المكبر ثم أُنهيت المكالمة") },
                    END_DELAY
                )
            }
            mp.setOnErrorListener { _, w, e ->
                finishCall("خطأ في التشغيل ($w/$e)")
                true
            }
            mp.prepare()
            mp.start()
            AppState.status.value = "يتم تشغيل التسجيل عبر المكبر"
        } catch (e: Exception) {
            finishCall("فشل التشغيل: ${e.message}")
        }
    }

    private fun finishCall(msg: String) {
        stopPlayer()
        val ended = try {
            @Suppress("DEPRECATION")
            getSystemService(TelecomManager::class.java).endCall()
        } catch (e: SecurityException) {
            false
        }
        report(if (ended) msg else "$msg (تعذر إنهاء المكالمة تلقائيًا)")
    }

    private fun onIdle() {
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        restoreAudio()
        if (inCall && !logged) {
            report(if (answeredByUs) "انتهت المكالمة قبل اكتمال التشغيل" else "انتهت المكالمة قبل الرد")
        }
        inCall = false
        answeredByUs = false
        playStarted = false
        AppState.status.value = "لا توجد مكالمة حاليًا"
    }

    // ---------- الصوت ----------
    @Suppress("DEPRECATION")
    private fun routeToSpeaker() {
        val am = getSystemService(AudioManager::class.java)
        speakerBefore = am.isSpeakerphoneOn
        audioChanged = true
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            }
        } catch (_: Exception) {
        }
        am.isSpeakerphoneOn = true
        try {
            am.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0
            )
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        if (!audioChanged) return
        audioChanged = false
        val am = getSystemService(AudioManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            am.isSpeakerphoneOn = speakerBefore
        } catch (_: Exception) {
        }
    }

    private fun stopPlayer() {
        player?.let {
            runCatching { it.setOnCompletionListener(null) }
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    private fun report(msg: String) {
        if (logged) return
        logged = true
        AppState.addLog(msg)
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_STOP = "com.homs.autoreply.STOP"
        private const val CHANNEL = "homs_auto_reply"
        private const val ANSWER_DELAY = 1500L
        private const val PLAY_DELAY = 800L
        private const val END_DELAY = 600L
    }
}
