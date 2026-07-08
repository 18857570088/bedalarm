package com.zclei.bedalarm.net

import com.zclei.bedalarm.data.BedConfig
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class CloudPressureLogicConfig(
    val patientType: String,
    val inBedThreshold: Int,
    val twoPointInBedThreshold: Int,
    val leftThreshold: Int,
    val leftConfirmPackets: Int,
)

data class CloudLowWeightBedSetting(
    val bedId: Long,
    val bedIndex: Int?,
    val bedLabel: String,
    val lowWeightPatient: Boolean,
)

data class CloudLeaveAlarmAcknowledgementSetting(
    val bedId: Long,
    val bedIndex: Int?,
    val bedLabel: String,
    val leaveAlarmAcknowledged: Boolean,
)

data class CloudDynamicPressureProfile(
    val bedId: Long,
    val bedIndex: Int?,
    val bedLabel: String,
    val emptyBaseline: List<Int>,
    val inBedTemplate: List<Int>,
    val baselineUpdateDate: String?,
    val templateUpdateDate: String?,
    val baselineUpdatedAt: String?,
    val templateUpdatedAt: String?,
    val updatedAt: String?,
)

class BedAlarmConfigClient(
    private val baseUrlProvider: () -> String,
) {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    suspend fun loadLowWeightSettings(
        hospitalId: Long,
        hospitalCode: String,
    ): List<CloudLowWeightBedSetting> {
        val url = endpoint("hospitals/${hospitalCode.urlEncode()}/low-weight").toHttpUrl()
            .newBuilder()
            .addQueryParameter("hospitalId", hospitalId.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return parseLowWeightSettings(JSONObject(execute(request)))
    }

    suspend fun saveLowWeightSettings(
        hospitalId: Long,
        hospitalCode: String,
        updatedByUserId: Long?,
        beds: List<BedConfig>,
    ): List<CloudLowWeightBedSetting> {
        val array = JSONArray()
        beds.forEach { bed ->
            array.put(
                JSONObject()
                    .put("bedId", bed.stableId)
                    .put("bedIndex", bed.bedIndex)
                    .put("bedLabel", bed.bedLabel.ifBlank { bed.bedIndex.toString() })
                    .put("lowWeightPatient", bed.lowWeightPatient),
            )
        }
        val json = JSONObject()
            .put("hospitalId", hospitalId)
            .put("hospitalCode", hospitalCode)
            .put("beds", array)
        updatedByUserId?.let { json.put("updatedByUserId", it) }
        val request = Request.Builder()
            .url(endpoint("hospitals/${hospitalCode.urlEncode()}/low-weight"))
            .put(json.toString().toJsonBody())
            .build()
        return parseLowWeightSettings(JSONObject(execute(request)))
    }

    suspend fun loadLeaveAlarmAcknowledgements(
        hospitalId: Long,
        hospitalCode: String,
    ): List<CloudLeaveAlarmAcknowledgementSetting> {
        val url = endpoint("hospitals/${hospitalCode.urlEncode()}/leave-alarm-ack").toHttpUrl()
            .newBuilder()
            .addQueryParameter("hospitalId", hospitalId.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return parseLeaveAlarmAcknowledgements(JSONObject(execute(request)))
    }

    suspend fun saveLeaveAlarmAcknowledgements(
        hospitalId: Long,
        hospitalCode: String,
        updatedByUserId: Long?,
        beds: List<BedConfig>,
    ): List<CloudLeaveAlarmAcknowledgementSetting> {
        val array = JSONArray()
        beds.forEach { bed ->
            array.put(
                JSONObject()
                    .put("bedId", bed.stableId)
                    .put("bedIndex", bed.bedIndex)
                    .put("bedLabel", bed.bedLabel.ifBlank { bed.bedIndex.toString() })
                    .put("leaveAlarmAcknowledged", bed.leaveAlarmAcknowledged),
            )
        }
        val json = JSONObject()
            .put("hospitalId", hospitalId)
            .put("hospitalCode", hospitalCode)
            .put("beds", array)
        updatedByUserId?.let { json.put("updatedByUserId", it) }
        val request = Request.Builder()
            .url(endpoint("hospitals/${hospitalCode.urlEncode()}/leave-alarm-ack"))
            .put(json.toString().toJsonBody())
            .build()
        return parseLeaveAlarmAcknowledgements(JSONObject(execute(request)))
    }

    suspend fun loadDynamicPressureProfiles(
        hospitalId: Long,
        hospitalCode: String,
    ): List<CloudDynamicPressureProfile> {
        val url = endpoint("hospitals/${hospitalCode.urlEncode()}/dynamic-pressure-profiles").toHttpUrl()
            .newBuilder()
            .addQueryParameter("hospitalId", hospitalId.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return parseDynamicPressureProfiles(JSONObject(execute(request)))
    }

    suspend fun saveDynamicPressureProfiles(
        hospitalId: Long,
        hospitalCode: String,
        updatedByUserId: Long?,
        profiles: List<CloudDynamicPressureProfile>,
    ): List<CloudDynamicPressureProfile> {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("bedId", profile.bedId)
                    .put("bedIndex", profile.bedIndex)
                    .put("bedLabel", profile.bedLabel)
                    .put("emptyBaseline", profile.emptyBaseline.toJsonArray())
                    .put("inBedTemplate", profile.inBedTemplate.toJsonArray())
                    .put("baselineUpdateDate", profile.baselineUpdateDate)
                    .put("templateUpdateDate", profile.templateUpdateDate)
                    .put("baselineUpdatedAt", profile.baselineUpdatedAt)
                    .put("templateUpdatedAt", profile.templateUpdatedAt),
            )
        }
        val json = JSONObject()
            .put("hospitalId", hospitalId)
            .put("hospitalCode", hospitalCode)
            .put("profiles", array)
        updatedByUserId?.let { json.put("updatedByUserId", it) }
        val request = Request.Builder()
            .url(endpoint("hospitals/${hospitalCode.urlEncode()}/dynamic-pressure-profiles"))
            .put(json.toString().toJsonBody())
            .build()
        return parseDynamicPressureProfiles(JSONObject(execute(request)))
    }

    suspend fun loadPressureLogicSettings(
        hospitalId: Long,
        hospitalCode: String,
    ): Map<String, CloudPressureLogicConfig> {
        val url = endpoint("hospitals/${hospitalCode.urlEncode()}/pressure-logic").toHttpUrl()
            .newBuilder()
            .addQueryParameter("hospitalId", hospitalId.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return parsePressureLogicSettings(JSONObject(execute(request)))
    }

    suspend fun savePressureLogicSettings(
        hospitalId: Long,
        hospitalCode: String,
        updatedByUserId: Long?,
        configs: List<CloudPressureLogicConfig>,
    ): Map<String, CloudPressureLogicConfig> {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(
                JSONObject()
                    .put("patientType", config.patientType.trim().uppercase())
                    .put("inBedThreshold", config.inBedThreshold)
                    .put("twoPointInBedThreshold", config.twoPointInBedThreshold)
                    .put("leftThreshold", config.leftThreshold)
                    .put("leftConfirmPackets", config.leftConfirmPackets),
            )
        }
        val json = JSONObject()
            .put("hospitalId", hospitalId)
            .put("hospitalCode", hospitalCode)
            .put("configs", array)
        updatedByUserId?.let { json.put("updatedByUserId", it) }
        val request = Request.Builder()
            .url(endpoint("hospitals/${hospitalCode.urlEncode()}/pressure-logic"))
            .put(json.toString().toJsonBody())
            .build()
        return parsePressureLogicSettings(JSONObject(execute(request)))
    }

    private fun parseLowWeightSettings(json: JSONObject): List<CloudLowWeightBedSetting> {
        val beds = json.optJSONArray("beds") ?: JSONArray()
        return buildList {
            for (index in 0 until beds.length()) {
                val bed = beds.optJSONObject(index) ?: continue
                val bedId = bed.optLong("bedId", 0L)
                if (bedId <= 0L) continue
                add(
                    CloudLowWeightBedSetting(
                        bedId = bedId,
                        bedIndex = if (bed.has("bedIndex") && !bed.isNull("bedIndex")) bed.optInt("bedIndex") else null,
                        bedLabel = bed.optString("bedLabel"),
                        lowWeightPatient = bed.optBoolean("lowWeightPatient", false),
                    ),
                )
            }
        }
    }

    private fun parseLeaveAlarmAcknowledgements(json: JSONObject): List<CloudLeaveAlarmAcknowledgementSetting> {
        val beds = json.optJSONArray("beds") ?: JSONArray()
        return buildList {
            for (index in 0 until beds.length()) {
                val bed = beds.optJSONObject(index) ?: continue
                val bedId = bed.optLong("bedId", 0L)
                if (bedId <= 0L) continue
                add(
                    CloudLeaveAlarmAcknowledgementSetting(
                        bedId = bedId,
                        bedIndex = if (bed.has("bedIndex") && !bed.isNull("bedIndex")) bed.optInt("bedIndex") else null,
                        bedLabel = bed.optString("bedLabel"),
                        leaveAlarmAcknowledged = bed.optBoolean("leaveAlarmAcknowledged", false),
                    ),
                )
            }
        }
    }

    private fun parseDynamicPressureProfiles(json: JSONObject): List<CloudDynamicPressureProfile> {
        val profiles = json.optJSONArray("profiles") ?: JSONArray()
        return buildList {
            for (index in 0 until profiles.length()) {
                val item = profiles.optJSONObject(index) ?: continue
                val bedId = item.optLong("bedId", 0L)
                if (bedId <= 0L) continue
                add(
                    CloudDynamicPressureProfile(
                        bedId = bedId,
                        bedIndex = if (item.has("bedIndex") && !item.isNull("bedIndex")) item.optInt("bedIndex") else null,
                        bedLabel = item.optString("bedLabel"),
                        emptyBaseline = item.optJSONArray("emptyBaseline").toIntList4(),
                        inBedTemplate = item.optJSONArray("inBedTemplate").toIntList4(),
                        baselineUpdateDate = item.optStringOrNull("baselineUpdateDate"),
                        templateUpdateDate = item.optStringOrNull("templateUpdateDate"),
                        baselineUpdatedAt = item.optStringOrNull("baselineUpdatedAt"),
                        templateUpdatedAt = item.optStringOrNull("templateUpdatedAt"),
                        updatedAt = item.optStringOrNull("updatedAt"),
                    ),
                )
            }
        }
    }

    private fun parsePressureLogicSettings(json: JSONObject): Map<String, CloudPressureLogicConfig> {
        val configs = json.optJSONArray("configs") ?: JSONArray()
        return buildMap {
            for (index in 0 until configs.length()) {
                val item = configs.optJSONObject(index) ?: continue
                val patientType = item.optString("patientType").trim().uppercase()
                if (patientType.isBlank()) continue
                put(
                    patientType,
                    CloudPressureLogicConfig(
                        patientType = patientType,
                        inBedThreshold = item.optInt("inBedThreshold"),
                        twoPointInBedThreshold = item.optInt("twoPointInBedThreshold"),
                        leftThreshold = item.optInt("leftThreshold"),
                        leftConfirmPackets = item.optInt("leftConfirmPackets"),
                    ),
                )
            }
        }
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val summary = body.take(240).ifBlank { response.message }
                throw IOException("HTTP ${response.code}: $summary")
            }
            return body
        }
    }

    private fun endpoint(path: String): String {
        val base = baseUrlProvider().trim().ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        return "$base/${path.trimStart('/')}"
    }

    private fun String.toJsonBody() =
        toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun List<Int>.toJsonArray(): JSONArray {
        val array = JSONArray()
        take(4).forEach { array.put(it) }
        return array
    }

    private fun JSONArray?.toIntList4(): List<Int> {
        if (this == null || length() < 4) return emptyList()
        return List(4) { index -> optInt(index) }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    companion object {
        const val DEFAULT_BASE_URL = "https://bedalarm.86086.cn/bedalarm-config/api"
    }
}
