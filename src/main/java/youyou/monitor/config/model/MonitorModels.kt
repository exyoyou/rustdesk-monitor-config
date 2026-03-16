package youyou.monitor.config.model

/**
 * WebDAV 服务器配置
 */
data class WebDavServer(
    val url: String,
    val username: String,
    val password: String,
    val monitorDir: String = "Monitor",
    val remoteUploadDir: String = "Monitor/upload",
    val templateDir: String = "Templates"
)

/**
 * 监控配置
 */
data class MonitorConfig(
    val matchThreshold: Double = 0.92,
    val matchCooldownMs: Long = 3000L,
    val detectPerSecond: Long = 1L,
    val maxStorageSizeMB: Int = 1024,
    val screenshotDir: String = "ScreenCaptures",
    val videoDir: String = "ScreenRecord",
    val templateDir: String = "Templates",
    val matcherType: String = "grayscale",
    val preferExternalStorage: Boolean = false,
    val rootDir: String = "PingerLove",
    val webdavServers: List<WebDavServer> = emptyList()
) {
    companion object {
        fun default() = MonitorConfig()
    }
}
