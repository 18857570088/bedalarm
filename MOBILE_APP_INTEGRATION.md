# 床位监护系统 — 手机 APP 对接文档

> 版本：与 `bed-web-server` / `sleep-server` 当前代码一致  
> 面向：原生 Android / iOS 客户端开发  
> Web 参考实现：`bed-web-server/src/`

---

## 1. 系统架构

```text
┌─────────────┐     HTTPS REST      ┌──────────────┐
│  手机 APP   │◄──────────────────►│ sleep-server │  :5000 /api
│             │                     │  (Spring)    │
│             │     MQTT 订阅       └──────┬───────┘
│             │◄──────────────────────────┤
└─────────────┘                           │
                                    MQTT Broker
                                    (1883 TCP / 8083·8084 WS)
                                           ▲
                                    4G/WiFi 网关设备
```

APP 需对接两条链路：

| 链路 | 用途 |
|------|------|
| **REST API** | 登录、医院/床位配置、历史数据、睡眠报告 |
| **MQTT 实时流** | 床位实时心率/呼吸/姿态/在离床，驱动看板与报警 |

---

## 2. 环境与地址
host：86086.cn
| 项目 | 默认值 | 说明 |
|------|--------|------|
| API Base URL | `https://{host}/api` | 生产域名由运维提供 |
| API 端口 | `5000` | 直连后端时使用 |
| MQTT TCP | `{host}:1883` | **原生 APP 推荐** |
| MQTT WebSocket | `ws://{host}:8083/mqtt` | HTTP 页面 |
| MQTT WSS | `wss://{host}:8084/mqtt` | HTTPS 页面 |
| Swagger | `{host}/swagger-ui.html` | 在线 API 文档 |
| OpenAPI JSON | `{host}/api-docs` | 机器可读接口描述 |

> MQTT 账号密码以部署环境为准。Web 端默认见 `mqttBed.js`；后端 `application.properties` 中亦有配置，**上线前请向后端/运维确认**，勿写死在 APP 里。

---

## 3. REST API

### 3.1 通用约定

- **Content-Type**：`application/json`
- **认证**：除登录/注册外，请求头携带  
  `Authorization: Bearer {token}`
- **401 / 403**：Token 失效或无权限，应跳转登录
- **时区**：历史数据接口时间参数为 **UTC+8（北京时间）**，格式 `yyyy-MM-dd HH:mm:ss`

### 3.2 认证

#### POST `/api/auth/login`

请求：

```json
{
  "username": "admin",
  "password": "******"
}
```

响应：

```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "user": {
    "id": 1,
    "username": "admin",
    "displayName": "管理员",
    "role": "ADMIN",
    "hospitalIds": [1, 2, 3]
  }
}
```

`role` 常见值：`ADMIN`（管理员）、`USER`（普通用户）、`VIEWER`（只读）。

#### POST `/api/auth/register`

```json
{
  "username": "nurse01",
  "password": "******",
  "displayName": "张护士"
}
```

响应结构同登录。

#### GET `/api/users/me`

获取当前登录用户（需 Token）。

---

### 3.3 医院与床位（看板核心）

#### GET `/api/hospitals`

返回当前用户可见的医院列表（不含床位明细）。

```json
[
  {
    "id": 1,
    "name": "北京市医院",
    "code": "beijing",
    "sortOrder": 1
  }
]
```

#### GET `/api/hospitals/by-code/{code}`

**进入某医院看板时调用**，返回医院信息 + 全部床位配置。

```json
{
  "id": 1,
  "name": "北京市医院",
  "code": "beijing",
  "sortOrder": 1,
  "beds": [
    {
      "id": 10,
      "bedIndex": 1,
      "bedLabel": "12",
      "patientName": "张三",
      "fallRiskLevel": "level_2",
      "department": "内科",
      "boundGatewayId": 74,
      "boundSensorId": 0,
      "heartRateAlarmMin": 50,
      "heartRateAlarmMax": 120,
      "breathRateAlarmMin": 8,
      "breathRateAlarmMax": 30,
      "leaveBedAlarm": true,
      "leaveBedAlarmStartMinutes": 1320,
      "leaveBedAlarmEndMinutes": 480,
      "lowWeightPatient": false,
      "leaveAlarmAcknowledged": false
    }
  ]
}
```

