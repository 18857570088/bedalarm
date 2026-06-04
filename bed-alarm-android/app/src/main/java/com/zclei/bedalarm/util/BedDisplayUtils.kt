package com.zclei.bedalarm.util

import com.zclei.bedalarm.data.BedConfig
import com.zclei.bedalarm.data.BedRuntime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object BedDisplayUtils {
    fun formatDesensitizedName(name: String): String {
        val chars = name.trim().toList()
        return when (chars.size) {
            0 -> "--"
            1 -> "*"
            2 -> "${chars[0]}*"
            3 -> "${chars[0]}*${chars[2]}"
            else -> "${chars.first()}${"*".repeat(chars.size - 2)}${chars.last()}"
        }
    }

    fun riskLevelName(level: String): String {
        return when (level.lowercase()) {
            "level_2", "medium" -> "重点防跌"
            "level_3", "high" -> "高危防跌"
            else -> "一般防跌"
        }
    }

    fun riskLevelColor(level: String): Int {
        return when (level.lowercase()) {
            "level_2", "medium" -> 0xFFB7791F.toInt()
            "level_3", "high" -> 0xFFC2410C.toInt()
            else -> 0xFF2563EB.toInt()
        }
    }

    fun statusKey(runtime: BedRuntime): String {
        if (runtime.protocol == "occupancy4pressure") {
            runtime.pressurePresence?.let { present ->
                return if (present) "in_bed" else "left"
            }
            return "unknown"
        }
        runtime.occupancyPresent?.let { present ->
            return if (present) "in_bed" else "left"
        }
        return if (runtime.posture > 0) "in_bed" else "left"
    }

    fun postureLabel(runtime: BedRuntime): String {
        runtime.occupancyPresent?.let { present ->
            return if (present) "在床" else "离床"
        }
        return when (runtime.posture) {
            0 -> "无人"
            1 -> "仰卧"
            2 -> "左侧卧"
            3 -> "右侧卧"
            4 -> "体动"
            5, 6 -> "体位异常"
            7 -> "设备异常"
            else -> "--"
        }
    }

    fun statusText(runtime: BedRuntime, now: Long): String {
        val label = if (statusKey(runtime) == "in_bed") "在床" else "离床"
        return "$label ${formatDuration(now - runtime.statusSince)}"
    }

    fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs.coerceAtLeast(0L) / 1000).toInt()
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "${hours}时${minutes}分"
        } else {
            "${minutes}分${secs}秒"
        }
    }

    fun isBound(bed: BedConfig): Boolean {
        return bed.boundGatewayId != 0 || bed.boundSensorId != 0
    }

    fun isOffline(bed: BedConfig, runtime: BedRuntime, now: Long): Boolean {
        if (!isBound(bed)) return false
        val lastTime = runtime.lastMqttTime ?: return true
        return now - lastTime > 60_000
    }

    fun deviceHexLabel(bed: BedConfig): String {
        if (!isBound(bed)) return "未绑定"
        return "0x${bed.boundGatewayId.toString(16).uppercase().padStart(4, '0')}-" +
            "0x${bed.boundSensorId.toString(16).uppercase().padStart(4, '0')}"
    }

    fun deviceNumber(bed: BedConfig): String {
        return "6978864830016${bed.boundGatewayId.toString().padStart(6, '0')}" +
            "_6978864830023${bed.boundSensorId.toString().padStart(6, '0')}"
    }

    fun temperatureLabel(runtime: BedRuntime): String {
        if (!runtime.showTemperature || runtime.temperature.isEmpty()) return "--"
        val valid = runtime.temperature.filter { it > 0 }
        if (valid.isEmpty()) return "--"
        val avg = valid.average() / 10.0
        return String.format(Locale.CHINA, "%.1f℃", avg)
    }

    fun lastMqttLabel(runtime: BedRuntime): String {
        val time = runtime.lastMqttTime ?: return "--"
        return SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(time))
    }

    fun isWithinLeaveBedAlarmWindow(bed: BedConfig, now: Calendar = Calendar.getInstance()): Boolean {
        val start = bed.leaveBedAlarmStartMinutes.coerceIn(0, 1439)
        val end = bed.leaveBedAlarmEndMinutes.coerceIn(0, 1439)
        if (start == end) return true
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (start < end) {
            current in start until end
        } else {
            current >= start || current < end
        }
    }

    fun buildLeaveBedSpeech(bed: BedConfig): String {
        val bedPart = formatBedSpeechPart(bed)
        return when (bed.fallRiskLevel.lowercase()) {
            "level_2", "medium" -> "${bedPart}重点防跌患者离床，请护士查看。"
            "level_3", "high" -> "${bedPart}高危防跌患者离床，请立即处理。"
            else -> "${bedPart}患者离床。"
        }
    }

    fun buildVitalAlarmText(bed: BedConfig, runtime: BedRuntime): String? {
        val issues = mutableListOf<String>()
        if (runtime.heartRate > 0 && runtime.heartRate < bed.heartRateAlarmMin) {
            issues += "心率过低，当前${runtime.heartRate}次每分"
        }
        if (runtime.heartRate > 0 && runtime.heartRate > bed.heartRateAlarmMax) {
            issues += "心率过高，当前${runtime.heartRate}次每分"
        }
        if (runtime.breathRate > 0 && runtime.breathRate < bed.breathRateAlarmMin) {
            issues += "呼吸过低，当前${runtime.breathRate}次每分"
        }
        if (runtime.breathRate > 0 && runtime.breathRate > bed.breathRateAlarmMax) {
            issues += "呼吸过高，当前${runtime.breathRate}次每分"
        }
        if (issues.isEmpty()) return null

        val name = formatDesensitizedName(bed.patientName).takeIf { it != "--" }.orEmpty()
        return "${formatBedSpeechPart(bed)}${name}${issues.joinToString("，")}，请留意"
    }

    fun displayHeart(rawHeartRate: Int): Int = (rawHeartRate / 10.0).roundToInt()

    fun displayBreath(rawRespiration: Int): Int = (rawRespiration / 10.0).roundToInt()

    fun formatApiDate(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))
    }

    private fun formatBedSpeechPart(bed: BedConfig): String {
        val label = bed.bedLabel.trim().ifBlank { bed.bedIndex.toString() }
        return when {
            label.endsWith("床") -> label
            label.endsWith("号") -> "${label}床"
            else -> "${label}号床"
        }
    }
}
