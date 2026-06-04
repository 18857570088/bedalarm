package com.zclei.bedalarm.protocol

import com.zclei.bedalarm.data.ParsedFrame
import kotlin.math.min

object BedFrameParser {
    fun parsePayload(payload: ByteArray): List<ParsedFrame> {
        return splitBinaryFrames(payload).mapNotNull { frame -> parseFrame(frame) }
    }

    fun splitBinaryFrames(payload: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var index = 0
        while (index <= payload.size - 3) {
            val header = findHeader(payload, index)
            if (header < 0 || header > payload.size - 3) break

            val datalen = payload.u8(header + 2)
            val expectedLength = expectedLength(datalen)
            if (header + expectedLength > payload.size) break

            frames += payload.copyOfRange(header, header + expectedLength)
            index = header + expectedLength
        }
        return frames
    }

    fun parseFrame(frame: ByteArray): ParsedFrame? {
        if (frame.size < 19 || frame.u8(0) != 0xC5 || frame.u8(1) != 0x5C) return null
        val datalen = frame.u8(2)
        return when {
            datalen == 40 || datalen == 115 -> parseMeshFrame(frame)
            datalen == 14 || datalen == 15 -> parseOccupancyFrame(frame)
            else -> parseLegacyFrame(frame)
        }
    }

    fun modbusCrc16(bytes: ByteArray, endExclusive: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until endExclusive) {
            crc = crc xor bytes.u8(i)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun parseMeshFrame(frame: ByteArray): ParsedFrame? {
        if (!hasValidCrc(frame)) return null
        val payloadStart = 7
        val payloadEndExclusive = frame.size - 3
        if (payloadEndExclusive - payloadStart < 35) return null

        val payload = frame.copyOfRange(payloadStart, payloadEndExclusive)
        val vitals = payload.u8(32) or (payload.u8(33) shl 8) or (payload.u8(34) shl 16)
        val respiration = vitals and 0x1FF
        val heartRate = (vitals ushr 9) and 0x7FF
        val posture = (vitals ushr 20) and 0x0F

        return ParsedFrame(
            protocol = "mesh",
            gatewayId = frame.u16Le(3),
            sensorId = frame.u16Le(5),
            heartRate = heartRate,
            respiration = respiration,
            posture = posture,
            pressure28 = payload.take(28).map { it.toInt() and 0xFF },
            temperature = payload.drop(28).take(4).map { it.toInt() and 0xFF },
            showTemperature = true,
            heartRateWave = if (payload.size >= 110) parseWave12Bit(payload, 35, 75) else emptyList(),
            sequenceId = frame.u8(frame.size - 3),
        )
    }

    private fun parseOccupancyFrame(frame: ByteArray): ParsedFrame? {
        if (frame.size < 19 || !hasValidCrc(frame)) return null
        val adc = List(4) { index -> frame.u16Le(7 + index * 2) }
        val present = frame.u8(15) != 0
        return ParsedFrame(
            protocol = "occupancy4pressure",
            gatewayId = frame.u16Le(3),
            sensorId = frame.u16Le(5),
            heartRate = 0,
            respiration = 0,
            posture = if (present) 1 else 0,
            pressure28 = adc.map { min(255, it / 16) },
            temperature = emptyList(),
            showTemperature = false,
            heartRateWave = emptyList(),
            sequenceId = frame.u8(16),
            occupancyPresent = present,
            adcUint16 = adc,
        )
    }

    private fun parseLegacyFrame(frame: ByteArray): ParsedFrame? {
        if (frame.size < 80 || !hasValidCrc(frame)) return null
        val gatewayId = frame.u16Le(3)
        val sensorId = frame.u16Le(5)
        val pressure = (7 until min(35, frame.size - 3)).map { frame.u8(it) }
        val vitalsOffset = 35
        val hasPackedVitals = frame.size > vitalsOffset + 2
        val vitals = if (hasPackedVitals) {
            frame.u8(vitalsOffset) or (frame.u8(vitalsOffset + 1) shl 8) or (frame.u8(vitalsOffset + 2) shl 16)
        } else {
            0
        }
        val waveStart = 78
        val wave = if (frame.size >= 182 && frame.size > waveStart + 100) {
            List(50) { index -> frame.u16Le(waveStart + index * 2) }
        } else {
            emptyList()
        }
        return ParsedFrame(
            protocol = "legacy",
            gatewayId = gatewayId,
            sensorId = sensorId,
            heartRate = (vitals ushr 9) and 0x7FF,
            respiration = vitals and 0x1FF,
            posture = (vitals ushr 20) and 0x0F,
            pressure28 = pressure,
            temperature = emptyList(),
            showTemperature = false,
            heartRateWave = wave,
            sequenceId = if (frame.size >= 182) frame.u16Le(frame.size - 4) else frame.u8(frame.size - 3),
        )
    }

    private fun parseWave12Bit(payload: ByteArray, start: Int, length: Int): List<Int> {
        val out = ArrayList<Int>(50)
        var index = start
        val end = min(payload.size, start + length)
        while (index + 2 < end && out.size < 50) {
            val b0 = payload.u8(index)
            val b1 = payload.u8(index + 1)
            val b2 = payload.u8(index + 2)
            out += b0 or ((b1 and 0x0F) shl 8)
            if (out.size < 50) out += ((b1 ushr 4) and 0x0F) or (b2 shl 4)
            index += 3
        }
        return out
    }

    private fun expectedLength(datalen: Int): Int {
        return when {
            datalen == 40 -> 45
            datalen == 115 -> 120
            datalen == 14 || datalen == 15 -> 19
            datalen >= 0xB0 -> 182
            else -> 80
        }
    }

    private fun hasValidCrc(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        val expected = frame.u16Le(frame.size - 2)
        val actual = modbusCrc16(frame, frame.size - 2)
        return expected == actual
    }

    private fun findHeader(bytes: ByteArray, start: Int): Int {
        var index = start
        while (index <= bytes.size - 2) {
            if (bytes.u8(index) == 0xC5 && bytes.u8(index + 1) == 0x5C) return index
            index += 1
        }
        return -1
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16Le(index: Int): Int {
        if (index + 1 >= size) return 0
        return u8(index) or (u8(index + 1) shl 8)
    }
}
