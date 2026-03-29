package youyou.monitor.config.model

/**
 * WebDAV 服务器配置
 */
data class WebDavServer(
    /** WebDAV 服务地址（例如: https://example.com/dav） */
    val url: String,
    /** WebDAV 登录用户名 */
    val username: String,
    /** WebDAV 登录密码 */
    val password: String,
    /** 监控主目录（服务端逻辑目录名） */
    val monitorDir: String = "Monitor",
    /** 上传目录（图片/视频/日志等上传目标目录） */
    val remoteUploadDir: String = "Monitor/upload",
    /** 模板目录（模板同步目录） */
    val templateDir: String = "Templates"
)

/**
 * 定位轨迹配置
 *
 * 推荐值（生产环境）：
 * - movingIntervalMs: 10_000 ~ 15_000
 * - staticIntervalMs: 60_000 ~ 120_000
 * - movementThresholdMeters: 20.0 ~ 50.0
 * - maxAcceptAccuracyMeters: 80f ~ 150f
 * - rawRetentionDays: 2 ~ 7
 * - rawDedupMinDistanceMeters: 5.0 ~ 15.0
 * - rawDedupMinIntervalMs: 10_000 ~ 30_000
 */
data class LocationTrackConfig(
    /** 是否需要逆地理信息（地址/POI） */
    val needAddress: Boolean = true,
    /** 是否启用“单次定位 + 间隔轮询”模式 */
    val singleShotPolling: Boolean = true,
    /** 是否仅在设备空闲（锁屏/熄屏）时采集 */
    val idleOnly: Boolean = true,
    /** 是否启用“运动/静止”自适应采样间隔 */
    val adaptiveInterval: Boolean = true,
    /** 运动状态下定位间隔（毫秒） */
    val movingIntervalMs: Long = 10_000L,
    /** 静止状态下定位间隔（毫秒） */
    val staticIntervalMs: Long = 60_000L,
    /** 运动判定基础阈值（米） */
    val movementThresholdMeters: Double = 30.0,
    /** 可接受最大定位精度（米，越大越宽松） */
    val maxAcceptAccuracyMeters: Float = 120f,
    /** 原始轨迹文件保留天数（超过后清理） */
    val rawRetentionDays: Int = 2,
    /** 原始点去重最小位移阈值（米） */
    val rawDedupMinDistanceMeters: Double = 8.0,
    /** 原始点去重最小时间间隔（毫秒） */
    val rawDedupMinIntervalMs: Long = 15_000L,
    /** 停留点判定半径（米） */
    val stayRadiusMeters: Double = 50.0,
    /** 停留点最小时长（毫秒） */
    val stayMinDurationMs: Long = 5 * 60 * 1000L
)

/**
 * 监控配置
 */
data class MonitorConfig(
    /** 模板匹配阈值（0~1，越高越严格） */
    val matchThreshold: Double = 0.92,
    /** 连续命中冷却时间（毫秒） */
    val matchCooldownMs: Long = 3000L,
    /** 每秒检测次数 */
    val detectPerSecond: Long = 1L,
    /** 最大存储容量（MB） */
    val maxStorageSizeMB: Int = 1024,
    /** 截图存储子目录 */
    val screenshotDir: String = "ScreenCaptures",
    /** 录像存储子目录 */
    val videoDir: String = "ScreenRecord",
    /** 模板存储子目录 */
    val templateDir: String = "Templates",
    /** 匹配算法类型（如 grayscale） */
    val matcherType: String = "grayscale",
    /** 是否优先使用外部存储 */
    val preferExternalStorage: Boolean = false,
    /** 根目录名称 */
    val rootDir: String = "PingerLove",
    /** 定位轨迹相关配置 */
    val locationTrack: LocationTrackConfig = LocationTrackConfig(),
    /** WebDAV 服务器列表（支持多服务器） */
    val webdavServers: List<WebDavServer> = emptyList()
) {
    companion object {
        fun default() = MonitorConfig()
    }
}
