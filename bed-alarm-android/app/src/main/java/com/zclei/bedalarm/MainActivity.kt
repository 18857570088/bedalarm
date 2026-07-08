package com.zclei.bedalarm

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.zclei.bedalarm.data.AuthUser
import com.zclei.bedalarm.data.BedConfig
import com.zclei.bedalarm.data.BedRuntime
import com.zclei.bedalarm.data.HospitalDetail
import com.zclei.bedalarm.data.ParsedFrame
import com.zclei.bedalarm.net.BedAlarmConfigClient
import com.zclei.bedalarm.net.CloudDynamicPressureProfile
import com.zclei.bedalarm.net.CloudLeaveAlarmAcknowledgementSetting
import com.zclei.bedalarm.net.CloudLowWeightBedSetting
import com.zclei.bedalarm.net.CloudPressureLogicConfig
import com.zclei.bedalarm.mqtt.BedMqttClient
import com.zclei.bedalarm.net.BedApiClient
import com.zclei.bedalarm.speech.AlarmSpeaker
import com.zclei.bedalarm.util.BedDisplayUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var api: BedApiClient
    private lateinit var configApi: BedAlarmConfigClient
    private lateinit var speaker: AlarmSpeaker

    private var token: String? = null
    private var currentUser: AuthUser? = null
    private var currentHospital: HospitalDetail? = null
    private var mqttClient: BedMqttClient? = null
    private var tickerJob: Job? = null
    private var mqttRetryJob: Job? = null
    private var configSyncJob: Job? = null
    private var bedConfigWriteInProgress = false
    private var showingSettings = false
    private var showingPressureLogicSettings = false

    private val beds = mutableListOf<BedConfig>()
    private val runtimes = linkedMapOf<Long, BedRuntime>()
    private val dynamicPressureProfiles = linkedMapOf<Long, DynamicPressureProfile>()
    private val dynamicPressureSampling = linkedMapOf<Long, PressureProfileSamplingState>()
    private val dynamicPressureWriteInProgress = mutableSetOf<Long>()

    private var bedGridLayout: GridLayout? = null
    private var inBedCountText: TextView? = null
    private var alertCountText: TextView? = null
    private var acknowledgedCountText: TextView? = null
    private var longAbsenceCountText: TextView? = null
    private var systemStatusText: TextView? = null
    private var mqttStatusText: TextView? = null
    private var settingsStatusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("bed_alarm", MODE_PRIVATE)
        migratePressureLogicSettings()
        token = prefs.getString(KEY_TOKEN, null)
        api = BedApiClient(
            baseUrlProvider = { apiBaseUrl },
            tokenProvider = { token },
        )
        configApi = BedAlarmConfigClient(
            baseUrlProvider = { bedAlarmConfigApiBaseUrl },
        )
        speaker = AlarmSpeaker(this)

        if (token.isNullOrBlank()) {
            showLogin()
        } else {
            enterMain()
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        mqttRetryJob?.cancel()
        configSyncJob?.cancel()
        mqttClient?.disconnect()
        speaker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Use OnBackPressedDispatcher for new screens.")
    override fun onBackPressed() {
        val detail = currentHospital
        if (showingPressureLogicSettings) {
            showSettings()
            return
        }
        if (showingSettings && detail != null) {
            showDashboard(detail, resetRuntime = false)
            return
        }
        super.onBackPressed()
    }

    private fun showLogin(message: String = "") {
        tickerJob?.cancel()
        mqttRetryJob?.cancel()
        configSyncJob?.cancel()
        mqttClient?.disconnect()
        currentHospital = null
        showingSettings = false
        showingPressureLogicSettings = false
        bedGridLayout = null

        val root = screenRoot()
        val content = paddedColumn(24)
        root.addView(content)

        content.addView(bigTitle("离床报警"))
        content.addView(subtitle("请输入用户名与密码"))

        val username = input("用户名", prefString(KEY_LAST_USERNAME, defaultLoginUsername.ifBlank { "admin" }))
        val password = input("密码", "", secret = true)
        val status = smallText(message, 0xFFB91C1C.toInt())

        listOf(username, password).forEach {
            content.addView(it.first)
            content.addView(it.second)
        }

        val loginButton = primaryButton("登录")
        loginButton.setOnClickListener {
            status.text = "登录中..."
            loginButton.isEnabled = false
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        api.login(username.second.text.toString().trim(), password.second.text.toString())
                    }
                    token = result.token
                    currentUser = result.user
                    prefs.edit()
                        .putString(KEY_TOKEN, result.token)
                        .putString(KEY_LAST_USERNAME, username.second.text.toString().trim())
                        .apply()
                    enterMain()
                } catch (error: Exception) {
                    status.text = "登录失败：${error.message.orEmpty()}"
                    loginButton.isEnabled = true
                }
            }
        }

        content.addView(loginButton)
        content.addView(status)
        setContentView(root)
    }

    private fun enterMain() {
        tickerJob?.cancel()
        mqttRetryJob?.cancel()
        configSyncJob?.cancel()
        mqttClient?.disconnect()
        bedGridLayout = null

        val root = screenRoot()
        val content = paddedColumn(24)
        root.addView(content)
        content.addView(bigTitle("离床报警"))
        content.addView(subtitle("正在进入主界面..."))
        setContentView(root)

        lifecycleScope.launch {
            try {
                val startup = withContext(Dispatchers.IO) {
                    val hospitals = api.hospitals()
                    val preferredCode = defaultHospitalCode
                    val firstHospital = hospitals.firstOrNull {
                        preferredCode.isNotBlank() && it.code.equals(preferredCode, ignoreCase = true)
                    } ?: hospitals.firstOrNull()
                        ?: error("当前账号没有可见医院")
                    val hospital = api.hospitalByCode(firstHospital.code)
                    val lowWeightSettings = runCatching {
                        configApi.loadLowWeightSettings(hospital.id, hospital.code)
                    }.getOrNull()
                    val leaveAlarmAcknowledgements = runCatching {
                        configApi.loadLeaveAlarmAcknowledgements(hospital.id, hospital.code)
                    }.getOrNull()
                    val pressureLogicSettings = runCatching {
                        configApi.loadPressureLogicSettings(hospital.id, hospital.code)
                    }.getOrDefault(emptyMap())
                    val dynamicPressureProfiles = runCatching {
                        configApi.loadDynamicPressureProfiles(hospital.id, hospital.code)
                    }.getOrDefault(emptyList())
                    val withLowWeight = if (lowWeightSettings.isNullOrEmpty()) {
                        mergeLocalLowWeightSettings(hospital)
                    } else {
                        mergeLowWeightSettings(hospital, lowWeightSettings)
                    }
                    StartupCloudConfig(
                        detail = if (leaveAlarmAcknowledgements == null) {
                            markLeaveAlarmAcknowledgementsUnavailable(withLowWeight)
                        } else {
                            mergeLeaveAlarmAcknowledgementSettings(withLowWeight, leaveAlarmAcknowledgements)
                        },
                        pressureLogicSettings = pressureLogicSettings,
                        dynamicPressureProfiles = dynamicPressureProfiles,
                    )
                }
                applyCloudPressureLogicSettings(startup.pressureLogicSettings)
                showDashboard(startup.detail, cloudDynamicPressureProfiles = startup.dynamicPressureProfiles)
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                if (message.contains("401") || message.contains("403")) {
                    token = null
                    prefs.edit().remove(KEY_TOKEN).apply()
                    showLogin("登录已失效，请重新输入用户名与密码")
                } else {
                    showLogin("进入主界面失败：$message")
                }
            }
        }
    }

    private fun showDashboard(
        detail: HospitalDetail,
        resetRuntime: Boolean = true,
        cloudDynamicPressureProfiles: List<CloudDynamicPressureProfile> = emptyList(),
    ) {
        showingSettings = false
        showingPressureLogicSettings = false
        settingsStatusText = null
        val mergedDetail = mergeRemoteBedsPreservingLocal(detail, beds)
        currentHospital = mergedDetail
        if (resetRuntime) {
            tickerJob?.cancel()
            mqttRetryJob?.cancel()
            configSyncJob?.cancel()
            mqttClient?.disconnect()
            beds.clear()
            beds += mergedDetail.beds
            runtimes.clear()
            beds.forEach { bed -> runtimes[bed.stableId] = BedRuntime() }
            dynamicPressureProfiles.clear()
            dynamicPressureSampling.clear()
            ensureDynamicPressureProfiles(mergedDetail.code)
            mergeDynamicPressureProfiles(mergedDetail.code, cloudDynamicPressureProfiles)
            currentHospital = mergedDetail.copy(beds = beds.toList())
        } else if (beds.isEmpty()) {
            beds += mergedDetail.beds
            beds.forEach { bed -> runtimes.getOrPut(bed.stableId) { BedRuntime() } }
            ensureDynamicPressureProfiles(mergedDetail.code)
            mergeDynamicPressureProfiles(mergedDetail.code, cloudDynamicPressureProfiles)
            currentHospital = mergedDetail.copy(beds = beds.toList())
        } else if (cloudDynamicPressureProfiles.isNotEmpty()) {
            mergeDynamicPressureProfiles(mergedDetail.code, cloudDynamicPressureProfiles)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(DASHBOARD_BACKGROUND_COLOR)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(DASHBOARD_BACKGROUND_COLOR)
            isFillViewport = true
            clipToPadding = false
        }
        val content = paddedColumn(DASHBOARD_HORIZONTAL_PADDING_DP, top = 6, bottom = 54)
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val titleRow = horizontalRow()
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleBlock.addView(dashboardTitle("离床报警系统"))
        titleBlock.addView(dashboardInfoText("${detail.code} · ${beds.size} 张床"))
        titleRow.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(settingsButton().apply { setOnClickListener { showSettings() } })
        content.addView(titleRow)

        content.addView(systemStatusCard(), blockLp(bottom = 3))
        mqttStatusText = TextView(this).apply {
            visibility = View.GONE
            text = "MQTT 未连接"
        }

        bedGridLayout = GridLayout(this).apply {
            columnCount = 1
            useDefaultMargins = false
        }
        content.addView(bedGridLayout, blockLp(top = 2))

        root.addView(logoutButton("退出").apply { setOnClickListener { logout() } }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            setMargins(0, 0, dp(14), dp(9))
        })

        setContentView(root)
        renderBeds()
        if (resetRuntime) {
            startMqtt()
            startTicker()
            startConfigSync()
        } else if (tickerJob?.isActive != true) {
            startTicker()
            if (configSyncJob?.isActive != true) {
                startConfigSync()
            }
        }
    }

    private fun showSettings(statusMessage: String = "勾选床位后点击保存") {
        val detail = currentHospital ?: return
        showingSettings = true
        showingPressureLogicSettings = false
        bedGridLayout = null

        val root = screenRoot()
        val content = paddedColumn(20, top = 8, bottom = 18)
        root.addView(content)

        val titleRow = horizontalRow()
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleBlock.addView(dashboardTitle("系统设置"))
        titleBlock.addView(dashboardInfoText("低体重患者与判断逻辑设置"))
        titleRow.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(smallButton("返回").apply {
                setOnClickListener { showDashboard(detail, resetRuntime = false) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
            addView(smallButton("保存").apply {
                setTextColor(Color.WHITE)
                background = rounded(0xFF16A34A.toInt(), Color.TRANSPARENT, 8)
                setOnClickListener { saveLowWeightBeds() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                leftMargin = dp(6)
            })
        })
        content.addView(titleRow)
        content.addView(primaryButton("离床在床判断逻辑设置").apply {
            setOnClickListener { showPressureLogicPasswordDialog() }
        })
        settingsStatusText = smallText(statusMessage, 0xFF475569.toInt()).apply {
            setPadding(0, 0, 0, dp(5))
        }
        content.addView(settingsStatusText)
        content.addView(lowWeightSettingsPanel(), blockLp(top = 3))

        setContentView(root)
    }

    private fun showPressureLogicPasswordDialog() {
        val passwordInput = EditText(this).apply {
            hint = "工程师密码"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFF0F172A.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("工程师验证")
            .setView(passwordInput)
            .setNegativeButton("取消", null)
            .setPositiveButton("进入") { _, _ ->
                if (passwordInput.text.toString() == ENGINEER_PASSWORD) {
                    showPressureLogicSettings()
                } else {
                    showError("密码错误", "请输入正确的工程师密码")
                }
            }
            .show()
    }

    private fun showPressureLogicSettings(message: String = "") {
        showingSettings = true
        showingPressureLogicSettings = true
        bedGridLayout = null

        val root = screenRoot()
        val content = paddedColumn(20, top = 8, bottom = 18)
        root.addView(content)

        val titleRow = horizontalRow()
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleBlock.addView(dashboardTitle("判断逻辑设置"))
        titleBlock.addView(dashboardInfoText("离床、在床压力阈值"))
        titleRow.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(smallButton("返回").apply {
            setOnClickListener { showSettings() }
        })
        content.addView(titleRow)

        val normalConfig = pressureLogicConfig(false)
        val lowWeightConfig = pressureLogicConfig(true)
        val normalInputs = addPressureLogicSection(content, "普通患者", normalConfig)
        val lowWeightInputs = addPressureLogicSection(content, "低体重患者", lowWeightConfig)
        val status = smallText(message, 0xFFB91C1C.toInt())

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(primaryButton("保存").apply {
            setOnClickListener {
                savePressureLogicSettings(normalInputs, lowWeightInputs, status)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6)
        })
        buttonRow.addView(smallButton("恢复默认").apply {
            setOnClickListener {
                setPressureLogicInputs(normalInputs, defaultPressureLogicConfig(lowWeight = false))
                setPressureLogicInputs(lowWeightInputs, defaultPressureLogicConfig(lowWeight = true))
                savePressureLogicSettings(normalInputs, lowWeightInputs, status)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(6)
        })
        content.addView(buttonRow, blockLp(top = 6, bottom = 4))
        content.addView(status)

        setContentView(root)
    }

    private fun startMqtt() {
        mqttRetryJob?.cancel()
        mqttRetryJob = lifecycleScope.launch {
            connectMqttWithRetry()
        }
    }

    private suspend fun connectMqttWithRetry() {
        var attempt = 1
        while (currentHospital != null) {
            try {
                connectMqttOnce()
                mqttStatusText?.text = "MQTT 已订阅"
                return
            } catch (error: Exception) {
                mqttStatusText?.text = "MQTT 连接失败，${MQTT_RECONNECT_DELAY_MS / 1000}秒后自动重连（第 $attempt 次）"
                delay(MQTT_RECONNECT_DELAY_MS)
                attempt += 1
            }
        }
    }

    private suspend fun connectMqttOnce() {
        val host = mqttHost
        val port = mqttPort
        val username = mqttUsername
        val password = mqttPassword
        mqttClient?.disconnect()
        mqttClient = BedMqttClient(
            host = host,
            port = port,
            username = username,
            password = password,
            onFrames = { frames ->
                lifecycleScope.launch(Dispatchers.Main.immediate) {
                    frames.forEach { applyFrame(it) }
                    renderBeds()
                }
            },
            onStatus = { status ->
                lifecycleScope.launch(Dispatchers.Main.immediate) {
                    mqttStatusText?.text = status
                    if (status.startsWith("MQTT 断开")) {
                        scheduleMqttReconnect()
                    }
                }
            },
        )

        mqttClient?.connect()
    }

    private fun scheduleMqttReconnect() {
        if (currentHospital == null || mqttRetryJob?.isActive == true) return
        mqttRetryJob = lifecycleScope.launch {
            mqttStatusText?.text = "MQTT 已断开，${MQTT_RECONNECT_DELAY_MS / 1000}秒后自动重连"
            delay(MQTT_RECONNECT_DELAY_MS)
            connectMqttWithRetry()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                evaluateAllAlarms(System.currentTimeMillis())
                renderBeds()
                delay(1_000)
            }
        }
    }

    private fun startConfigSync() {
        configSyncJob?.cancel()
        configSyncJob = lifecycleScope.launch {
            while (isActive) {
                delay(CLOUD_CONFIG_SYNC_INTERVAL_MS)
                refreshCloudBedConfig()
            }
        }
    }

    private suspend fun refreshCloudBedConfig() {
        val hospital = currentHospital ?: return
        if (bedConfigWriteInProgress || showingSettings) return
        try {
            val refreshed = withContext(Dispatchers.IO) {
                val hospitalDetail = api.hospitalByCode(hospital.code)
                val lowWeightSettings = runCatching {
                    configApi.loadLowWeightSettings(hospitalDetail.id, hospitalDetail.code)
                }.getOrNull()
                val leaveAlarmAcknowledgements = runCatching {
                    configApi.loadLeaveAlarmAcknowledgements(hospitalDetail.id, hospitalDetail.code)
                }.getOrNull()
                val dynamicPressureProfiles = runCatching {
                    configApi.loadDynamicPressureProfiles(hospitalDetail.id, hospitalDetail.code)
                }.getOrDefault(emptyList())
                val withLowWeight = if (lowWeightSettings.isNullOrEmpty()) {
                    mergeLocalLowWeightSettings(hospitalDetail)
                } else {
                    mergeLowWeightSettings(hospitalDetail, lowWeightSettings)
                }
                val detail = if (leaveAlarmAcknowledgements == null) {
                    markLeaveAlarmAcknowledgementsUnavailable(withLowWeight)
                } else {
                    mergeLeaveAlarmAcknowledgementSettings(withLowWeight, leaveAlarmAcknowledgements)
                }
                CloudBedConfigRefresh(detail, dynamicPressureProfiles)
            }
            mergeCloudBedConfig(refreshed.detail)
            mergeDynamicPressureProfiles(refreshed.detail.code, refreshed.dynamicPressureProfiles)
        } catch (error: Exception) {
            handleAuthFailure(error)
        }
    }

    private fun mergeCloudBedConfig(refreshed: HospitalDetail) {
        val merged = mergeRemoteBedsPreservingLocal(refreshed, beds)
        val sorted = merged.beds
        beds.clear()
        beds += sorted
        currentHospital = merged.copy(beds = sorted)
        ensureDynamicPressureProfiles(merged.code)

        beds.forEach { bed ->
            val runtime = runtimes.getOrPut(bed.stableId) { BedRuntime() }
            if (bed.leaveAlarmAcknowledged && runtime.statusKey == "left") {
                runtime.leaveAlarmAcknowledged = true
                runtime.leaveAlarmSpoken = true
                speaker.stopLeaveAlarm(bed.stableId)
            } else if (!bed.leaveAlarmAcknowledged && runtime.statusKey == "left") {
                runtime.leaveAlarmAcknowledged = false
            }
        }

        if (!showingSettings) {
            renderBeds()
        }
    }

    private fun mergeRemoteBedsPreservingLocal(
        refreshed: HospitalDetail,
        localBeds: List<BedConfig>,
    ): HospitalDetail {
        val localById = localBeds.associateBy { it.stableId }
        val mergedBeds = refreshed.beds.map { remote ->
            val local = localById[remote.stableId]
            remote.copy(
                lowWeightPatient = if (remote.lowWeightPatientFieldPresent) {
                    remote.lowWeightPatient
                } else {
                    local?.lowWeightPatient ?: localLowWeightPatient(refreshed.code, remote)
                },
                leaveAlarmAcknowledged = if (remote.leaveAlarmAcknowledgedFieldPresent) {
                    remote.leaveAlarmAcknowledged
                } else {
                    local?.leaveAlarmAcknowledged ?: remote.leaveAlarmAcknowledged
                },
            )
        }
        persistLocalLowWeightPatients(refreshed.code, mergedBeds)
        return refreshed.copy(beds = sortedBeds(mergedBeds))
    }

    private fun mergeLowWeightSettings(
        detail: HospitalDetail,
        settings: List<CloudLowWeightBedSetting>,
    ): HospitalDetail {
        if (settings.isEmpty()) return mergeLocalLowWeightSettings(detail)
        val byId = settings.associateBy { it.bedId }
        val byPosition = settings
            .filter { it.bedIndex != null }
            .associateBy { lowWeightPositionKey(it.bedIndex ?: 0, it.bedLabel) }
        val mergedBeds = detail.beds.map { bed ->
            val setting = byPosition[lowWeightPositionKey(bed)] ?: byId[bed.stableId]
            if (setting == null) {
                bed
            } else {
                bed.copy(
                    lowWeightPatient = setting.lowWeightPatient,
                    lowWeightPatientFieldPresent = true,
                )
            }
        }
        return detail.copy(beds = sortedBeds(mergedBeds))
    }

    private fun mergeLocalLowWeightSettings(detail: HospitalDetail): HospitalDetail {
        val mergedBeds = detail.beds.map { bed ->
            bed.copy(
                lowWeightPatient = localLowWeightPatient(detail.code, bed),
                lowWeightPatientFieldPresent = true,
            )
        }
        return detail.copy(beds = sortedBeds(mergedBeds))
    }

    private fun mergeLeaveAlarmAcknowledgementSettings(
        detail: HospitalDetail,
        settings: List<CloudLeaveAlarmAcknowledgementSetting>,
    ): HospitalDetail {
        val byId = settings.associateBy { it.bedId }
        val byPosition = settings
            .filter { it.bedIndex != null }
            .associateBy { lowWeightPositionKey(it.bedIndex ?: 0, it.bedLabel) }
        val mergedBeds = detail.beds.map { bed ->
            val setting = byPosition[lowWeightPositionKey(bed)] ?: byId[bed.stableId]
            bed.copy(
                leaveAlarmAcknowledged = setting?.leaveAlarmAcknowledged ?: false,
                leaveAlarmAcknowledgedFieldPresent = true,
            )
        }
        return detail.copy(beds = sortedBeds(mergedBeds))
    }

    private fun markLeaveAlarmAcknowledgementsUnavailable(detail: HospitalDetail): HospitalDetail {
        val mergedBeds = detail.beds.map { bed ->
            bed.copy(leaveAlarmAcknowledgedFieldPresent = false)
        }
        return detail.copy(beds = sortedBeds(mergedBeds))
    }

    private fun applyCloudPressureLogicSettings(settings: Map<String, CloudPressureLogicConfig>): Boolean {
        val normal = settings[PATIENT_TYPE_NORMAL]?.toPressureLogicConfig()
        val lowWeight = settings[PATIENT_TYPE_LOW_WEIGHT]?.toPressureLogicConfig()
        if (normal == null && lowWeight == null) return false
        applyPressureLogicSettings(normal, lowWeight)
        return true
    }

    private fun applyPressureLogicSettings(
        normal: PressureLogicConfig?,
        lowWeight: PressureLogicConfig?,
    ) {
        if (normal == null && lowWeight == null) return
        val editor = prefs.edit()
        normal?.let { editor.putPressureLogicConfig(lowWeight = false, config = it) }
        lowWeight?.let { editor.putPressureLogicConfig(lowWeight = true, config = it) }
        editor
            .putInt(KEY_PRESSURE_CONFIG_VERSION, PRESSURE_CONFIG_VERSION)
            .apply()
        resetPressureCounters()
    }

    private fun SharedPreferences.Editor.putPressureLogicConfig(
        lowWeight: Boolean,
        config: PressureLogicConfig,
    ): SharedPreferences.Editor {
        return if (lowWeight) {
            putInt(KEY_PRESSURE_LOW_WEIGHT_IN_BED, config.inBedThreshold)
                .putInt(KEY_PRESSURE_LOW_WEIGHT_TWO_POINT, config.twoPointInBedThreshold)
                .putInt(KEY_PRESSURE_LOW_WEIGHT_LEFT, config.leftThreshold)
                .putInt(KEY_PRESSURE_LOW_WEIGHT_COUNT, config.leftConfirmPackets)
        } else {
            putInt(KEY_PRESSURE_NORMAL_IN_BED, config.inBedThreshold)
                .putInt(KEY_PRESSURE_NORMAL_TWO_POINT, config.twoPointInBedThreshold)
                .putInt(KEY_PRESSURE_NORMAL_LEFT, config.leftThreshold)
                .putInt(KEY_PRESSURE_NORMAL_COUNT, config.leftConfirmPackets)
        }
    }

    private fun CloudPressureLogicConfig.toPressureLogicConfig(): PressureLogicConfig =
        PressureLogicConfig(
            inBedThreshold = inBedThreshold,
            twoPointInBedThreshold = twoPointInBedThreshold,
            leftThreshold = leftThreshold,
            leftConfirmPackets = leftConfirmPackets,
        )

    private fun PressureLogicConfig.toCloudPressureLogicConfig(patientType: String): CloudPressureLogicConfig =
        CloudPressureLogicConfig(
            patientType = patientType,
            inBedThreshold = inBedThreshold,
            twoPointInBedThreshold = twoPointInBedThreshold,
            leftThreshold = leftThreshold,
            leftConfirmPackets = leftConfirmPackets,
        )

    private fun ensureDynamicPressureProfiles(hospitalCode: String) {
        val activeIds = beds.map { it.stableId }.toSet()
        dynamicPressureProfiles.keys.retainAll(activeIds)
        dynamicPressureSampling.keys.retainAll(activeIds)
        beds.forEach { bed ->
            dynamicPressureProfiles.getOrPut(bed.stableId) {
                localDynamicPressureProfile(hospitalCode, bed) ?: emptyDynamicPressureProfile(bed)
            }
        }
    }

    private fun mergeDynamicPressureProfiles(
        hospitalCode: String,
        cloudProfiles: List<CloudDynamicPressureProfile>,
    ) {
        if (beds.isEmpty()) return
        val byId = cloudProfiles.associateBy { it.bedId }
        val byPosition = cloudProfiles
            .filter { it.bedIndex != null }
            .associateBy { lowWeightPositionKey(it.bedIndex ?: 0, it.bedLabel) }
        beds.forEach { bed ->
            val cloud = byPosition[lowWeightPositionKey(bed)] ?: byId[bed.stableId]
            val profile = cloud?.toDynamicPressureProfile(bed)
                ?: dynamicPressureProfiles[bed.stableId]
                ?: localDynamicPressureProfile(hospitalCode, bed)
                ?: emptyDynamicPressureProfile(bed)
            dynamicPressureProfiles[bed.stableId] = profile
            persistLocalDynamicPressureProfile(hospitalCode, bed, profile)
        }
    }

    private fun emptyDynamicPressureProfile(bed: BedConfig): DynamicPressureProfile =
        DynamicPressureProfile(
            bedId = bed.stableId,
            bedIndex = bed.bedIndex,
            bedLabel = bed.bedLabel.ifBlank { bed.bedIndex.toString() },
        )

    private fun CloudDynamicPressureProfile.toDynamicPressureProfile(bed: BedConfig): DynamicPressureProfile =
        DynamicPressureProfile(
            bedId = bed.stableId,
            bedIndex = bed.bedIndex,
            bedLabel = bed.bedLabel.ifBlank { bed.bedIndex.toString() },
            emptyBaseline = emptyBaseline.takeIf { it.size >= 4 } ?: emptyList(),
            inBedTemplate = inBedTemplate.takeIf { it.size >= 4 } ?: emptyList(),
            baselineUpdateDate = baselineUpdateDate,
            templateUpdateDate = templateUpdateDate,
            baselineUpdatedAt = baselineUpdatedAt,
            templateUpdatedAt = templateUpdatedAt,
            updatedAt = updatedAt,
        )

    private fun DynamicPressureProfile.toCloudDynamicPressureProfile(): CloudDynamicPressureProfile =
        CloudDynamicPressureProfile(
            bedId = bedId,
            bedIndex = bedIndex,
            bedLabel = bedLabel,
            emptyBaseline = emptyBaseline,
            inBedTemplate = inBedTemplate,
            baselineUpdateDate = baselineUpdateDate,
            templateUpdateDate = templateUpdateDate,
            baselineUpdatedAt = baselineUpdatedAt,
            templateUpdatedAt = templateUpdatedAt,
            updatedAt = updatedAt,
        )

    private fun localDynamicPressureProfile(hospitalCode: String, bed: BedConfig): DynamicPressureProfile? {
        val baseline = prefs.getString(dynamicPressureProfileKey(hospitalCode, bed, "empty"), null).toPressureList4()
        val template = prefs.getString(dynamicPressureProfileKey(hospitalCode, bed, "template"), null).toPressureList4()
        if (baseline.isEmpty() && template.isEmpty()) return null
        return DynamicPressureProfile(
            bedId = bed.stableId,
            bedIndex = bed.bedIndex,
            bedLabel = bed.bedLabel.ifBlank { bed.bedIndex.toString() },
            emptyBaseline = baseline,
            inBedTemplate = template,
            baselineUpdateDate = prefs.getString(dynamicPressureProfileKey(hospitalCode, bed, "empty_date"), null),
            templateUpdateDate = prefs.getString(dynamicPressureProfileKey(hospitalCode, bed, "template_date"), null),
            baselineUpdatedAt = prefs.getString(dynamicPressureProfileKey(hospitalCode, bed, "empty_at"), null),
            templateUpdatedAt = prefs.getString(dynamicPressureProfileKey(hospitalCode, bed, "template_at"), null),
            updatedAt = prefs.getString(dynamicPressureProfileKey(hospitalCode, bed, "updated_at"), null),
        )
    }

    private fun persistLocalDynamicPressureProfile(
        hospitalCode: String,
        bed: BedConfig,
        profile: DynamicPressureProfile,
    ) {
        prefs.edit()
            .putString(dynamicPressureProfileKey(hospitalCode, bed, "empty"), profile.emptyBaseline.toPressureString())
            .putString(dynamicPressureProfileKey(hospitalCode, bed, "template"), profile.inBedTemplate.toPressureString())
            .putString(dynamicPressureProfileKey(hospitalCode, bed, "empty_date"), profile.baselineUpdateDate.orEmpty())
            .putString(dynamicPressureProfileKey(hospitalCode, bed, "template_date"), profile.templateUpdateDate.orEmpty())
            .putString(dynamicPressureProfileKey(hospitalCode, bed, "empty_at"), profile.baselineUpdatedAt.orEmpty())
            .putString(dynamicPressureProfileKey(hospitalCode, bed, "template_at"), profile.templateUpdatedAt.orEmpty())
            .putString(dynamicPressureProfileKey(hospitalCode, bed, "updated_at"), profile.updatedAt.orEmpty())
            .apply()
    }

    private fun dynamicPressureProfileKey(hospitalCode: String, bed: BedConfig, suffix: String): String =
        "$KEY_DYNAMIC_PRESSURE_PROFILE_PREFIX${hospitalCode}_${lowWeightPositionKey(bed)}_$suffix"

    private fun List<Int>.toPressureString(): String =
        if (size >= 4) take(4).joinToString(",") else ""

    private fun String?.toPressureList4(): List<Int> {
        val values = this?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
        return if (values.size >= 4) values.take(4) else emptyList()
    }

    private fun dynamicProfileDate(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))

    private fun dynamicProfileTimestamp(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(now))

    private fun dynamicProfileTimestampMillis(value: String?): Long? {
        val text = value?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
                isLenient = false
            }.parse(text)?.time
        }.getOrNull()
    }

    private fun applyFrame(frame: ParsedFrame) {
        val bed = beds.firstOrNull { bed ->
            BedDisplayUtils.isBound(bed) &&
                frame.gatewayId == bed.boundGatewayId &&
                frame.sensorId == bed.boundSensorId
        } ?: return
        val runtime = runtimes.getOrPut(bed.stableId) { BedRuntime() }
        val now = System.currentTimeMillis()
        val previousStatus = runtime.statusKey

        runtime.protocol = frame.protocol
        runtime.posture = frame.posture
        runtime.pressure28 = frame.pressure28
        runtime.temperature = frame.temperature
        runtime.showTemperature = frame.showTemperature
        runtime.heartRateWave = frame.heartRateWave
        runtime.sequenceId = frame.sequenceId
        runtime.occupancyPresent = frame.occupancyPresent
        runtime.lastMqttTime = now

        val pressure4 = frame.pressure28.take(4)
        if (frame.protocol == "occupancy4pressure") {
            if (runtime.lastOccupancySequenceId == frame.sequenceId) {
                return
            }
            runtime.lastOccupancySequenceId = frame.sequenceId
            updatePressurePresence(bed, runtime, pressure4)
        }

        if (frame.protocol == "occupancy4pressure") {
            runtime.heartRate = 0
            runtime.breathRate = 0
        } else {
            runtime.heartRate = if (frame.heartRate > 0) BedDisplayUtils.displayHeart(frame.heartRate) else 0
            runtime.breathRate = if (frame.respiration > 0) BedDisplayUtils.displayBreath(frame.respiration) else 0
        }

        val newStatus = BedDisplayUtils.statusKey(runtime)
        runtime.statusKey = newStatus
        if (newStatus != previousStatus) {
            val initialLeft = previousStatus == "unknown" && newStatus == "left"
            runtime.statusSince = if (initialLeft) {
                now - LONG_ABSENCE_MS
            } else {
                now
            }
            if (newStatus == "in_bed") {
                runtime.leaveAlarmSpoken = false
                runtime.leaveAlarmAcknowledged = false
                runtime.lastAlarmText = null
                speaker.stopLeaveAlarm(bed.stableId)
                clearCloudLeaveAlarmAcknowledgedIfNeeded(bed)
            } else if (newStatus == "left") {
                runtime.leaveAlarmSpoken = initialLeft
                runtime.leaveAlarmAcknowledged = bed.leaveAlarmAcknowledged
                if (bed.leaveAlarmAcknowledged) {
                    runtime.leaveAlarmSpoken = true
                }
                runtime.lastAlarmText = null
            }
        }

        evaluateAlarms(bed, runtime, now)
        if (frame.protocol == "occupancy4pressure") {
            maybeUpdateDynamicPressureProfile(bed, runtime, pressure4, now)
        }
    }

    private fun updatePressurePresence(bed: BedConfig, runtime: BedRuntime, pressure4: List<Int>) {
        if (pressure4.size < 4) return
        val dynamicProfile = dynamicPressureProfiles[bed.stableId]
        if (dynamicProfile != null && hasCompleteDynamicPressureModel(dynamicProfile)) {
            updateDynamicPressurePresence(runtime, pressure4, dynamicProfile, pressureLogicConfig(bed.lowWeightPatient))
            return
        }
        updateFixedPressurePresence(bed, runtime, pressure4)
    }

    private fun updateFixedPressurePresence(bed: BedConfig, runtime: BedRuntime, pressure4: List<Int>) {
        val config = pressureLogicConfig(bed.lowWeightPatient)
        when {
            pressure4.any { it > config.inBedThreshold } ||
                pressure4.count { it > config.twoPointInBedThreshold } >= 2 -> {
                runtime.pressurePresence = true
                runtime.pressureLowPacketCount = 0
            }
            pressure4.all { it <= config.leftThreshold } -> {
                runtime.pressureLowPacketCount += 1
                if (runtime.pressureLowPacketCount >= config.leftConfirmPackets) {
                    runtime.pressurePresence = false
                }
            }
            pressure4.any { it > config.leftThreshold } -> {
                runtime.pressureLowPacketCount = 0
            }
        }
    }

    private fun updateDynamicPressurePresence(
        runtime: BedRuntime,
        pressure4: List<Int>,
        profile: DynamicPressureProfile,
        fallbackConfig: PressureLogicConfig,
    ) {
        val ratios = dynamicPressureRatios(pressure4, profile)
        val inBedEvidence = ratios.sumRatio >= DYNAMIC_IN_BED_SUM_RATIO ||
            ratios.maxRatio >= DYNAMIC_IN_BED_MAX_RATIO ||
            ratios.top2Ratio >= DYNAMIC_IN_BED_TOP2_RATIO
        val leftEvidence = ratios.sumRatio <= DYNAMIC_LEFT_SUM_RATIO &&
            ratios.maxRatio <= DYNAMIC_LEFT_MAX_RATIO &&
            ratios.top2Ratio <= DYNAMIC_LEFT_TOP2_RATIO

        when {
            inBedEvidence -> {
                runtime.pressurePresence = true
                runtime.pressureLowPacketCount = 0
            }
            leftEvidence -> {
                runtime.pressureLowPacketCount += 1
                val confirmPackets = fallbackConfig.leftConfirmPackets.coerceAtLeast(DYNAMIC_LEFT_CONFIRM_PACKETS_MIN)
                if (runtime.pressureLowPacketCount >= confirmPackets) {
                    runtime.pressurePresence = false
                }
            }
            else -> {
                runtime.pressureLowPacketCount = 0
            }
        }
    }

    private fun maybeUpdateDynamicPressureProfile(
        bed: BedConfig,
        runtime: BedRuntime,
        pressure4: List<Int>,
        now: Long,
    ) {
        if (pressure4.size < 4) return
        if (runtime.statusKey !in setOf("in_bed", "left")) {
            dynamicPressureSampling.remove(bed.stableId)
            return
        }
        val sampling = dynamicPressureSampling.getOrPut(bed.stableId) {
            PressureProfileSamplingState(runtime.statusKey, now)
        }
        if (sampling.statusKey != runtime.statusKey) {
            resetPressureProfileSampling(sampling, runtime.statusKey, now)
        }

        when (runtime.statusKey) {
            "left" -> maybeUpdateEmptyBaselineProfile(bed, sampling, pressure4, now)
            "in_bed" -> maybeUpdateInBedTemplateProfile(bed, sampling, pressure4, now)
        }
    }

    private fun maybeUpdateEmptyBaselineProfile(
        bed: BedConfig,
        sampling: PressureProfileSamplingState,
        pressure4: List<Int>,
        now: Long,
    ) {
        val current = dynamicPressureProfiles[bed.stableId] ?: emptyDynamicPressureProfile(bed)
        if (!isDynamicProfileRefreshDue(
                lastUpdatedAt = current.baselineUpdatedAt,
                lastUpdateDate = current.baselineUpdateDate,
                refreshMs = DYNAMIC_EMPTY_BASELINE_REFRESH_MS,
                now = now,
            )
        ) {
            resetPressureProfileCapture(sampling)
            return
        }
        if (!collectPressureProfileWindow(sampling, pressure4, now)) return

        val hospital = currentHospital ?: return
        val updated = current.copy(
            bedId = bed.stableId,
            bedIndex = bed.bedIndex,
            bedLabel = bed.bedLabel.ifBlank { bed.bedIndex.toString() },
            emptyBaseline = medianPressureSample(sampling.samples),
            baselineUpdateDate = dynamicProfileDate(now),
            baselineUpdatedAt = dynamicProfileTimestamp(now),
            updatedAt = dynamicProfileTimestamp(now),
        )
        dynamicPressureProfiles[bed.stableId] = updated
        persistLocalDynamicPressureProfile(hospital.code, bed, updated)
        saveDynamicPressureProfileToCloud(hospital, bed, updated)
        resetPressureProfileSampling(sampling, "left", now)
    }

    private fun maybeUpdateInBedTemplateProfile(
        bed: BedConfig,
        sampling: PressureProfileSamplingState,
        pressure4: List<Int>,
        now: Long,
    ) {
        val current = dynamicPressureProfiles[bed.stableId] ?: emptyDynamicPressureProfile(bed)
        if (!isDynamicProfileRefreshDue(
                lastUpdatedAt = current.templateUpdatedAt,
                lastUpdateDate = current.templateUpdateDate,
                refreshMs = DYNAMIC_IN_BED_TEMPLATE_REFRESH_MS,
                now = now,
            )
        ) {
            resetPressureProfileCapture(sampling)
            return
        }
        if (!collectPressureProfileWindow(sampling, pressure4, now)) return

        val hospital = currentHospital ?: return
        val updated = current.copy(
            bedId = bed.stableId,
            bedIndex = bed.bedIndex,
            bedLabel = bed.bedLabel.ifBlank { bed.bedIndex.toString() },
            inBedTemplate = medianPressureSample(sampling.samples),
            templateUpdateDate = dynamicProfileDate(now),
            templateUpdatedAt = dynamicProfileTimestamp(now),
            updatedAt = dynamicProfileTimestamp(now),
        )

        dynamicPressureProfiles[bed.stableId] = updated
        persistLocalDynamicPressureProfile(hospital.code, bed, updated)
        saveDynamicPressureProfileToCloud(hospital, bed, updated)
        resetPressureProfileSampling(sampling, "in_bed", now)
    }

    private fun collectPressureProfileWindow(
        sampling: PressureProfileSamplingState,
        pressure4: List<Int>,
        now: Long,
    ): Boolean {
        if (now - sampling.startedAtMs < DYNAMIC_PROFILE_CAPTURE_DELAY_MS) {
            resetPressureProfileCapture(sampling)
            return false
        }

        if (sampling.captureStartedAtMs == 0L) {
            sampling.captureStartedAtMs = now
            sampling.samples.clear()
        }

        sampling.samples.add(pressure4.take(4))
        sampling.lastSampleAtMs = now
        while (sampling.samples.size > DYNAMIC_PROFILE_CAPTURE_MAX_SAMPLES) {
            sampling.samples.removeAt(0)
        }
        return now - sampling.captureStartedAtMs >= DYNAMIC_PROFILE_CAPTURE_DURATION_MS
    }

    private fun resetPressureProfileSampling(
        sampling: PressureProfileSamplingState,
        statusKey: String,
        now: Long,
    ) {
        sampling.statusKey = statusKey
        sampling.startedAtMs = now
        resetPressureProfileCapture(sampling)
    }

    private fun resetPressureProfileCapture(sampling: PressureProfileSamplingState) {
        sampling.captureStartedAtMs = 0L
        sampling.lastSampleAtMs = 0L
        sampling.samples.clear()
    }

    private fun isDynamicProfileRefreshDue(
        lastUpdatedAt: String?,
        lastUpdateDate: String?,
        refreshMs: Long,
        now: Long,
    ): Boolean {
        val lastUpdateMs = dynamicProfileTimestampMillis(lastUpdatedAt)
        if (lastUpdateMs != null) return now - lastUpdateMs >= refreshMs
        if (refreshMs >= DYNAMIC_EMPTY_BASELINE_REFRESH_MS && lastUpdateDate == dynamicProfileDate(now)) {
            return false
        }
        return true
    }

    private fun saveDynamicPressureProfileToCloud(
        hospital: HospitalDetail,
        bed: BedConfig,
        profile: DynamicPressureProfile,
    ) {
        if (!dynamicPressureWriteInProgress.add(bed.stableId)) return
        lifecycleScope.launch {
            try {
                val savedProfiles = withContext(Dispatchers.IO) {
                    configApi.saveDynamicPressureProfiles(
                        hospitalId = hospital.id,
                        hospitalCode = hospital.code,
                        updatedByUserId = currentUser?.id,
                        profiles = listOf(profile.toCloudDynamicPressureProfile()),
                    )
                }
                mergeDynamicPressureProfiles(hospital.code, savedProfiles)
            } catch (_: Exception) {
                // Local dynamic parameters remain active when the cloud endpoint is temporarily unavailable.
            } finally {
                dynamicPressureWriteInProgress.remove(bed.stableId)
            }
        }
    }

    private fun hasCompleteDynamicPressureModel(profile: DynamicPressureProfile): Boolean {
        if (profile.emptyBaseline.size < 4 || profile.inBedTemplate.size < 4) return false
        val templateDelta = dynamicTemplateDelta(profile)
        return templateDelta.sum() >= DYNAMIC_MIN_TEMPLATE_DELTA_SUM
    }

    private fun dynamicPressureRatios(
        pressure4: List<Int>,
        profile: DynamicPressureProfile,
    ): DynamicPressureRatios {
        val baseline = profile.emptyBaseline.take(4)
        val adjusted = List(4) { index -> (pressure4[index] - baseline[index]).coerceAtLeast(0) }
        val templateDelta = dynamicTemplateDelta(profile)
        val sumRatio = adjusted.sum().toDouble() / templateDelta.sum().coerceAtLeast(1)
        val maxRatio = adjusted.maxOrNull().orZero().toDouble() / templateDelta.maxOrNull().orZero().coerceAtLeast(1)
        val top2Ratio = adjusted.sortedDescending().take(2).sum().toDouble() /
            templateDelta.sortedDescending().take(2).sum().coerceAtLeast(1)
        return DynamicPressureRatios(sumRatio, maxRatio, top2Ratio)
    }

    private fun dynamicTemplateDelta(profile: DynamicPressureProfile): List<Int> {
        return List(4) { index ->
            (profile.inBedTemplate[index] - profile.emptyBaseline[index]).coerceAtLeast(1)
        }
    }

    private fun medianPressureSample(samples: List<List<Int>>): List<Int> {
        if (samples.isEmpty()) return listOf(0, 0, 0, 0)
        return List(4) { point ->
            val values = samples.mapNotNull { it.getOrNull(point) }.sorted()
            values[values.size / 2]
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun evaluateAlarms(bed: BedConfig, runtime: BedRuntime, now: Long) {
        val online = BedDisplayUtils.isBound(bed) && !BedDisplayUtils.isOffline(bed, runtime, now)
        val leftBed = runtime.statusKey == "left"
        val confirmedLeft = leftBed && isLeaveAlarmConfirmed(runtime, now)
        if (online && confirmedLeft) {
            if (!isBedLeaveAlarmAcknowledged(bed, runtime) && !runtime.leaveAlarmSpoken && now - runtime.statusSince < LONG_ABSENCE_MS) {
                val text = "${bed.bedLabel.ifBlank { bed.bedIndex.toString() }}号床离床，请注意"
                runtime.leaveAlarmSpoken = true
                runtime.lastAlarmText = text
                speaker.speakLeaveAlarmForDuration(bed.stableId, text, LEAVE_ALARM_VOICE_MS)
                speaker.vibrateAlarm()
            }
        } else if (!leftBed) {
            runtime.leaveAlarmSpoken = false
            runtime.leaveAlarmAcknowledged = false
            runtime.lastAlarmText = null
            speaker.stopLeaveAlarm(bed.stableId)
            clearCloudLeaveAlarmAcknowledgedIfNeeded(bed)
        }
    }

    private fun evaluateAllAlarms(now: Long) {
        beds.forEach { bed ->
            val runtime = runtimes.getOrPut(bed.stableId) { BedRuntime() }
            evaluateAlarms(bed, runtime, now)
        }
    }

    private fun renderBeds() {
        val grid = bedGridLayout ?: return
        val now = System.currentTimeMillis()
        val gridSpec = calculateBedGridSpec(grid)
        grid.columnCount = gridSpec.columns
        val states = beds.map { bed ->
            val runtime = runtimes.getOrPut(bed.stableId) { BedRuntime() }
            bedVisualState(bed, runtime, now)
        }
        updateSummary(states)

        repeat(grid.childCount) { index ->
            grid.getChildAt(index).clearAnimation()
        }
        grid.removeAllViews()
        beds.forEach { bed ->
            val runtime = runtimes.getOrPut(bed.stableId) { BedRuntime() }
            grid.addView(bedSquare(bed, runtime, now, gridSpec))
        }
    }

    private fun calculateBedGridSpec(grid: GridLayout): BedGridSpec {
        val margin = dp(BED_SQUARE_MARGIN_DP)
        val minSquareSize = dp(BED_SQUARE_MIN_DP)
        val fallbackWidth = resources.displayMetrics.widthPixels - dp(DASHBOARD_HORIZONTAL_PADDING_DP) * 2
        val availableWidth = (grid.width.takeIf { it > 0 } ?: fallbackWidth)
            .coerceAtLeast(minSquareSize + margin * 2)
        val columns = (availableWidth / (minSquareSize + margin * 2)).coerceAtLeast(1)
        val squareSize = ((availableWidth - columns * margin * 2) / columns)
            .coerceAtLeast(minSquareSize)
        return BedGridSpec(columns, squareSize, margin)
    }

    private fun updateSummary(states: List<BedVisualState>) {
        val inBedCount = states.count { it == BedVisualState.IN_BED }
        val alertCount = states.count { it == BedVisualState.ALERT }
        val acknowledgedCount = states.count { it == BedVisualState.ACKNOWLEDGED }
        val longAbsenceCount = states.count { it == BedVisualState.LONG_ABSENCE }

        inBedCountText?.text = inBedCount.toString()
        alertCountText?.text = alertCount.toString()
        acknowledgedCountText?.text = acknowledgedCount.toString()
        longAbsenceCountText?.text = longAbsenceCount.toString()

        systemStatusText?.apply {
            if (alertCount > 0) {
                text = "系统运行中，${alertCount} 个床位正在离床报警"
                setTextColor(0xFFB91C1C.toInt())
            } else {
                text = "系统正常运行，暂无报警"
                setTextColor(0xFF0F172A.toInt())
            }
        }
    }

    private fun bedSquare(bed: BedConfig, runtime: BedRuntime, now: Long, gridSpec: BedGridSpec): View {
        val state = bedVisualState(bed, runtime, now)
        val colors = bedStateColors(state)
        val square = FrameLayout(this).apply {
            background = rounded(colors.fill, colors.stroke, 10)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true
            isFocusable = true
            if (state == BedVisualState.ALERT) {
                startAnimation(AlphaAnimation(1f, 0.45f).apply {
                    duration = 700
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                })
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN &&
                    bedVisualState(bed, runtime, System.currentTimeMillis()) == BedVisualState.ALERT
                ) {
                    acknowledgeLeaveAlarm(bed, runtime)
                    true
                } else {
                    false
                }
            }
        }
        square.layoutParams = GridLayout.LayoutParams().apply {
            width = gridSpec.squareSizePx
            height = gridSpec.squareSizePx
            setMargins(gridSpec.marginPx, gridSpec.marginPx, gridSpec.marginPx, gridSpec.marginPx)
        }

        square.addView(TextView(this).apply {
            text = bed.bedLabel.ifBlank { bed.bedIndex.toString() }
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.text)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (bed.lowWeightPatient) {
            square.addView(TextView(this).apply {
                text = "L"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF111827.toInt())
                setShadowLayer(3f, 0f, 0f, Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START))
        }

        square.addView(TextView(this).apply {
            text = state.badge
            textSize = 11f
            setTextColor(colors.badgeText)
            gravity = Gravity.CENTER
            background = rounded(colors.badgeFill, Color.TRANSPARENT, 99)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        if (state != BedVisualState.IN_BED && state != BedVisualState.IDLE) {
            square.addView(TextView(this).apply {
                text = BedDisplayUtils.formatDuration(now - runtime.statusSince)
                textSize = 10f
                setTextColor(colors.text)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }

        return square
    }

    private fun acknowledgeLeaveAlarm(bed: BedConfig, runtime: BedRuntime) {
        speaker.stopLeaveAlarm(bed.stableId)
        runtime.leaveAlarmSpoken = true
        runtime.leaveAlarmAcknowledged = true
        runtime.lastAlarmText = null
        setCloudLeaveAlarmAcknowledged(bed, true)
        renderBeds()
    }

    private fun clearCloudLeaveAlarmAcknowledgedIfNeeded(bed: BedConfig) {
        if (bed.leaveAlarmAcknowledged) {
            setCloudLeaveAlarmAcknowledged(bed, false)
        }
    }

    private fun setCloudLeaveAlarmAcknowledged(bed: BedConfig, acknowledged: Boolean) {
        val hospital = currentHospital ?: return
        val bedIndex = beds.indexOfFirst { it.stableId == bed.stableId }
        if (bedIndex < 0 || beds[bedIndex].leaveAlarmAcknowledged == acknowledged) return

        val updatedBed = beds[bedIndex].copy(
            leaveAlarmAcknowledged = acknowledged,
            leaveAlarmAcknowledgedFieldPresent = true,
        )
        beds[bedIndex] = updatedBed
        currentHospital = hospital.copy(beds = beds.toList())
        renderBeds()

        bedConfigWriteInProgress = true
        lifecycleScope.launch {
            try {
                val savedSettings = withContext(Dispatchers.IO) {
                    configApi.saveLeaveAlarmAcknowledgements(
                        hospitalId = hospital.id,
                        hospitalCode = hospital.code,
                        updatedByUserId = currentUser?.id,
                        beds = listOf(updatedBed),
                    )
                }
                val merged = mergeLeaveAlarmAcknowledgementSettings(
                    hospital.copy(beds = beds.toList()),
                    savedSettings,
                )
                beds.clear()
                beds += merged.beds
                currentHospital = merged.copy(beds = beds.toList())
                renderBeds()
            } catch (error: Exception) {
                systemStatusText?.apply {
                    text = "确认离床保存失败：${error.message.orEmpty()}"
                    setTextColor(0xFFB91C1C.toInt())
                }
            } finally {
                bedConfigWriteInProgress = false
            }
        }
    }

    private fun bedVisualState(bed: BedConfig, runtime: BedRuntime, now: Long): BedVisualState {
        if (!BedDisplayUtils.isBound(bed) || BedDisplayUtils.isOffline(bed, runtime, now)) {
            return BedVisualState.IDLE
        }
        if (runtime.statusKey == "in_bed") {
            return BedVisualState.IN_BED
        }
        if (runtime.statusKey != "left") {
            return BedVisualState.IDLE
        }
        if (!isLeaveAlarmConfirmed(runtime, now)) {
            return BedVisualState.IN_BED
        }
        if (now - runtime.statusSince >= LONG_ABSENCE_MS) {
            return BedVisualState.LONG_ABSENCE
        }
        return if (isBedLeaveAlarmAcknowledged(bed, runtime)) {
            BedVisualState.ACKNOWLEDGED
        } else {
            BedVisualState.ALERT
        }
    }

    private fun isBedLeaveAlarmAcknowledged(bed: BedConfig, runtime: BedRuntime): Boolean {
        return runtime.leaveAlarmAcknowledged || bed.leaveAlarmAcknowledged
    }

    private fun bedStateColors(state: BedVisualState): BedStateColors {
        return when (state) {
            BedVisualState.IN_BED -> BedStateColors(0xFF4CAF7D.toInt(), 0xFF3D9E6A.toInt(), Color.WHITE, 0xFF2E7D52.toInt(), 0xFFC8F5DF.toInt())
            BedVisualState.ALERT -> BedStateColors(0xFFE53935.toInt(), 0xFFB71C1C.toInt(), Color.WHITE, 0xFF7F0000.toInt(), 0xFFFFCDD2.toInt())
            BedVisualState.ACKNOWLEDGED -> BedStateColors(0xFFF8BBD0.toInt(), 0xFFF48FB1.toInt(), 0xFF880E4F.toInt(), 0xFFFCE4EC.toInt(), 0xFF880E4F.toInt())
            BedVisualState.LONG_ABSENCE -> BedStateColors(0xFFFAFAFA.toInt(), 0xFFE0E0E0.toInt(), 0xFF757575.toInt(), 0xFFEEEEEE.toInt(), 0xFF616161.toInt())
            BedVisualState.IDLE -> BedStateColors(0xFFFAFAFA.toInt(), 0xFFE2E8F0.toInt(), 0xFF94A3B8.toInt(), 0xFFE2E8F0.toInt(), 0xFF64748B.toInt())
        }
    }

    private fun isLeaveAlarmConfirmed(runtime: BedRuntime, now: Long): Boolean {
        return runtime.statusKey == "left" && now - runtime.statusSince >= LEAVE_ALARM_CONFIRM_DELAY_MS
    }

    private fun bedNumberSortKey(bed: BedConfig): Int {
        return Regex("\\d+").find(bed.bedLabel)?.value?.toIntOrNull()
            ?: bed.bedIndex.takeIf { it > 0 }
            ?: Int.MAX_VALUE
    }

    private fun sortedBeds(source: List<BedConfig>): List<BedConfig> {
        return source.sortedWith(compareBy<BedConfig> { bedNumberSortKey(it) }.thenBy { it.bedLabel }.thenBy { it.bedIndex })
    }

    private fun addPressureLogicSection(
        parent: LinearLayout,
        title: String,
        config: PressureLogicConfig,
    ): PressureLogicInputs {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 0xFFD6E2DA.toInt(), 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        section.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
            includeFontPadding = false
            setPadding(0, 0, 0, dp(7))
        })

        val inBed = input("单点在床阈值（任意压力大于此值判定在床）", config.inBedThreshold.toString(), number = true)
        val twoPoint = input("双点在床阈值（任意2点压力大于此值判定在床）", config.twoPointInBedThreshold.toString(), number = true)
        val left = input("离床低压阈值（四点压力均小于等于此值计为低压）", config.leftThreshold.toString(), number = true)
        val count = input("低压计数（连续达到后判定离床）", config.leftConfirmPackets.toString(), number = true)
        listOf(inBed, twoPoint, left, count).forEach { pair ->
            section.addView(pair.first)
            section.addView(pair.second)
        }
        parent.addView(section, blockLp(top = 4, bottom = 8))
        return PressureLogicInputs(inBed.second, twoPoint.second, left.second, count.second)
    }

    private fun savePressureLogicSettings(
        normalInputs: PressureLogicInputs,
        lowWeightInputs: PressureLogicInputs,
        status: TextView,
    ) {
        val normal = readPressureLogicInputs(normalInputs)
        val lowWeight = readPressureLogicInputs(lowWeightInputs)
        if (normal == null || lowWeight == null) {
            status.setTextColor(0xFFB91C1C.toInt())
            status.text = "请输入完整的数字配置"
            return
        }
        val validationError = validatePressureLogic("普通患者", normal)
            ?: validatePressureLogic("低体重患者", lowWeight)
        if (validationError != null) {
            status.setTextColor(0xFFB91C1C.toInt())
            status.text = validationError
            return
        }

        status.setTextColor(0xFF475569.toInt())
        status.text = "正在保存到云端数据库..."
        lifecycleScope.launch {
            try {
                val hospital = currentHospital ?: error("当前医院信息不存在")
                val saved = withContext(Dispatchers.IO) {
                    configApi.savePressureLogicSettings(
                        hospitalId = hospital.id,
                        hospitalCode = hospital.code,
                        updatedByUserId = currentUser?.id,
                        configs = listOf(
                            normal.toCloudPressureLogicConfig(PATIENT_TYPE_NORMAL),
                            lowWeight.toCloudPressureLogicConfig(PATIENT_TYPE_LOW_WEIGHT),
                        ),
                    )
                }
                if (!applyCloudPressureLogicSettings(saved)) {
                    applyPressureLogicSettings(normal, lowWeight)
                }
                status.setTextColor(0xFF166534.toInt())
                status.text = "已保存到云端数据库，新的判断逻辑已生效"
            } catch (error: Exception) {
                status.setTextColor(0xFFB91C1C.toInt())
                status.text = "保存到云端数据库失败：${error.message.orEmpty()}"
            }
        }
    }

    private fun readPressureLogicInputs(inputs: PressureLogicInputs): PressureLogicConfig? {
        val inBed = inputs.inBedThreshold.text.toString().trim().toIntOrNull()
        val twoPoint = inputs.twoPointInBedThreshold.text.toString().trim().toIntOrNull()
        val left = inputs.leftThreshold.text.toString().trim().toIntOrNull()
        val count = inputs.leftConfirmPackets.text.toString().trim().toIntOrNull()
        if (inBed == null || twoPoint == null || left == null || count == null) return null
        return PressureLogicConfig(inBed, twoPoint, left, count)
    }

    private fun setPressureLogicInputs(inputs: PressureLogicInputs, config: PressureLogicConfig) {
        inputs.inBedThreshold.setText(config.inBedThreshold.toString())
        inputs.twoPointInBedThreshold.setText(config.twoPointInBedThreshold.toString())
        inputs.leftThreshold.setText(config.leftThreshold.toString())
        inputs.leftConfirmPackets.setText(config.leftConfirmPackets.toString())
    }

    private fun validatePressureLogic(label: String, config: PressureLogicConfig): String? {
        return when {
            config.inBedThreshold <= config.twoPointInBedThreshold -> "$label：单点在床阈值必须大于双点在床阈值"
            config.twoPointInBedThreshold <= config.leftThreshold -> "$label：双点在床阈值必须大于离床低压阈值"
            config.leftConfirmPackets < 1 -> "$label：低压计数必须大于等于 1"
            else -> null
        }
    }

    private fun resetPressureLogicSettings() {
        prefs.edit()
            .remove(KEY_PRESSURE_NORMAL_IN_BED)
            .remove(KEY_PRESSURE_NORMAL_TWO_POINT)
            .remove(KEY_PRESSURE_NORMAL_LEFT)
            .remove(KEY_PRESSURE_NORMAL_COUNT)
            .remove(KEY_PRESSURE_LOW_WEIGHT_IN_BED)
            .remove(KEY_PRESSURE_LOW_WEIGHT_TWO_POINT)
            .remove(KEY_PRESSURE_LOW_WEIGHT_LEFT)
            .remove(KEY_PRESSURE_LOW_WEIGHT_COUNT)
            .putInt(KEY_PRESSURE_CONFIG_VERSION, PRESSURE_CONFIG_VERSION)
            .apply()
        resetPressureCounters()
    }

    private fun resetPressureCounters() {
        runtimes.values.forEach { runtime ->
            runtime.pressureLowPacketCount = 0
        }
    }

    private fun pressureLogicConfig(lowWeight: Boolean): PressureLogicConfig {
        return if (lowWeight) {
            PressureLogicConfig(
                inBedThreshold = prefs.getInt(KEY_PRESSURE_LOW_WEIGHT_IN_BED, DEFAULT_LOW_WEIGHT_PRESSURE_IN_BED_THRESHOLD),
                twoPointInBedThreshold = prefs.getInt(KEY_PRESSURE_LOW_WEIGHT_TWO_POINT, DEFAULT_LOW_WEIGHT_PRESSURE_TWO_POINT_THRESHOLD),
                leftThreshold = prefs.getInt(KEY_PRESSURE_LOW_WEIGHT_LEFT, DEFAULT_LOW_WEIGHT_PRESSURE_LEFT_THRESHOLD),
                leftConfirmPackets = prefs.getInt(KEY_PRESSURE_LOW_WEIGHT_COUNT, DEFAULT_LOW_WEIGHT_PRESSURE_LEFT_CONFIRM_PACKETS),
            )
        } else {
            PressureLogicConfig(
                inBedThreshold = prefs.getInt(KEY_PRESSURE_NORMAL_IN_BED, DEFAULT_PRESSURE_IN_BED_THRESHOLD),
                twoPointInBedThreshold = prefs.getInt(KEY_PRESSURE_NORMAL_TWO_POINT, DEFAULT_PRESSURE_TWO_POINT_THRESHOLD),
                leftThreshold = prefs.getInt(KEY_PRESSURE_NORMAL_LEFT, DEFAULT_PRESSURE_LEFT_THRESHOLD),
                leftConfirmPackets = prefs.getInt(KEY_PRESSURE_NORMAL_COUNT, DEFAULT_PRESSURE_LEFT_CONFIRM_PACKETS),
            )
        }
    }

    private fun defaultPressureLogicConfig(lowWeight: Boolean): PressureLogicConfig {
        return if (lowWeight) {
            PressureLogicConfig(
                inBedThreshold = DEFAULT_LOW_WEIGHT_PRESSURE_IN_BED_THRESHOLD,
                twoPointInBedThreshold = DEFAULT_LOW_WEIGHT_PRESSURE_TWO_POINT_THRESHOLD,
                leftThreshold = DEFAULT_LOW_WEIGHT_PRESSURE_LEFT_THRESHOLD,
                leftConfirmPackets = DEFAULT_LOW_WEIGHT_PRESSURE_LEFT_CONFIRM_PACKETS,
            )
        } else {
            PressureLogicConfig(
                inBedThreshold = DEFAULT_PRESSURE_IN_BED_THRESHOLD,
                twoPointInBedThreshold = DEFAULT_PRESSURE_TWO_POINT_THRESHOLD,
                leftThreshold = DEFAULT_PRESSURE_LEFT_THRESHOLD,
                leftConfirmPackets = DEFAULT_PRESSURE_LEFT_CONFIRM_PACKETS,
            )
        }
    }

    private fun migratePressureLogicSettings() {
        if (prefs.getInt(KEY_PRESSURE_CONFIG_VERSION, 0) >= PRESSURE_CONFIG_VERSION) return
        prefs.edit()
            .putInt(KEY_PRESSURE_NORMAL_IN_BED, DEFAULT_PRESSURE_IN_BED_THRESHOLD)
            .putInt(KEY_PRESSURE_NORMAL_TWO_POINT, DEFAULT_PRESSURE_TWO_POINT_THRESHOLD)
            .putInt(KEY_PRESSURE_NORMAL_LEFT, DEFAULT_PRESSURE_LEFT_THRESHOLD)
            .putInt(KEY_PRESSURE_NORMAL_COUNT, DEFAULT_PRESSURE_LEFT_CONFIRM_PACKETS)
            .putInt(KEY_PRESSURE_LOW_WEIGHT_IN_BED, DEFAULT_LOW_WEIGHT_PRESSURE_IN_BED_THRESHOLD)
            .putInt(KEY_PRESSURE_LOW_WEIGHT_TWO_POINT, DEFAULT_LOW_WEIGHT_PRESSURE_TWO_POINT_THRESHOLD)
            .putInt(KEY_PRESSURE_LOW_WEIGHT_LEFT, DEFAULT_LOW_WEIGHT_PRESSURE_LEFT_THRESHOLD)
            .putInt(KEY_PRESSURE_LOW_WEIGHT_COUNT, DEFAULT_LOW_WEIGHT_PRESSURE_LEFT_CONFIRM_PACKETS)
            .putInt(KEY_PRESSURE_CONFIG_VERSION, PRESSURE_CONFIG_VERSION)
            .apply()
    }

    private fun lowWeightSettingsPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 0xFFD6E2DA.toInt(), 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "低体重患者设置"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF0F172A.toInt())
                includeFontPadding = false
                setPadding(0, 0, 0, dp(8))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            beds.forEach { bed ->
                addView(lowWeightBedRow(bed, bed.lowWeightPatient), blockLp(bottom = 6))
            }
        }
    }

    private fun lowWeightBedRow(bed: BedConfig, checked: Boolean): LinearLayout {
        val checkbox = CheckBox(this).apply {
            isChecked = checked
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF16A34A.toInt(), 0xFF94A3B8.toInt()),
            )
            contentDescription = "${bedDisplayName(bed)}低体重患者"
        }
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            setLowWeightBedLocally(bed, isChecked)
        }

        val labelBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = bedDisplayName(bed)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1F2937.toInt())
                includeFontPadding = false
            })
            addView(TextView(this@MainActivity).apply {
                text = BedDisplayUtils.deviceHexLabel(bed)
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
                includeFontPadding = false
                setPadding(0, dp(3), 0, 0)
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFFF8FBF8.toInt(), 0xFFE1E8E3.toInt(), 8)
            setPadding(dp(8), dp(7), dp(10), dp(7))
            isClickable = true
            isFocusable = true
            addView(checkbox, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(labelBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    private fun setLowWeightBedLocally(bed: BedConfig, enabled: Boolean) {
        val hospital = currentHospital ?: return
        val bedIndex = beds.indexOfFirst { it.stableId == bed.stableId }
        if (bedIndex < 0) return
        if (beds[bedIndex].lowWeightPatient == enabled) return

        val updatedBed = beds[bedIndex].copy(lowWeightPatient = enabled)
        beds[bedIndex] = updatedBed
        currentHospital = hospital.copy(beds = beds.toList())
        persistLocalLowWeightPatients(hospital.code, beds)
        settingsStatusText?.apply {
            text = "选择已变更，请点击保存"
            setTextColor(0xFFB45309.toInt())
        }
    }

    private fun saveLowWeightBeds() {
        val hospital = currentHospital ?: return
        val savingBeds = beds.toList()
        settingsStatusText?.text = "正在保存到服务器数据库..."

        bedConfigWriteInProgress = true
        lifecycleScope.launch {
            try {
                val savedSettings = withContext(Dispatchers.IO) {
                    configApi.saveLowWeightSettings(
                        hospitalId = hospital.id,
                        hospitalCode = hospital.code,
                        updatedByUserId = currentUser?.id,
                        beds = savingBeds,
                    )
                }
                val merged = mergeLowWeightSettings(hospital.copy(beds = savingBeds), savedSettings)
                beds.clear()
                beds += merged.beds
                currentHospital = merged.copy(beds = beds.toList())
                persistLocalLowWeightPatients(hospital.code, beds)
                val message = "已保存到 bedalarm.86086.cn 的 bedalarm 数据库"
                settingsStatusText?.text = message
                if (showingSettings) {
                    showSettings(message)
                } else {
                    renderBeds()
                }
            } catch (error: Exception) {
                val message = "保存到服务器数据库失败：${error.message.orEmpty()}"
                settingsStatusText?.text = message
                if (showingSettings) {
                    showSettings(message)
                } else {
                    renderBeds()
                }
            } finally {
                bedConfigWriteInProgress = false
            }
        }
    }

    private fun localLowWeightPatient(hospitalCode: String, bed: BedConfig): Boolean {
        return prefs.getBoolean(lowWeightPatientKey(hospitalCode, bed), bed.lowWeightPatient)
    }

    private fun persistLocalLowWeightPatients(hospitalCode: String, sourceBeds: List<BedConfig>) {
        val editor = prefs.edit()
        sourceBeds.forEach { bed ->
            editor.putBoolean(lowWeightPatientKey(hospitalCode, bed), bed.lowWeightPatient)
        }
        editor.apply()
    }

    private fun lowWeightPatientKey(hospitalCode: String, bed: BedConfig): String {
        return "$KEY_LOW_WEIGHT_LOCAL_PREFIX${hospitalCode}_${lowWeightPositionKey(bed)}"
    }

    private fun lowWeightPositionKey(bed: BedConfig): String =
        lowWeightPositionKey(bed.bedIndex, bed.bedLabel)

    private fun lowWeightPositionKey(bedIndex: Int, bedLabel: String): String =
        "$bedIndex:${bedLabel.trim()}"

    private fun bedDisplayName(bed: BedConfig): String {
        val label = bed.bedLabel.trim().ifBlank { bed.bedIndex.toString() }
        return if (label.endsWith("床")) label else "${label}号床"
    }

    private fun statusLegend(): GridLayout {
        return GridLayout(this).apply {
            columnCount = 4
            addView(legendItem("在床", BedVisualState.IN_BED), weightedGridLp(margin = 0))
            addView(legendItem("离床报警", BedVisualState.ALERT), weightedGridLp(margin = 0))
            addView(legendItem("确认离床", BedVisualState.ACKNOWLEDGED), weightedGridLp(margin = 0))
            addView(legendItem("长久离床", BedVisualState.LONG_ABSENCE), weightedGridLp(margin = 0))
        }
    }

    private fun legendItem(label: String, state: BedVisualState): LinearLayout {
        val colors = bedStateColors(state)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1), dp(2), dp(1))
            addView(View(this@MainActivity).apply {
                background = rounded(colors.fill, colors.stroke, 3)
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                rightMargin = dp(3)
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(0xFF0F172A.toInt())
                includeFontPadding = false
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun summaryCards(): GridLayout {
        return GridLayout(this).apply {
            columnCount = 4
            addView(summaryCard("在床", 0xFF4CAF7D.toInt()) { inBedCountText = it }, weightedGridLp(height = dp(72), margin = 3))
            addView(summaryCard("报警中", 0xFFE53935.toInt()) { alertCountText = it }, weightedGridLp(height = dp(72), margin = 3))
            addView(summaryCard("确认离床", 0xFFC2185B.toInt()) { acknowledgedCountText = it }, weightedGridLp(height = dp(72), margin = 3))
            addView(summaryCard("长久离床", 0xFF9E9E9E.toInt()) { longAbsenceCountText = it }, weightedGridLp(height = dp(72), margin = 3))
        }
    }

    private fun summaryCard(label: String, numberColor: Int, bindNumber: (TextView) -> Unit): LinearLayout {
        val number = TextView(this).apply {
            text = "0"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(numberColor)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        bindNumber(number)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 0xFFE2E8F0.toInt(), 8)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(0xFF0F172A.toInt())
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })
        }
    }

    private fun systemStatusCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 0xFFE2E8F0.toInt(), 8)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            val statusText = TextView(this@MainActivity).apply {
                text = "系统正常运行，暂无报警"
                textSize = 16f
                setTextColor(0xFF0F172A.toInt())
                includeFontPadding = false
            }
            systemStatusText = statusText
            addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun weightedGridLp(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, margin: Int = 0): GridLayout.LayoutParams {
        return GridLayout.LayoutParams().apply {
            width = 0
            this.height = height
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(margin), dp(margin), dp(margin), dp(margin))
        }
    }

    private fun prefString(key: String, fallback: String): String {
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun logout() {
        mqttRetryJob?.cancel()
        token = null
        prefs.edit().remove(KEY_TOKEN).apply()
        showLogin()
    }

    private fun handleAuthFailure(error: Exception) {
        val message = error.message.orEmpty()
        if (message.contains("401") || message.contains("403")) {
            token = null
            prefs.edit().remove(KEY_TOKEN).apply()
            showLogin()
        }
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message.ifBlank { "未知错误" })
            .setPositiveButton("确定", null)
            .show()
    }

    private fun screenRoot(): ScrollView {
        return ScrollView(this).apply {
            setBackgroundColor(0xFFF0F7F2.toInt())
            isFillViewport = true
        }
    }

    private fun paddedColumn(padding: Int, top: Int = 22, bottom: Int = 22): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(top), dp(padding), dp(bottom))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun horizontalRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = blockLp(bottom = 3)
        }
    }

    private fun bigTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
            includeFontPadding = false
        }
    }

    private fun dashboardTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0F172A.toInt())
            includeFontPadding = false
        }
    }

    private fun dashboardInfoText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(2), 0, 0)
            includeFontPadding = false
        }
    }

    private fun subtitle(text: String): TextView {
        return smallText(text, 0xFF475569.toInt()).apply {
            textSize = 15f
            setPadding(0, dp(6), 0, dp(18))
        }
    }

    private fun smallText(text: String, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            setPadding(0, dp(5), 0, dp(5))
        }
    }

    private fun input(label: String, value: String, secret: Boolean = false, number: Boolean = false): Pair<TextView, EditText> {
        val labelView = smallText(label, 0xFF334155.toInt())
        val edit = EditText(this).apply {
            setText(value)
            hint = label
            setSingleLine(true)
            textSize = 16f
            setTextColor(0xFF0F172A.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = rounded(0xFFFFFFFF.toInt(), 0xFFCBD5E1.toInt(), 8)
            setPadding(dp(12), 0, dp(12), 0)
            minHeight = dp(48)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
            layoutParams = blockLp(bottom = 8)
        }
        return labelView to edit
    }

    private fun primaryButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(0xFF2563EB.toInt(), Color.TRANSPARENT, 8)
            minHeight = dp(48)
            layoutParams = blockLp(top = 8, bottom = 10)
        }
    }

    private fun smallButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            setTextColor(0xFF0F172A.toInt())
            background = rounded(0xFFFFFFFF.toInt(), 0xFFCBD5E1.toInt(), 8)
            minHeight = dp(38)
            minWidth = dp(70)
        }
    }

    private fun settingsButton(): ImageButton {
        return ImageButton(this).apply {
            contentDescription = "设置"
            setImageResource(R.drawable.ic_settings)
            setColorFilter(0xFF334155.toInt())
            background = rounded(0xFFFFFFFF.toInt(), 0xFFD6E2DA.toInt(), 8)
            elevation = dp(2).toFloat()
            minimumWidth = dp(34)
            minimumHeight = dp(34)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
    }

    private fun logoutButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = raisedLogoutBackground()
            minHeight = dp(30)
            minWidth = dp(58)
            elevation = dp(4).toFloat()
            translationZ = dp(1).toFloat()
            setPadding(dp(8), 0, dp(8), dp(2))
        }
    }

    private fun raisedLogoutBackground(): android.graphics.drawable.Drawable {
        val shadow = rounded(0xFF8F1D1D.toInt(), Color.TRANSPARENT, 7)
        val face = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFFF7A59.toInt(), 0xFFE53935.toInt(), 0xFFB91C1C.toInt()),
        ).apply {
            cornerRadius = dp(7).toFloat()
            setStroke(dp(1), 0xFFFFB4A2.toInt())
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(shadow, face)).apply {
            setLayerInset(1, 0, 0, 0, dp(3))
        }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) {
                setStroke(dp(1), stroke)
            }
        }
    }

    private fun blockLp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val apiBaseUrl: String
        get() = getString(R.string.config_api_base_url)

    private val bedAlarmConfigApiBaseUrl: String
        get() = getString(R.string.config_bedalarm_config_api_base_url)

    private val mqttHost: String
        get() = getString(R.string.config_mqtt_host)

    private val mqttPort: Int
        get() = resources.getInteger(R.integer.config_mqtt_port)

    private val mqttUsername: String
        get() = getString(R.string.config_mqtt_username)

    private val mqttPassword: String
        get() = getString(R.string.config_mqtt_password)

    private val defaultLoginUsername: String
        get() = getString(R.string.config_default_login_username).trim()

    private val defaultHospitalCode: String
        get() = getString(R.string.config_default_hospital_code).trim()

    companion object {
        private const val DASHBOARD_HORIZONTAL_PADDING_DP = 14
        private const val BED_SQUARE_MIN_DP = 82
        private const val BED_SQUARE_MARGIN_DP = 5
        private val DASHBOARD_BACKGROUND_COLOR = 0xFFF0F7F2.toInt()
        private const val DEFAULT_PRESSURE_IN_BED_THRESHOLD = 135
        private const val DEFAULT_PRESSURE_TWO_POINT_THRESHOLD = 95
        private const val DEFAULT_PRESSURE_LEFT_THRESHOLD = 80
        private const val DEFAULT_PRESSURE_LEFT_CONFIRM_PACKETS = 2
        private const val DEFAULT_LOW_WEIGHT_PRESSURE_IN_BED_THRESHOLD = 95
        private const val DEFAULT_LOW_WEIGHT_PRESSURE_TWO_POINT_THRESHOLD = 80
        private const val DEFAULT_LOW_WEIGHT_PRESSURE_LEFT_THRESHOLD = 75
        private const val DEFAULT_LOW_WEIGHT_PRESSURE_LEFT_CONFIRM_PACKETS = 2
        private const val LEAVE_ALARM_CONFIRM_DELAY_MS = 0L
        private const val LEAVE_ALARM_VOICE_MS = 10_000L
        private const val LONG_ABSENCE_MS = 10L * 60L * 1000L
        private const val MQTT_RECONNECT_DELAY_MS = 5_000L
        private const val CLOUD_CONFIG_SYNC_INTERVAL_MS = 5_000L
        private const val DYNAMIC_PROFILE_CAPTURE_DELAY_MS = 10L * 60L * 1000L
        private const val DYNAMIC_PROFILE_CAPTURE_DURATION_MS = 2L * 60L * 1000L
        private const val DYNAMIC_PROFILE_CAPTURE_MAX_SAMPLES = 300
        private const val DYNAMIC_EMPTY_BASELINE_REFRESH_MS = 24L * 60L * 60L * 1000L
        private const val DYNAMIC_IN_BED_TEMPLATE_REFRESH_MS = 2L * 60L * 60L * 1000L
        private const val DYNAMIC_MIN_TEMPLATE_DELTA_SUM = 8
        private const val DYNAMIC_IN_BED_SUM_RATIO = 0.35
        private const val DYNAMIC_IN_BED_MAX_RATIO = 0.45
        private const val DYNAMIC_IN_BED_TOP2_RATIO = 0.40
        private const val DYNAMIC_LEFT_SUM_RATIO = 0.20
        private const val DYNAMIC_LEFT_MAX_RATIO = 0.30
        private const val DYNAMIC_LEFT_TOP2_RATIO = 0.25
        private const val DYNAMIC_LEFT_CONFIRM_PACKETS_MIN = 2
        private const val ENGINEER_PASSWORD = "goodluck"
        private const val PRESSURE_CONFIG_VERSION = 2
        private const val PATIENT_TYPE_NORMAL = "NORMAL"
        private const val PATIENT_TYPE_LOW_WEIGHT = "LOW_WEIGHT"
        private const val KEY_TOKEN = "token"
        private const val KEY_LAST_USERNAME = "last_username"
        private const val KEY_PRESSURE_CONFIG_VERSION = "pressure_config_version"
        private const val KEY_PRESSURE_NORMAL_IN_BED = "pressure_normal_in_bed"
        private const val KEY_PRESSURE_NORMAL_TWO_POINT = "pressure_normal_two_point"
        private const val KEY_PRESSURE_NORMAL_LEFT = "pressure_normal_left"
        private const val KEY_PRESSURE_NORMAL_COUNT = "pressure_normal_count"
        private const val KEY_PRESSURE_LOW_WEIGHT_IN_BED = "pressure_low_weight_in_bed"
        private const val KEY_PRESSURE_LOW_WEIGHT_TWO_POINT = "pressure_low_weight_two_point"
        private const val KEY_PRESSURE_LOW_WEIGHT_LEFT = "pressure_low_weight_left"
        private const val KEY_PRESSURE_LOW_WEIGHT_COUNT = "pressure_low_weight_count"
        private const val KEY_LOW_WEIGHT_LOCAL_PREFIX = "low_weight_local_"
        private const val KEY_DYNAMIC_PRESSURE_PROFILE_PREFIX = "dynamic_pressure_profile_"
    }

    private enum class BedVisualState(val badge: String) {
        IN_BED("在床"),
        ALERT("离床"),
        ACKNOWLEDGED("离床"),
        LONG_ABSENCE("长久"),
        IDLE("离线"),
    }

    private data class StartupCloudConfig(
        val detail: HospitalDetail,
        val pressureLogicSettings: Map<String, CloudPressureLogicConfig>,
        val dynamicPressureProfiles: List<CloudDynamicPressureProfile>,
    )

    private data class CloudBedConfigRefresh(
        val detail: HospitalDetail,
        val dynamicPressureProfiles: List<CloudDynamicPressureProfile>,
    )

    private data class BedGridSpec(
        val columns: Int,
        val squareSizePx: Int,
        val marginPx: Int,
    )

    private data class BedStateColors(
        val fill: Int,
        val stroke: Int,
        val text: Int,
        val badgeFill: Int,
        val badgeText: Int,
    )

    private data class PressureLogicConfig(
        val inBedThreshold: Int,
        val twoPointInBedThreshold: Int,
        val leftThreshold: Int,
        val leftConfirmPackets: Int,
    )

    private data class PressureLogicInputs(
        val inBedThreshold: EditText,
        val twoPointInBedThreshold: EditText,
        val leftThreshold: EditText,
        val leftConfirmPackets: EditText,
    )

    private data class DynamicPressureProfile(
        val bedId: Long,
        val bedIndex: Int,
        val bedLabel: String,
        val emptyBaseline: List<Int> = emptyList(),
        val inBedTemplate: List<Int> = emptyList(),
        val baselineUpdateDate: String? = null,
        val templateUpdateDate: String? = null,
        val baselineUpdatedAt: String? = null,
        val templateUpdatedAt: String? = null,
        val updatedAt: String? = null,
    )

    private data class PressureProfileSamplingState(
        var statusKey: String,
        var startedAtMs: Long,
        var captureStartedAtMs: Long = 0L,
        var lastSampleAtMs: Long = 0L,
        val samples: MutableList<List<Int>> = mutableListOf(),
    )

    private data class DynamicPressureRatios(
        val sumRatio: Double,
        val maxRatio: Double,
        val top2Ratio: Double,
    )
}
