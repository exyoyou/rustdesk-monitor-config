package youyou.monitor.config.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import youyou.monitor.config.model.LocationTrackConfig
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.config.model.ProxyConfig
import youyou.monitor.config.model.WebDavServer
import java.io.File

/**
 * 配置仓储实现
 *
 * 功能：
 * - 本地配置文件管理
 * - 配置变更通知（Flow）
 * - 可插拔的远程配置同步（通过 RemoteConfigFetcher）
 */
class ConfigRepositoryImpl(
    private val context: Context
) : ConfigRepository {

    companion object {
        private const val TAG = "MonitorConfigRepository"
        private const val CONFIG_FILE_NAME = "config.json"
        private const val DEFAULT_CONFIG_ASSET = "monitor_config_default.json"
        private const val PREFS_NAME = "monitor_config_prefs"
        private const val PREF_CONFIG_PATH = "pref_config_file_path"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var configFile: File = File(context.filesDir, CONFIG_FILE_NAME)

    private val _configFlow = MutableStateFlow(MonitorConfig.default())

    @Volatile
    private var remoteConfigFetcher: RemoteConfigFetcher? = null

    @Volatile
    private var lastReloadedKey: String? = null

    init {
        restoreConfigPathFromPrefs()
        loadLocalConfig()
    }

    /**
     * 重新读取本地配置文件。其他实例（如远程同步）更新了文件后，用它拿到最新内容；
     * 文件不存在或解析失败时保持当前配置不变，也不会写入默认配置。
     */
    fun reload() {
        try {
            restoreConfigPathFromPrefs()
            if (configFile.exists()) {
                // 轮询调用很频繁：文件的路径、修改时间、大小都没变就不再读文件和解析。
                // 先取标识再读内容，读的过程中文件被改了只会多解析一次，不会漏。
                val key = "${configFile.absolutePath}:${configFile.lastModified()}:${configFile.length()}"
                if (key == lastReloadedKey) return
                _configFlow.value = parseConfig(configFile.readText())
                lastReloadedKey = key
            }
        } catch (e: Exception) {
            Log.w(TAG, "重新加载配置失败: ${e.message}")
        }
    }

    fun setRemoteConfigFetcher(fetcher: RemoteConfigFetcher?) {
        remoteConfigFetcher = fetcher
        Log.d(TAG, "RemoteConfigFetcher updated: ${if (fetcher != null) "set" else "null"}")
    }

    override fun getConfigFlow(): Flow<MonitorConfig> = _configFlow.asStateFlow()

    override suspend fun getCurrentConfig(): MonitorConfig = _configFlow.value

    override suspend fun updateConfig(config: MonitorConfig) {
        withContext(Dispatchers.IO) {
            try {
                updateConfigFileLocation(config)
                Log.i(TAG, "配置已更新: threshold=${config.matchThreshold}")
            } catch (e: Exception) {
                Log.e(TAG, "更新配置失败: ${e.message}", e)
            }
        }
    }

    override suspend fun syncFromRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        val fetcher = remoteConfigFetcher
            ?: return@withContext Result.failure(IllegalStateException("RemoteConfigFetcher not configured"))

        return@withContext try {
            val current = _configFlow.value
            val fetched = fetcher.fetch(current)
            fetched
                .onSuccess { remote ->
                    updateConfigFileLocation(remote)
                    Log.i(TAG, "远程配置同步成功")
                }
                .map { Unit }
        } catch (e: Exception) {
            Log.e(TAG, "从远程同步配置失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun restoreConfigPathFromPrefs() {
        try {
            val saved = prefs.getString(PREF_CONFIG_PATH, null)
            if (!saved.isNullOrEmpty()) {
                val file = File(saved)
                if (file.exists()) {
                    configFile = file
                    Log.d(TAG, "从 prefs 恢复配置路径: ${configFile.absolutePath}")
                } else {
                    prefs.edit().remove(PREF_CONFIG_PATH).apply()
                    Log.d(TAG, "保存的配置路径无效，已移除")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "恢复配置路径失败: ${e.message}")
        }
    }

    /**
     * 优先级：本地文件 > assets 默认 > 硬编码默认
     */
    private fun loadLocalConfig() {
        try {
            if (configFile.exists()) {
                try {
                    _configFlow.value = parseConfig(configFile.readText())
                    Log.i(TAG, "本地配置已加载: ${configFile.absolutePath}")
                    return
                } catch (e: Exception) {
                    // 文件损坏时如果停在硬编码默认值（没有服务器），就再也拉不到远程配置；回退到 assets 默认配置并覆盖修复
                    Log.e(TAG, "本地配置损坏，回退到默认配置: ${e.message}", e)
                }
            }

            val defaultConfig = loadDefaultConfigFromAssets()
            if (defaultConfig != null) {
                updateConfigFileLocation(defaultConfig)
                Log.i(TAG, "默认配置已从 assets 加载并保存")
                return
            }

            Log.w(TAG, "assets 默认配置加载失败，使用硬编码默认值")
        } catch (e: Exception) {
            Log.e(TAG, "加载本地配置失败: ${e.message}", e)
        }
    }

    private fun loadDefaultConfigFromAssets(): MonitorConfig? {
        return try {
            context.assets.open(DEFAULT_CONFIG_ASSET).use { input ->
                val json = input.bufferedReader().use { it.readText() }
                withBuildPassword(parseConfig(json))
            }
        } catch (e: Exception) {
            Log.w(TAG, "从 assets 加载默认配置失败: ${e.message}")
            null
        }
    }

    // 默认配置里的密码不入库，留空的由编译时注入的密码（BuildConfig 中 XOR 混淆存放）补上
    private fun withBuildPassword(config: MonitorConfig): MonitorConfig {
        val password = decodeBuildPassword(
            youyou.monitor.config.BuildConfig.DEFAULT_WEBDAV_PASSWORD_CIPHER,
            youyou.monitor.config.BuildConfig.DEFAULT_WEBDAV_PASSWORD_KEY
        )
        if (password.isEmpty()) {
            Log.w(TAG, "未配置默认 WebDAV 密码（MONITOR_WEBDAV_PASSWORD 或 local.properties 的 monitor.webdav.password）")
            return config
        }
        return config.copy(
            webdavServers = config.webdavServers.map {
                if (it.password.isEmpty()) it.copy(password = password) else it
            }
        )
    }

    // 与 build.gradle 对应：cipher 与 key 均为 hex，逐字节 XOR 还原 UTF-8 密码
    private fun decodeBuildPassword(cipherHex: String, keyHex: String): String {
        if (cipherHex.isEmpty() || cipherHex.length != keyHex.length) return ""
        val bytes = ByteArray(cipherHex.length / 2) { i ->
            val c = cipherHex.substring(i * 2, i * 2 + 2).toInt(16)
            val k = keyHex.substring(i * 2, i * 2 + 2).toInt(16)
            (c xor k).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun parseConfig(json: String): MonitorConfig {
        val obj = JSONObject(json)

        val webdavServers = mutableListOf<WebDavServer>()
        val serversArray = obj.optJSONArray("webdavServers") ?: JSONArray()
        for (i in 0 until serversArray.length()) {
            val serverObj = serversArray.getJSONObject(i)
            webdavServers.add(
                WebDavServer(
                    url = serverObj.optString("url", ""),
                    username = serverObj.optString("username", ""),
                    password = serverObj.optString("password", ""),
                    monitorDir = serverObj.optString("monitorDir", "Monitor"),
                    remoteUploadDir = serverObj.optString("remoteUploadDir", "Monitor/upload"),
                    templateDir = serverObj.optString("templateDir", "Templates")
                )
            )
        }

        val locationObj = obj.optJSONObject("locationTrack")
        val locationTrack = LocationTrackConfig(
            enabled = locationObj?.optBoolean("enabled", false) ?: false,
            needAddress = locationObj?.optBoolean("needAddress", true) ?: true,
            singleShotPolling = locationObj?.optBoolean("singleShotPolling", true) ?: true,
            idleOnly = locationObj?.optBoolean("idleOnly", true) ?: true,
            adaptiveInterval = locationObj?.optBoolean("adaptiveInterval", true) ?: true,
            movingIntervalMs = locationObj?.optLong("movingIntervalMs", 10_000L) ?: 10_000L,
            staticIntervalMs = locationObj?.optLong("staticIntervalMs", 60_000L) ?: 60_000L,
            movementThresholdMeters = locationObj?.optDouble("movementThresholdMeters", 30.0) ?: 30.0,
            maxAcceptAccuracyMeters =
                (locationObj?.optDouble("maxAcceptAccuracyMeters", 120.0) ?: 120.0).toFloat(),
            rawRetentionDays = locationObj?.optInt("rawRetentionDays", 2) ?: 2,
            rawDedupMinDistanceMeters =
                locationObj?.optDouble("rawDedupMinDistanceMeters", 8.0) ?: 8.0,
            rawDedupMinIntervalMs = locationObj?.optLong("rawDedupMinIntervalMs", 15_000L) ?: 15_000L
        )

        return MonitorConfig(
            recordEnabled = obj.optBoolean("recordEnabled", false),
            matchThreshold = obj.optDouble("matchThreshold", 0.92),
            matchCooldownMs = obj.optLong("matchCooldownMs", 3000L),
            detectPerSecond = obj.optLong("detectPerSecond", 1L),
            maxStorageSizeMB = obj.optInt("maxStorageSizeMB", 1024),
            screenshotDir = obj.optString("screenshotDir", "ScreenCaptures"),
            screenshotFormat = MonitorConfig.normalizeScreenshotFormat(obj.optString("screenshotFormat", "")),
            videoDir = obj.optString("videoDir", "ScreenRecord"),
            templateDir = obj.optString("templateDir", "Templates"),
            matcherType = obj.optString("matcherType", "grayscale"),
            preferExternalStorage = obj.optBoolean("preferExternalStorage", false),
            rootDir = obj.optString("rootDir", "PingerLove"),
            locationTrack = locationTrack,
            webdavServers = webdavServers,
            proxy = ProxyConfig.fromJson(obj.optJSONObject("proxy")),
            configSyncIntervalMinutes = obj.optInt("configSyncIntervalMinutes", 0)
        )
    }

    private fun serializeConfig(config: MonitorConfig): String {
        val obj = JSONObject()
        obj.put("recordEnabled", config.recordEnabled)
        obj.put("matchThreshold", config.matchThreshold)
        obj.put("matchCooldownMs", config.matchCooldownMs)
        obj.put("detectPerSecond", config.detectPerSecond)
        obj.put("maxStorageSizeMB", config.maxStorageSizeMB)
        obj.put("screenshotDir", config.screenshotDir)
        obj.put("screenshotFormat", config.screenshotFormat)
        obj.put("videoDir", config.videoDir)
        obj.put("templateDir", config.templateDir)
        obj.put("matcherType", config.matcherType)
        obj.put("preferExternalStorage", config.preferExternalStorage)
        obj.put("rootDir", config.rootDir)

        val locationObj = JSONObject().apply {
            put("enabled", config.locationTrack.enabled)
            put("needAddress", config.locationTrack.needAddress)
            put("singleShotPolling", config.locationTrack.singleShotPolling)
            put("idleOnly", config.locationTrack.idleOnly)
            put("adaptiveInterval", config.locationTrack.adaptiveInterval)
            put("movingIntervalMs", config.locationTrack.movingIntervalMs)
            put("staticIntervalMs", config.locationTrack.staticIntervalMs)
            put("movementThresholdMeters", config.locationTrack.movementThresholdMeters)
            put("maxAcceptAccuracyMeters", config.locationTrack.maxAcceptAccuracyMeters)
            put("rawRetentionDays", config.locationTrack.rawRetentionDays)
            put("rawDedupMinDistanceMeters", config.locationTrack.rawDedupMinDistanceMeters)
            put("rawDedupMinIntervalMs", config.locationTrack.rawDedupMinIntervalMs)
        }
        obj.put("locationTrack", locationObj)

        val serversArray = JSONArray()
        config.webdavServers.forEach { server ->
            val serverObj = JSONObject()
            serverObj.put("url", server.url)
            serverObj.put("username", server.username)
            serverObj.put("password", server.password)
            serverObj.put("monitorDir", server.monitorDir)
            serverObj.put("remoteUploadDir", server.remoteUploadDir)
            serverObj.put("templateDir", server.templateDir)
            serversArray.put(serverObj)
        }
        obj.put("webdavServers", serversArray)
        obj.put("proxy", config.proxy.toJson())
        obj.put("configSyncIntervalMinutes", config.configSyncIntervalMinutes)

        return obj.toString(2)
    }

    // 配置里有 WebDAV 密码，始终放在应用私有目录；preferExternalStorage 只影响截图等数据文件。
    // 以前写在外部存储的配置文件，会在下一次保存配置时由 updateConfigFileLocation 迁移回来并删除。
    private fun getConfigFile(config: MonitorConfig): File {
        return File(context.filesDir, "${config.rootDir}/$CONFIG_FILE_NAME")
    }

    // 远程拉取可能并发（看门狗、启动加载、WorkManager 兜底），串行化写文件
    @Synchronized
    private fun updateConfigFileLocation(config: MonitorConfig) {
        val newConfigFile = getConfigFile(config)
        if (newConfigFile != configFile) {
            if (configFile.exists()) {
                if (!newConfigFile.exists()) {
                    try {
                        newConfigFile.parentFile?.mkdirs()
                        configFile.copyTo(newConfigFile)
                        Log.i(TAG, "配置文件已迁移到新位置: ${newConfigFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "迁移配置文件失败: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "新配置文件已存在，跳过迁移")
                }
                configFile.delete()
            }
            configFile = newConfigFile
        }

        try {
            prefs.edit().putString(PREF_CONFIG_PATH, configFile.absolutePath).apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存配置路径到 prefs 失败: ${e.message}")
        }

        try {
            val changed = _configFlow.value != config
            val json = serializeConfig(config)
            configFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            // 先写临时文件再改名，写到一半被杀也不会留下损坏的配置
            // 临时文件名每次唯一：同进程里还有 monitor-proxy 的另一个实例，不能共用一个临时文件
            val tmp = File.createTempFile(configFile.name, ".tmp", configFile.parentFile)
            tmp.writeText(json)
            if (!tmp.renameTo(configFile)) {
                configFile.writeText(json)
                tmp.delete()
            }
            if (changed) {
                _configFlow.value = config
            }
            Log.d(TAG, "本地配置已保存")
        } catch (e: Exception) {
            Log.e(TAG, "保存本地配置失败: ${e.message}", e)
        }
    }
}