#### PUT `/api/hospitals/{hospitalId}/beds`

保存床位配置（全量替换）。请求体为 `BedConfigDto[]`，字段同上。  
Web 端参考：`Dashboard.vue` → `chartConfigsToDto()`。

---

### 3.4 床位配置字段说明（BedConfigDto）

| 字段 | 类型 | 说明 |
|------|------|------|
| `bedIndex` | int | 内部序号，从 1 起 |
| `bedLabel` | string | **展示床号**，如 `"12"`、`"305房12"` |
| `patientName` | string | 患者姓名（**APP 展示需脱敏**，见 §6.3） |
| `fallRiskLevel` | string | 防跌等级，见 §6.2 |
| `department` | string | 科室 |
| `boundGatewayId` | int | 绑定网关 ID（十进制，对应 MQTT 帧内 `gatewayId`） |
| `boundSensorId` | int | 绑定传感器 ID（十进制，对应 MQTT 帧内 `sensorId`） |
| `heartRateAlarmMin/Max` | int | 心率报警阈值（次/分） |
| `breathRateAlarmMin/Max` | int | 呼吸报警阈值（次/分） |
| `leaveBedAlarm` | bool | 是否启用离床报警 |
| `leaveBedAlarmStartMinutes` | int | 报警时段开始，**当天 0 点起的分钟数**（0~1439）。默认 1320 = 22:00 |
| `leaveBedAlarmEndMinutes` | int | 报警时段结束。默认 480 = 08:00。可跨日（start > end） |
| `lowWeightPatient` | bool | 是否为低体重患者；APP 勾选后通过床位配置接口保存到云端 |
| `leaveAlarmAcknowledged` | bool | 当前离床报警是否已确认；APP 点击红色报警床位后写入云端，患者恢复在床后清零 |

**传感器绑定**：`boundGatewayId` 与 `boundSensorId` 均为 **0** 表示未绑定。  
展示格式：`0x{GW}-0x{SN}`（4 位十六进制），例如 `0x004A-0x0000`。

**设备编号（查历史用）**：

```text
deviceNumber = "6978864830016" + pad6(gatewayId) + "_6978864830023" + pad6(sensorId)
```

示例：`gatewayId=74, sensorId=0` → `697886483001600074_6978864830023000000`  
Web 参考：`Dashboard.vue` → `getDeviceNumber()`。

---

### 3.5 历史数据与睡眠报告（详情页）

#### GET `/api/devices/{device_number}`

查询设备历史体征。

| 参数 | 说明 |
|------|------|
| `start_time` / `end_time` | `yyyy-MM-dd HH:mm:ss` |
| `limit` / `offset` | 分页 |
| `sort_order` | `ASC` / `DESC` |

#### GET `/api/sleep/analysis`

睡眠分析报告。

| 参数 | 说明 |
|------|------|
| `deviceNumber` | 同上 |
| `startTime` / `endTime` | `yyyy-MM-dd HH:mm:ss` |

---

## 4. MQTT 实时数据

### 4.1 连接参数

| 项 | 值 |
|----|-----|
| 协议 | MQTT 3.1.1 |
| 原生 APP | TCP `host:1883` |
| Web | WebSocket `/mqtt` |
| QoS | 0 |
| Payload | **二进制**（非 JSON） |

**订阅主题**（三选一或全部订阅）：

```text
/wm/sxu/200/4g/pub
/wm/sxu/200/wifi/pub
/wm/sxu/200/net/pub
```

Web 参考：`src/services/mqttBed.js`。

### 4.2 数据处理流程

