package com.zclei.bedalarm.speech

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlarmSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tts = TextToSpeech(appContext, this)
    private val leaveAlarms = linkedMapOf<Long, LeaveAlarm>()
    private var leaveAlarmJob: Job? = null
    private var leaveAlarmSessionStartAt = 0L
    private var leaveAlarmSessionEndAt = 0L
    private var leaveAlarmBaseDurationMs = 10_000L
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.CHINESE
            tts.setSpeechRate(1.0f)
            tts.setPitch(1.0f)
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "alarm-${System.nanoTime()}")
    }

    fun speakLeaveAlarmForDuration(key: Long, text: String, durationMs: Long) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (leaveAlarms.isEmpty()) {
            leaveAlarmSessionStartAt = now
        }
        leaveAlarmBaseDurationMs = durationMs
        leaveAlarms[key] = LeaveAlarm(key, text)
        leaveAlarmSessionEndAt = leaveAlarmSessionStartAt + leaveAlarms.size * leaveAlarmBaseDurationMs
        startLeaveAlarmQueueIfNeeded()
    }

    private fun startLeaveAlarmQueueIfNeeded() {
        if (leaveAlarmJob?.isActive == true) return
        leaveAlarmJob = scope.launch {
            try {
                while (leaveAlarms.isNotEmpty() && System.currentTimeMillis() < leaveAlarmSessionEndAt) {
                    val alarms = leaveAlarms.values.toList()
                    for (alarm in alarms) {
                        if (!leaveAlarms.containsKey(alarm.key) || System.currentTimeMillis() >= leaveAlarmSessionEndAt) {
                            continue
                        }
                        if (ready) {
                            tts.speak(alarm.text, TextToSpeech.QUEUE_ADD, null, "leave-${alarm.key}-${System.nanoTime()}")
                        }
                        delay(leaveAlarmRepeatDelay(alarm.text))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                leaveAlarms.clear()
                leaveAlarmJob = null
            }
        }
    }

    fun stopLeaveAlarm(key: Long) {
        if (leaveAlarms.remove(key) == null) return
        if (leaveAlarms.isEmpty()) {
            leaveAlarmJob?.cancel()
            leaveAlarmJob = null
            tts.stop()
        } else {
            leaveAlarmSessionEndAt = leaveAlarmSessionStartAt + leaveAlarms.size * leaveAlarmBaseDurationMs
        }
    }

    fun stopAllLeaveAlarms() {
        leaveAlarmJob?.cancel()
        leaveAlarmJob = null
        leaveAlarms.clear()
        tts.stop()
    }

    fun vibrateAlarm() {
        val pattern = longArrayOf(0, 200, 100, 200)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    fun shutdown() {
        stopAllLeaveAlarms()
        tts.stop()
        tts.shutdown()
    }

    private fun leaveAlarmRepeatDelay(text: String): Long {
        return (text.length * 320L + 1_200L).coerceAtLeast(3_000L)
    }

    private data class LeaveAlarm(
        val key: Long,
        val text: String,
    )
}
