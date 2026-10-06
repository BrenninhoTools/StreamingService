package com.brenninho.streamingservice.server

/** Server settings. In production they come from environment variables, see [fromEnv]. */
data class ServerConfig(
    val port: Int = 8080,
    val maxRooms: Int = 200,
    val maxViewersPerRoom: Int = 100,
    /** How long a room survives after its host's connection drops, so the host can reconnect. */
    val hostGraceMillis: Long = 20_000,
    /** Public address of this server (https://...). Used for the "watch" link in Discord announcements. */
    val publicUrl: String? = null,
    /** Discord webhook that receives "X is live" announcements. Unset disables announcements. */
    val discordWebhookUrl: String? = null,
    /** Discord application id, handed to the web app so it can start the Embedded App SDK inside an Activity. */
    val discordClientId: String? = null,
    /** Directory with the built web app (index.html, ...) to serve at `/`. Unset serves no web app. */
    val staticDir: String? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): ServerConfig {
            val defaults = ServerConfig()
            fun text(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            return ServerConfig(
                port = text("PORT")?.toIntOrNull() ?: defaults.port,
                maxRooms = text("MAX_ROOMS")?.toIntOrNull() ?: defaults.maxRooms,
                maxViewersPerRoom = text("MAX_VIEWERS_PER_ROOM")?.toIntOrNull() ?: defaults.maxViewersPerRoom,
                hostGraceMillis = text("HOST_GRACE_SECONDS")?.toLongOrNull()?.times(1000) ?: defaults.hostGraceMillis,
                publicUrl = text("PUBLIC_URL")?.trimEnd('/'),
                discordWebhookUrl = text("DISCORD_WEBHOOK_URL"),
                discordClientId = text("DISCORD_CLIENT_ID"),
                staticDir = text("STATIC_DIR"),
            )
        }
    }
}
