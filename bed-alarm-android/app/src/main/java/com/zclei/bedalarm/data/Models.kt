package com.zclei.bedalarm.data

data class AuthUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: String,
    val hospitalIds: List<Long>,
)

data class LoginResult(
    val token: String,
    val user: AuthUser,
)

data class Hospital(
    val id: Long,
    val name: String,
    val code: String,
    val sortOrder: Int,
)

data class HospitalDetail(
    val id: Long,
    val name: String,
    val code: String,
    val sortOrder: Int,
    val beds: List<BedConfig>,
)

data class BedConfig(
    val id: Long,
    val bedIndex: Int,
    val bedLabel: String,
    val patientName: String,
    val fallRiskLevel: String,
    val department: String,
    val boundGatewayId: Int,
    val boundSensorId: Int,
    val heartRateAlarmMin: Int,
    val heartRateAlarmMax: Int,
    val breathRateAlarmMin: Int,
    val breathRateAlarmMax: Int,
    val leaveBedAlarm: Boolean,
    val leaveBedAlarmStartMinutes: Int,
    val leaveBedAlarmEndMinutes: Int,
    val lowWeightPatient: Boolean,
    val leaveAlarmAcknowledged: Boolean,
    val lowWeightPatientFieldPresent: Boolean = true,
    val leaveAlarmAcknowledgedFieldPresent: Boolean = true,
) {
    val stableId: Long
        get() = if (id != 0L) id else bedIndex.toLong()
}

data class ParsedFrame(
    val protocol: String,
    val gatewayId: Int,
    val sensorId: Int,
    val heartRate: Int,
    val respiration: Int,
    val posture: Int,
    val pressure28: List<Int>,
    val temperature: List<Int>,
    val showTemperature: Boolean,
    val heartRateWave: List<Int>,
    val sequenceId: Int,
    val occupancyPresent: Boolean? = null,
    val adcUint16: List<Int> = emptyList(),
)

data class BedRuntime(
    var heartRate: Int = 0,
    var breathRate: Int = 0,
    var posture: Int = 0,
    var protocol: String = "",
    var pressure28: List<Int> = emptyList(),
    var temperature: List<Int> = emptyList(),
    var showTemperature: Boolean = false,
    var heartRateWave: List<Int> = emptyList(),
    var sequenceId: Int = 0,
    var lastOccupancySequenceId: Int? = null,
    var occupancyPresent: Boolean? = null,
    var pressurePresence: Boolean? = null,
    var pressureLowPacketCount: Int = 0,
    var lastMqttTime: Long? = null,
    var statusKey: String = "unknown",
    var statusSince: Long = System.currentTimeMillis(),
    var leaveAlarmSpoken: Boolean = false,
    var leaveAlarmAcknowledged: Boolean = false,
    var lastVitalAlarmAt: Long = 0L,
    var lastAlarmText: String? = null,
)