```text
MQTT message (binary)
  → 按 C5 5C 帧头切分（可能粘包多帧）
  → 根据 datalen 识别帧类型
  → Modbus CRC-16 校验
  → 解析为统一 frame 对象
  → 用 gatewayId + sensorId 匹配床位 boundGatewayId + boundSensorId
  → 更新 UI / 触发报警
```

**解析参考代码**（可直接移植或对照实现）：

| 文件 | 内容 |
|------|------|
| `src/protocol/parseFrame.js` | 切帧、分发、旧网关 80/182 帧 |
| `src/protocol/meshFrame.js` | Mesh 45/120 字节帧 |
| `src/protocol/occupancyFrame.js` | 在离床 19 字节帧 |
| `SENSOR_MESH_PROTOCOL.md` | Mesh 协议详细说明 |

### 4.3 帧类型一览

所有帧帧头均为 **`C5 5C`**，第 3 字节为 `datalen`。

| datalen | 整帧长度 | 协议标识 | 说明 |
|---------|----------|----------|------|
| 40 | 45 | `mesh` | Mesh 简化帧（无波形） |
| 115 | 120 | `mesh` | Mesh 完整帧（含 50 点 12bit 心电） |
| 14 或 15 | 19 | `occupancy4pressure` | 4 路 ADC + 在离床状态 |
| ≥ 0xB0 (176) | 182 | `legacy` | 旧网关完整帧 |
| 其他 | 80 | `legacy` | 旧网关简化帧 |

### 4.4 统一解析结果（frame 对象）

解析成功后，各协议统一为如下结构（字段可能因协议为空/0）：

```typescript
interface ParsedFrame {
  protocol: 'mesh' | 'legacy' | 'occupancy4pressure'
  gatewayId: number      // 目标地址 / 网关 ID（小端 uint16）
  sensorId: number       // 源地址 / 传感器 ID（小端 uint16）
  heartRate: number      // 原始值：Mesh/Legacy 为 bpm×10，展示需 /10 四舍五入
  respiration: number    // 原始值：bpm×10 或 9bit 呼吸，展示需 /10
  posture: number        // 0~7，见 §5.1
  pressure28: number[]   // 28 路压力（0~255 或 ADC 映射值）
  temperature: number[]  // 4 路，单位 0.1℃ 整数（legacy/mesh）；occupancy 无
  showTemperature: boolean
  heartRateWave: number[] // 50 点；mesh 120B / legacy 182B 有值
  sequenceId: number     // mesh: uint8；legacy 182: uint16
  occupancyPresent?: boolean  // 仅 occupancy4pressure
  adcUint16?: number[]        // 仅 occupancy4pressure，4 路 12bit ADC
}
```

> Web 端在 `updateChartFromMqttFrame` 中：`heartRate = round(frame.heartRate / 10)`，`breathRate = round(frame.respiration / 10)`。

### 4.5 床位匹配规则

```text
当 bed.boundGatewayId !== 0 或 bed.boundSensorId !== 0：
  frame.gatewayId === bed.boundGatewayId
  AND frame.sensorId === bed.boundSensorId
→ 更新该床位
```

### 4.6 离线判定

```text
若 boundGatewayId/boundSensorId 已绑定：
  lastMqttTime 为空 或 距现在 > 60 秒 → 离线
未绑定 → 显示「未绑定」，不算离线
```

---

## 5. 业务逻辑（与 Web 看板一致）

### 5.1 姿态码 posture

| 值 | 含义 | 卡片展示 |
|----|------|----------|
| 0 | 无人 | 无人 / **离床**（occupancy 协议） |
| 1 | 仰卧 | 在床 |
| 2 | 左侧卧 | 在床 |
| 3 | 右侧卧 | 在床 |
| 4 | 体动 | 体动 |
| 5 | 体位异常 | 体位异常 |
| 6 | 异常 | 体位异常 |
| 7 | 设备异常 | 设备异常 |

