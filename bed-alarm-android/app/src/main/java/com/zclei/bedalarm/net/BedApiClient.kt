package com.zclei.bedalarm.net

import com.zclei.bedalarm.data.AuthUser
import com.zclei.bedalarm.data.BedConfig
import com.zclei.bedalarm.data.Hospital
import com.zclei.bedalarm.data.HospitalDetail
import com.zclei.bedalarm.data.LoginResult
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

class BedApiClient(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?,
) {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    suspend fun login(username: String, password: String): LoginResult {
        val json = JSONObject()
            .put("username", username)
            .put("password", password)
        val request = Request.Builder()
            .url(endpoint("auth/login"))
            .post(json.toString().toJsonBody())
            .build()
        return parseLogin(JSONObject(execute(request)))
    }

    suspend fun register(username: String, password: String, displayName: String): LoginResult {
        val json = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("displayName", displayName)
        val request = Request.Builder()
            .url(endpoint("auth/register"))
            .post(json.toString().toJsonBody())
            .build()
        return parseLogin(JSONObject(execute(request)))
    }

    suspend fun me(): AuthUser {
        val request = Request.Builder()
            .url(endpoint("users/me"))
            .get()
            .withBearer()
            .build()
        return parseUser(JSONObject(execute(request)))
    }

    suspend fun hospitals(): List<Hospital> {
        val request = Request.Builder()
            .url(endpoint("hospitals"))
            .get()
            .withBearer()
            .build()
        val array = JSONArray(execute(request))
        return List(array.length()) { index -> parseHospital(array.getJSONObject(index)) }
            .sortedWith(compareBy<Hospital> { it.sortOrder }.thenBy { it.name })
    }

    suspend fun hospitalByCode(code: String): HospitalDetail {
        val request = Request.Builder()
            .url(endpoint("hospitals/by-code/${code.urlEncode()}"))
            .get()
            .withBearer()
            .build()
        return parseHospitalDetail(JSONObject(execute(request)))
    }

    suspend fun saveBeds(hospitalId: Long, beds: List<BedConfig>) {
        val array = JSONArray()
        beds.forEach { bed -> array.put(bed.toJson()) }
        val request = Request.Builder()
            .url(endpoint("hospitals/$hospitalId/beds"))
            .put(array.toString().toJsonBody())
            .withBearer()
            .build()
        execute(request)
    }

    suspend fun deviceHistory(
        deviceNumber: String,
        startTime: String,
        endTime: String,
        limit: Int = 50,
        offset: Int = 0,
        sortOrder: String = "DESC",
    ): String {
        val url = endpoint("devices/${deviceNumber.urlEncode()}").toHttpUrl()
            .newBuilder()
            .addQueryParameter("start_time", startTime)
            .addQueryParameter("end_time", endTime)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("sort_order", sortOrder)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .withBearer()
            .build()
        return execute(request).prettyJson()
    }

    suspend fun sleepAnalysis(
        deviceNumber: String,
        startTime: String,
        endTime: String,
    ): String {
        val url = endpoint("sleep/analysis").toHttpUrl()
            .newBuilder()
            .addQueryParameter("deviceNumber", deviceNumber)
            .addQueryParameter("startTime", startTime)
            .addQueryParameter("endTime", endTime)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .withBearer()
            .build()
        return execute(request).prettyJson()
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

    private fun parseLogin(json: JSONObject): LoginResult {
        return LoginResult(
            token = json.optString("token"),
            user = parseUser(json.getJSONObject("user")),
        )
    }

    private fun parseUser(json: JSONObject): AuthUser {
        val ids = json.optJSONArray("hospitalIds") ?: JSONArray()
        return AuthUser(
            id = json.optLong("id", 0L),
            username = json.optString("username"),
            displayName = json.optString("displayName"),
            role = json.optString("role"),
            hospitalIds = List(ids.length()) { index -> ids.optLong(index) },
        )
    }

    private fun parseHospital(json: JSONObject): Hospital {
        return Hospital(
            id = json.optLong("id", 0L),
            name = json.optString("name"),
            code = json.optString("code"),
            sortOrder = json.optInt("sortOrder", 0),
        )
    }

    private fun parseHospitalDetail(json: JSONObject): HospitalDetail {
        val bedsJson = json.optJSONArray("beds") ?: JSONArray()
        return HospitalDetail(
            id = json.optLong("id", 0L),
            name = json.optString("name"),
            code = json.optString("code"),
            sortOrder = json.optInt("sortOrder", 0),
            beds = List(bedsJson.length()) { index -> parseBed(bedsJson.getJSONObject(index)) }
                .sortedBy { it.bedIndex },
        )
    }

    private fun parseBed(json: JSONObject): BedConfig {
        return BedConfig(
            id = json.optLong("id", 0L),
            bedIndex = json.optInt("bedIndex", 0),
            bedLabel = json.optString("bedLabel"),
            patientName = json.optString("patientName"),
            fallRiskLevel = json.optString("fallRiskLevel", "level_1"),
            department = json.optString("department"),
            boundGatewayId = json.optInt("boundGatewayId", 0),
            boundSensorId = json.optInt("boundSensorId", 0),
            heartRateAlarmMin = json.optInt("heartRateAlarmMin", 50),
            heartRateAlarmMax = json.optInt("heartRateAlarmMax", 120),
            breathRateAlarmMin = json.optInt("breathRateAlarmMin", 8),
            breathRateAlarmMax = json.optInt("breathRateAlarmMax", 30),
            leaveBedAlarm = json.optBoolean("leaveBedAlarm", true),
            leaveBedAlarmStartMinutes = json.optInt("leaveBedAlarmStartMinutes", 1320),
            leaveBedAlarmEndMinutes = json.optInt("leaveBedAlarmEndMinutes", 480),
            lowWeightPatient = json.optBoolean("lowWeightPatient", false),
            leaveAlarmAcknowledged = json.optBoolean("leaveAlarmAcknowledged", false),
            lowWeightPatientFieldPresent = json.has("lowWeightPatient"),
            leaveAlarmAcknowledgedFieldPresent = json.has("leaveAlarmAcknowledged"),
        )
    }

    private fun BedConfig.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("bedIndex", bedIndex)
            .put("bedLabel", bedLabel)
            .put("patientName", patientName)
            .put("fallRiskLevel", fallRiskLevel)
            .put("department", department)
            .put("boundGatewayId", boundGatewayId)
            .put("boundSensorId", boundSensorId)
            .put("heartRateAlarmMin", heartRateAlarmMin)
            .put("heartRateAlarmMax", heartRateAlarmMax)
            .put("breathRateAlarmMin", breathRateAlarmMin)
            .put("breathRateAlarmMax", breathRateAlarmMax)
            .put("leaveBedAlarm", leaveBedAlarm)
            .put("leaveBedAlarmStartMinutes", leaveBedAlarmStartMinutes)
            .put("leaveBedAlarmEndMinutes", leaveBedAlarmEndMinutes)
            .put("lowWeightPatient", lowWeightPatient)
            .put("leaveAlarmAcknowledged", leaveAlarmAcknowledged)
    }

    private fun Request.Builder.withBearer(): Request.Builder {
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            addHeader("Authorization", "Bearer $token")
        }
        return addHeader("Content-Type", "application/json")
    }

    private fun endpoint(path: String): String {
        val base = baseUrlProvider().trim().ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        return "$base/${path.trimStart('/')}"
    }

    private fun String.toJsonBody() =
        toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun String.prettyJson(): String {
        return try {
            when (firstOrNull { !it.isWhitespace() }) {
                '{' -> JSONObject(this).toString(2)
                '[' -> JSONArray(this).toString(2)
                else -> this
            }
        } catch (_: Exception) {
            this
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.86086.cn/api"
    }
}
