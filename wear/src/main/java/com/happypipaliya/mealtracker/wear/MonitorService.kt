package com.happypipaliya.mealtracker.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.happypipaliya.sleepcore.EPOCH_MS
import com.happypipaliya.sleepcore.Epoch
import com.happypipaliya.sleepcore.NightRecord
import com.happypipaliya.sleepcore.WakeDecider
import com.happypipaliya.sleepcore.BASELINE_MINUTES
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "SmartAlarm"

/**
 * Runs from 20 minutes before the wake window until the alarm: reads movement and heart rate,
 * scores each 30-second epoch, and rings as soon as it looks like light sleep (or at the window end).
 */
class MonitorService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var decider: WakeDecider? = null

    private var monitorStart = 0L
    private var windowStart = 0L
    private var windowEnd = 0L
    private var epochStart = 0L
    private val epochs = ArrayList<Epoch>()
    private val scores = ArrayList<Double>()

    // Raw sensor sums for the current epoch.
    private var moveSum = 0.0
    private var moveCount = 0
    private val heartRates = ArrayList<Double>()

    private var monitoring = false
    private var ringing = false
    private var ringingNight = 0L
    private var ringtone: Ringtone? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        when (intent?.action) {
            ACTION_MONITOR -> {
                startInForeground(statusNotification("Watching for light sleep…"))
                if (!monitoring && !ringing) startMonitoring(
                    intent.getLongExtra(Scheduler.EXTRA_WINDOW_START, 0),
                    intent.getLongExtra(Scheduler.EXTRA_WINDOW_END, 0),
                )
            }
            ACTION_BACKSTOP -> {
                startInForeground(statusNotification("Wake-up time"))
                // End of the window: ring unless we already did.
                if (!ringing) {
                    if (!monitoring) {
                        windowStart = intent.getLongExtra(Scheduler.EXTRA_WINDOW_START, 0)
                        windowEnd = intent.getLongExtra(Scheduler.EXTRA_WINDOW_END, 0)
                        monitorStart = windowStart - BASELINE_MINUTES * 60_000
                    }
                    ring("window_end")
                } else Unit
            }
            ACTION_TEST -> {
                startInForeground(statusNotification("Test alarm"))
                if (!ringing) {
                    val now = System.currentTimeMillis()
                    monitorStart = now; windowStart = now; windowEnd = now
                    handler.postDelayed({ ring("test") }, 3_000)
                }
            }
            ACTION_STOP -> stopRinging()
            else -> if (!monitoring && !ringing) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(windowStart: Long, windowEnd: Long) {
        val now = System.currentTimeMillis()
        if (windowEnd <= now) { stopSelf(); return }
        this.windowStart = windowStart
        this.windowEnd = windowEnd
        monitorStart = now
        epochStart = now
        decider = WakeDecider(WatchPrefs(this).model, windowStart)
        monitoring = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MealTracker:SmartAlarm")
            .apply { acquire(windowEnd - now + 15 * 60_000) }
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 40_000, 5_000_000) }
        // Prefer a continuous heart-rate sensor over Samsung's batched one, so readings land in the right epoch.
        val heartSensors = sm.getSensorList(Sensor.TYPE_HEART_RATE)
        val hr = heartSensors.firstOrNull { !it.name.contains("batch", ignoreCase = true) } ?: heartSensors.firstOrNull()
        if (hr != null && checkSelfPermission(heartRatePermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sm.registerListener(this, hr, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Heart rate from ${hr.name}")
        } else Log.w(TAG, "No heart-rate access; using movement only")
        handler.postDelayed(::closeEpoch, EPOCH_MS)
        Log.i(TAG, "Monitoring until ${java.util.Date(windowEnd)}")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                moveSum += abs(sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH)
                moveCount++
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values[0].toDouble()
                if (bpm in 30.0..220.0 && event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW) heartRates += bpm
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Every 30 s: summarise the epoch, score it, and decide whether to ring. */
    private fun closeEpoch() {
        if (!monitoring) return
        val hrMean = heartRates.takeIf { it.isNotEmpty() }?.average()
        val hrSd = hrMean?.let { m -> sqrt(heartRates.sumOf { (it - m) * (it - m) } / heartRates.size) }
        val epoch = Epoch(epochStart, if (moveCount > 0) moveSum / moveCount else 0.0, hrMean, hrSd)
        moveSum = 0.0; moveCount = 0; heartRates.clear()
        epochStart += EPOCH_MS

        val now = System.currentTimeMillis()
        val (score, ringNow) = decider!!.add(epoch, inWindow = epoch.start + EPOCH_MS >= windowStart)
        epochs += epoch
        scores += score
        when {
            ringNow -> ring("smart")
            now >= windowEnd -> ring("window_end")
            else -> handler.postDelayed(::closeEpoch, EPOCH_MS)
        }
    }

    private fun ring(trigger: String) {
        if (ringing) return
        ringing = true
        monitoring = false
        handler.removeCallbacksAndMessages(null)
        getSystemService(SensorManager::class.java).unregisterListener(this)

        val now = System.currentTimeMillis()
        val record = NightRecord(
            monitorStart = monitorStart, windowStart = windowStart, windowEnd = windowEnd, alarmAt = now,
            trigger = trigger, grogginess = null, modelVersion = WatchPrefs(this).model?.version ?: 0,
            epochs = epochs.toList(), scores = scores.toList(),
        )
        WatchPrefs(this).saveNight(record)
        ringingNight = record.monitorStart

        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java)
                .putExtra(AlarmActivity.EXTRA_NIGHT, record.monitorStart)
                .putExtra(AlarmActivity.EXTRA_TRIGGER, trigger)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Good morning")
            .setContentText(reason(trigger))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        startInForeground(alarm)

        // Gentle at first: vibration only, then sound after a minute.
        val pattern = longArrayOf(0, 500, 500, 500, 1500)
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        val effect = VibrationEffect.createWaveform(pattern, 0)
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else @Suppress("DEPRECATION") vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        handler.postDelayed({
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                runCatching { play() }
            }
        }, 60_000)
        handler.postDelayed(::stopRinging, 10 * 60_000) // give up after 10 minutes

        runCatching { startActivity(Intent(this, AlarmActivity::class.java)
            .putExtra(AlarmActivity.EXTRA_NIGHT, record.monitorStart)
            .putExtra(AlarmActivity.EXTRA_TRIGGER, trigger)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Log.i(TAG, "Ringing ($trigger)")
    }

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        ringtone?.stop()
        if (ringing && ringingNight != 0L) {
            // The alarm screen asks for grogginess; if it was stopped from the notification, still offer it.
            getSystemService(NotificationManager::class.java).notify(
                NOTIFY_RATE,
                NotificationCompat.Builder(this, CHANNEL_STATUS)
                    .setSmallIcon(R.drawable.ic_stat_alarm)
                    .setContentTitle("How groggy are you?")
                    .setContentText("Tap to rate with the bezel")
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(
                        this, 3,
                        Intent(this, AlarmActivity::class.java)
                            .putExtra(AlarmActivity.EXTRA_NIGHT, ringingNight)
                            .putExtra(AlarmActivity.EXTRA_RATE_ONLY, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ))
                    .build(),
            )
        }
        ringing = false
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Scheduler.reschedule(this) // tomorrow's window
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFY_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        else startForeground(NOTIFY_ONGOING, n)
    }

    private fun statusNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setContentTitle("Smart alarm")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALARM, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // the service controls vibration and sound itself
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Sleep sensing", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_MONITOR = "monitor"
        const val ACTION_BACKSTOP = "backstop"
        const val ACTION_TEST = "test"
        const val ACTION_STOP = "stop"
        private const val CHANNEL_ALARM = "alarm"
        private const val CHANNEL_STATUS = "status"
        private const val NOTIFY_ONGOING = 1
        private const val NOTIFY_RATE = 2

        fun reason(trigger: String) = when (trigger) {
            "smart" -> "You seem to be in light sleep"
            "window_end" -> "End of your wake window"
            else -> "Test alarm"
        }
    }
}
