package com.zclei.bedalarm.mqtt

import com.zclei.bedalarm.data.ParsedFrame
import com.zclei.bedalarm.protocol.BedFrameParser
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class BedMqttClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val onFrames: (List<ParsedFrame>) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private var client: MqttAsyncClient? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        val serverUri = "tcp://${host.trim()}:$port"
        val mqttClient = MqttAsyncClient(
            serverUri,
            "bed-alarm-android-${UUID.randomUUID()}",
            MemoryPersistence(),
        )
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                onStatus(if (reconnect) "MQTT 已重连" else "MQTT 已连接")
                subscribeAll(mqttClient)
            }

            override fun connectionLost(cause: Throwable?) {
                onStatus("MQTT 断开：${cause?.message.orEmpty()}")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payload = message?.payload ?: return
                val frames = BedFrameParser.parsePayload(payload)
                if (frames.isNotEmpty()) {
                    onFrames(frames)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 10
            keepAliveInterval = 30
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            if (username.isNotBlank()) userName = username
            if (this@BedMqttClient.password.isNotBlank()) {
                this.password = this@BedMqttClient.password.toCharArray()
            }
        }

        onStatus("MQTT 连接中")
        mqttClient.connect(options).waitForCompletion(15_000)
        client = mqttClient
        subscribeAll(mqttClient)
    }

    fun disconnect() {
        val current = client ?: return
        try {
            if (current.isConnected) {
                current.disconnectForcibly(500, 500)
            }
            current.close()
        } catch (_: Exception) {
            // Best-effort cleanup before reconnecting.
        } finally {
            client = null
        }
    }

    private fun subscribeAll(mqttClient: MqttAsyncClient) {
        TOPICS.forEach { topic ->
            try {
                mqttClient.subscribe(topic, 0).waitForCompletion(5_000)
            } catch (error: Exception) {
                onStatus("MQTT 订阅失败：${error.message.orEmpty()}")
            }
        }
    }

    companion object {
        val TOPICS = listOf(
            "/wm/sxu/200/4g/pub",
            "/wm/sxu/200/wifi/pub",
            "/wm/sxu/200/net/pub",
        )
    }
}