**occupancy4pressure 协议**：用 `occupancyPresent`（`occupancyRaw !== 0`）判断  
- `true` → 在床  
- `false` → 离床  

### 5.2 当前状态与持续时长

- **状态键**：occupancy 协议看 `hasPerson`；其他看 `posture > 0` → 在床，否则离床/无人
- **持续时长**：状态变化时记录 `statusSince = 当前时间戳`，展示「在床 12分30秒」/「离床 5分10秒」
- 参考：`bedDisplayUtils.js` → `getBedOccupancyStatusKey`、`formatStatusDurationLabel`

### 5.3 离床报警

同时满足才触发：

1. `leaveBedAlarm === true`
2. 床位在线（非未绑定、非离线）
3. 当前在报警时段内（支持跨日，见 `isWithinLeaveBedAlarmWindow`）
4. `posture === 0`（occupancy 协议下离床时 posture 也为 0）

同一轮报警只播报一次语音；多床同时离床时，Web 选 **lastMqttTime 最新** 的一床播报。

### 5.4 体征报警

同时满足：

1. 在线
2. 心率或呼吸 > 0
3. 超出 `heartRateAlarmMin/Max` 或 `breathRateAlarmMin/Max`

occupancy 协议无心率呼吸，不触发体征报警。

---

## 6. 展示与语音规范

### 6.1 床位卡片字段

| 展示项 | 来源 |
|--------|------|
| 床号 | `bedLabel` + 「号床」 |
| 患者姓名 | `patientName` **脱敏** |
| 防跌等级 | `fallRiskLevel` 标签 |
| 当前状态 | posture / 在离床 |
| 状态时长 | 前端计算 |
| 心率/呼吸 | MQTT 实时值 |
| 温度 | MQTT（occupancy 无） |

### 6.2 防跌风险等级 fallRiskLevel

| 系统值 | 别名 | 展示名 | 离床播报语 |
|--------|------|--------|------------|
| `level_1` | `normal` | 一般防跌 | `{床号}患者离床。` |
| `level_2` | `medium` | 重点防跌 | `{床号}重点防跌患者离床，请护士查看。` |
| `level_3` | `high` | 高危防跌 | `{床号}高危防跌患者离床，请立即处理。` |

默认 `level_1`。床号格式见 `riskLevelUtils.js` → `formatBedSpeechPart`（支持 `305房12` 等形式）。

### 6.3 姓名脱敏规则

```text
""        → "--"
1 字      → "*"
2 字      → "张*"
3 字      → "张*明"
≥4 字     → 首字 + (len-2)个* + 末字，如 "欧阳娜娜" → "欧**娜"
```

参考：`bedDisplayUtils.js` → `formatDesensitizedName`。

### 6.4 语音播报（建议 APP 用原生 TTS）

Web 使用 Web Speech API；**原生 APP 强烈建议使用 Android TextToSpeech / iOS AVSpeechSynthesizer**，无需浏览器「解锁」。

#### 离床报警 — 一轮节奏

```text
连续 3 次 → 静默 10s → 1 次 → 静默 20s → 1 次
```

常量见 `leaveBedSpeech.js`：

- `ALARM_SPEECH_FIRST_BURST_COUNT = 3`
- `ALARM_SPEECH_GAP_AFTER_FIRST_BURST_MS = 10000`
- `ALARM_SPEECH_GAP_BEFORE_FINAL_MS = 20000`

#### 体征报警文案模板

```text
{床号}{姓名}{异常描述}，请留意
```

异常描述示例：`心率过低，当前55次每分`、`呼吸过高，当前35次每分`（可组合）。

参考：`leaveBedSpeech.js` → `buildVitalAlarmText`。

#### 振动（可选）

移动端报警时可调用：`vibrate([200, 100, 200])` 毫秒。

---

## 7. 二进制协议摘要

### 7.1 公共：Modbus CRC-16

- 多项式 `0xA001`，初值 `0xFFFF`
- 覆盖范围：帧首到序号字节（含），不含 CRC 本身
- CRC 存储：**小端**（低字节在前）

