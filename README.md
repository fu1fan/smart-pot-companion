# Smart Pot Companion

一个由 ESP32-P4 智能盆栽、Kotlin/Ktor 服务端和 Jetpack Compose Android 客户端组成的可运行原型。三端通过统一 JSON 协议通信，设备与服务端使用 MQTT，App 与服务端使用 REST + WebSocket。

## 已实现

- 实时土壤湿度、环境光照、Wi-Fi 在线状态、阈值判断与趋势图。
- 50 种常见植物档案、专属湿度/光照阈值、养护知识、提醒与养护日志。
- 远程文字、12 种内置表情、屏幕亮度、回复音量、待机、重启和隔空触摸；命令带 ACK。
- 好感度和等级、用户记忆、云端 AI 对话、每日盆栽日记。
- 一次性分享码和受限访客令牌，可让两人共同查看和互动。
- PostgreSQL 持久化、分钟级遥测聚合和 90 天自动清理；无数据库时自动使用内存模式。

## 目录

- `kotlin/shared-protocol`：三端数据模型、MQTT 消息和植物状态规则。
- `kotlin/server`：Ktor API、WebSocket、MQTT 网关、PostgreSQL、告警、AI 和分享服务。
- `kotlin/androidApp`：原生 Android Compose App。
- `esp32/smart_pot_esp32p4`：ESP-IDF 固件与 MQTT 云接入。
- `infra`：PostgreSQL、Mosquitto 和服务端的一键 Docker Compose 环境。

## 本地启动

需要 Docker Compose。先复制 `.env.example` 为 `.env`，至少修改三个令牌/密码；AI 功能需要填写 `DEEPSEEK_API_KEY`。

```powershell
docker compose --env-file .env -f infra/docker-compose.yml up --build
```

健康检查：`http://localhost:8080/health`。Android App 默认访问统一服务器 `http://103.236.87.90:18080`，默认演示令牌为 `smart-pot-demo-token`。本地调试或临时环境可在构建时传入地址和令牌：

```powershell
./gradlew.bat :androidApp:assembleDebug -PSMART_POT_SERVER_URL=http://192.168.1.2:8080 -PSMART_POT_DEMO_TOKEN=your-token
```

### 不使用 Docker

```powershell
cd kotlin
./gradlew.bat :server:run
./gradlew.bat :androidApp:assembleDebug
```

未设置 `DATABASE_URL` 时服务端使用内存存储；未启动 MQTT 时设置 `MQTT_ENABLED=false`。调试 APK 位于 `kotlin/androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

## ESP32 接入

在 `esp32/smart_pot_esp32p4` 运行 `idf.py menuconfig`，进入 **Smart Pot**：

1. 设置 Wi-Fi SSID 和密码。
2. 启用 `SMART_POT_CLOUD_ENABLE`，设置唯一 `SMART_POT_DEVICE_ID`。
3. MQTT URI 默认已设为 `mqtt://103.236.87.90:1883`，用户名为 `smartpot`；填入服务器 `.env` 中的 `MQTT_PASSWORD`。
4. LLM endpoint 默认已设为 `http://103.236.87.90:18080/v1/chat/completions`；bearer token 填服务器 `.env` 中的 `DEMO_TOKEN`。
5. `idf.py build flash monitor`。

Wi-Fi 密码、MQTT 密码和 bearer token 不提交到公开仓库；每台构建设备需要在 `menuconfig` 中单独填写。

设备主题约定为 `smartpot/v1/devices/{deviceId}/...`，包含 `telemetry`、`reported`、`desired`、`commands`、`acks`、`events` 和 `online`。固件每 2 秒上传传感器数据；服务端按分钟覆盖聚合，降低数据库写入和历史数据体积。

## 服务器镜像与自动更新

服务端镜像由 GitHub Actions 构建并发布到 GitHub Container Registry：
`ghcr.io/fu1fan/smart-pot-companion-server:latest`。生产环境 Compose 中的
Watchtower 仅监控带标签的服务端容器，每分钟检查一次；发现新镜像后会
自动拉取并重启服务端，因此部署服务器不需要执行 Gradle 构建。

PostgreSQL 只绑定回环地址。公网防火墙和云安全组只需开放 `API_PORT`
指定的 HTTP API 端口与启用认证的 MQTT 1883 端口。

## 构建验证

```powershell
cd kotlin
./gradlew.bat test :server:installDist :androidApp:assembleDebug
```

ESP32 工程已按 ESP-IDF 5.5.4 构建。首次构建会下载受管组件；该板载 Wi-Fi 使用 ESP32-C6 从机配置。

## 原型边界

当前是可演示 MVP：主人认证使用静态 bearer token，Mosquitto 本地配置允许匿名连接，告警是 App 本地通知，尚未接入短信登录、FCM/APNs 或生产级设备证书。部署到公网前必须更换令牌、启用 HTTPS/MQTTS、为每台设备签发独立凭证，并将用户记忆与聊天内容纳入隐私授权、删除和备份机制。
