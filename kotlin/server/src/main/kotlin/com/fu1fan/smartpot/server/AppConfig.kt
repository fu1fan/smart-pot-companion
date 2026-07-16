package com.fu1fan.smartpot.server

data class AppConfig(
    val port: Int,
    val demoToken: String,
    val shareTokenSecret: String,
    val databaseUrl: String?,
    val databaseUser: String,
    val databasePassword: String,
    val mqttHost: String,
    val mqttPort: Int,
    val mqttUsername: String?,
    val mqttPassword: String?,
    val mqttTls: Boolean,
    val deepSeekEndpoint: String,
    val deepSeekApiKey: String?,
    val deepSeekModel: String,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig = AppConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            demoToken = env["DEMO_TOKEN"] ?: "smart-pot-demo-token",
            shareTokenSecret = env["SHARE_TOKEN_SECRET"] ?: "replace-this-demo-secret",
            databaseUrl = env["DATABASE_URL"],
            databaseUser = env["DATABASE_USER"] ?: "smartpot",
            databasePassword = env["DATABASE_PASSWORD"] ?: "smartpot",
            mqttHost = env["MQTT_HOST"] ?: "localhost",
            mqttPort = env["MQTT_PORT"]?.toIntOrNull() ?: 1883,
            mqttUsername = env["MQTT_USERNAME"],
            mqttPassword = env["MQTT_PASSWORD"],
            mqttTls = env["MQTT_TLS"]?.toBooleanStrictOrNull() ?: false,
            deepSeekEndpoint = env["DEEPSEEK_ENDPOINT"] ?: "https://api.deepseek.com/chat/completions",
            deepSeekApiKey = env["DEEPSEEK_API_KEY"],
            deepSeekModel = env["DEEPSEEK_MODEL"] ?: "deepseek-chat",
        )
    }
}