参考实现：`meshFrame.js` → `modbusCRC16()`。

### 7.2 Mesh 帧（45 / 120 字节）

```text
[C5][5C][datalen][dst_lo][dst_hi][src_lo][src_hi][payload...][seq][crc_lo][crc_hi]
```

- `gatewayId` = dst（小端 uint16）
- `sensorId` = src（小端 uint16）
- payload 前 28 字节：压力；28~31：温度；32~34：vitals 打包（呼吸 9bit + 心率 11bit + 姿态 4bit）
- 120 字节帧：payload 35~109 为 75 字节 12bit 心电波形（50 点）

完整说明见 **`SENSOR_MESH_PROTOCOL.md`**。

### 7.3 在离床帧（19 字节）

```text
偏移  字段
0-1   C5 5C
2     datalen (14 或 15)
3-4   gatewayId (LE)
5-6   sensorId (LE)
7-14  4× uint16 ADC (LE)
15    occupancyRaw (0=离床, 非0=在床)
16    seq (uint8)
17-18 CRC (LE)
```

### 7.4 旧网关帧（80 / 182 字节）

- 80 字节：无温度、无心电波形
- 182 字节：28 路压力 + 4 路温度 + 50 点心电 uint16 + uint16 序号

字节偏移见 `parseFrame.js` → `FRAME_FORMAT.POSITIONS`。

---

## 8. APP 推荐实现顺序

1. **登录** → 存 Token  
2. **GET /hospitals** → 选医院  
3. **GET /hospitals/by-code/{code}** → 渲染床位列表  
4. **连接 MQTT** → 订阅三主题 → 解析二进制帧  
5. **按 gatewayId/sensorId 更新床位** → 计算离线/报警  
6. **原生 TTS 播报** → 按 §6.4 文案与节奏  
7. （可选）详情页 → **GET /devices/{deviceNumber}**、**GET /sleep/analysis**

---

## 9. 参考文件索引

| 路径 | 说明 |
|------|------|
| `bed-web-server/src/services/api.js` | REST 封装 |
| `bed-web-server/src/services/mqttBed.js` | MQTT 连接 |
| `bed-web-server/src/protocol/` | 二进制协议解析 |
| `bed-web-server/SENSOR_MESH_PROTOCOL.md` | Mesh 协议原文 |
| `bed-web-server/src/views/Dashboard.vue` | 看板状态机、MQTT 更新、报警调度 |
| `bed-web-server/src/utils/bedDisplayUtils.js` | 状态/报警/脱敏/时长 |
| `bed-web-server/src/utils/riskLevelUtils.js` | 防跌等级与离床播报 |
| `bed-web-server/src/utils/leaveBedSpeech.js` | 语音队列与播报节奏 |
| `sleep-server/.../dto/BedConfigDto.java` | 床位配置后端模型 |
| `sleep-server/src/main/resources/application.properties` | 端口与 MQTT 配置 |

---

## 10. 常见问题

**Q：MQTT 收到数据但床位不更新？**  
检查 `boundGatewayId` / `boundSensorId` 是否与帧内 `gatewayId` / `sensorId` 一致（十进制整数，非 hex 字符串）。

**Q：心率显示偏大 10 倍？**  
原始字段为 `bpm×10`，展示前需 `/10` 并四舍五入。

**Q：粘包怎么处理？**  
一次 MQTT payload 可能含多帧，必须按 `C5 5C` + `datalen` 循环切分。见 `splitBinaryFrames()`。

**Q：语音在 Web 无声，APP 怎么办？**  
Web 受浏览器 autoplay 限制；APP 用 **原生 TTS** 无此问题。

**Q：密码写在哪？**  
向运维索取生产环境 API 地址、MQTT 账号；不要从仓库硬编码进 APP，建议远程配置或构建变体。

---

*文档维护：前端/Web 协议变更时请同步更新本文档。*
