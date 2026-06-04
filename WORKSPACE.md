# 项目工作目录

- 工作目录：`D:\2026\202605\bed alarm`
- 源代码目录：`D:\2026\202605\bed alarm\bed-alarm-android`
- 工作环境依赖目录：`D:\2026\codexwork`

## 环境变量入口

```powershell
. "D:\2026\202605\bed alarm\env.ps1"
```

该脚本会设置：

- `BED_ALARM_PROJECT_ROOT=D:\2026\202605\bed alarm`
- `BED_ALARM_SOURCE_ROOT=D:\2026\202605\bed alarm\bed-alarm-android`
- `CODEX_WORK_ROOT=D:\2026\codexwork`
- `GRADLE_USER_HOME=D:\2026\codexwork\.gradle`
- `ANDROID_USER_HOME=D:\2026\codexwork\.android`
- `ANDROID_HOME=D:\2026\codexwork\AndroidSDK`
- `ANDROID_SDK_ROOT=D:\2026\codexwork\AndroidSDK`

## 构建

```powershell
cd "D:\2026\202605\bed alarm"
.\build-debug.ps1
```

Android SDK 通过目录联接 `D:\2026\codexwork\AndroidSDK` 指向当前可用 SDK。
