# 离床报警 Android APP

原生 Kotlin Android 工程，按 `MOBILE_APP_INTEGRATION.md` 生成。

## 已实现

- 登录：`POST /api/auth/login`
- 登录成功后自动获取医院列表并进入第一家医院看板
- 医院床位看板：`GET /api/hospitals` + `GET /api/hospitals/by-code/{code}`
- MQTT TCP：默认 `86086.cn:1883`
- 订阅主题：
  - `/wm/sxu/200/4g/pub`
  - `/wm/sxu/200/wifi/pub`
  - `/wm/sxu/200/net/pub`
- 二进制帧解析：
  - Mesh 45 / 120 字节帧
  - 在离床 19 字节帧
  - 旧网关 80 / 182 字节基础兼容
- 床位匹配：`gatewayId + sensorId`
- 离线判定：60 秒未收到 MQTT
- 离床报警：Android TextToSpeech + 振动
- MQTT 连接失败或断开后自动重连
- 主界面以小方块显示所有床位，方块中间显示床位号
- 在床为舒适绿色，右上角显示“在床”
- 离床时红色闪烁，并播报“X号床离床，请注意”10 秒
- 点击离床报警方块后停止语音和闪烁，方块变为舒适浅红色
- 离床超过 10 分钟后方块变为白色
- 隐藏心率、呼吸、温度、压力、详情和手动 MQTT 控制

## 默认配置

- API Base URL：`https://api.86086.cn/api`
- BedAlarm Config API：`https://bedalarm.86086.cn/bedalarm-config/api`
- MQTT Host：`86086.cn`
- MQTT Port：`1883`
- MQTT Username：`admin`
- MQTT Password：配置在 `app/src/main/res/values/config.xml`

API、BedAlarm Config API 与 MQTT 配置保存在 `app/src/main/res/values/config.xml`，普通用户登录页不可见、不可修改。登录页只显示用户名与密码。

## 构建

```powershell
cd "D:\2026\202605\bed alarm\bed-alarm-android"
.\gradlew.bat assembleDebug
```

Debug APK 输出：

```text
D:\2026\202605\bed alarm\bed-alarm-android\app\build\outputs\apk\debug\app-debug.apk
```

## 说明

旧网关 80 / 182 字节帧因当前目录没有 Web 端 `parseFrame.js` 参考源码，已按文档做基础解析；如果后续拿到 Web 端协议文件，可以继续对齐旧网关字段偏移。
