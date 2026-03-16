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
import youyou.monitor.config.model.MonitorConfig
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

    init {
        restoreConfigPathFromPrefs()
        loadLocalConfig()
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
                val json = configFile.readText()
                _configFlow.value = parseConfig(json)
                Log.i(TAG, "本地配置已加载: ${configFile.absolutePath}")
                return
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
                parseConfig(json)
            }
        } catch (e: Exception) {
            Log.w(TAG, "从 assets 加载默认配置失败: ${e.message}")
            null
        }
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

        return MonitorConfig(
            matchThreshold = obj.optDouble("matchThreshold", 0.92),
            matchCooldownMs = obj.optLong("matchCooldownMs", 3000L),
            detectPerSecond = obj.optLong("detectPerSecond", 1L),
            maxStorageSizeMB = obj.optInt("maxStorageSizeMB", 1024),
            screenshotDir = obj.optString("screenshotDir", "ScreenCaptures"),
            videoDir = obj.optString("videoDir", "ScreenRecord"),
            templateDir = obj.optString("templateDir", "Templates"),
            matcherType = obj.optString("matcherType", "grayscale"),
            preferExternalStorage = obj.optBoolean("preferExternalStorage", false),
            rootDir = obj.optString("rootDir", "PingerLove"),
            webdavServers = webdavServers
        )
    }

    private fun serializeConfig(config: MonitorConfig): String {
        val obj = JSONObject()
        obj.put("matchThreshold", config.matchThreshold)
        obj.put("matchCooldownMs", config.matchCooldownMs)
        obj.put("detectPerSecond", config.detectPerSecond)
        obj.put("maxStorageSizeMB", config.maxStorageSizeMB)
        obj.put("screenshotDir", config.screenshotDir)
        obj.put("videoDir", config.videoDir)
        obj.put("templateDir", config.templateDir)
        obj.put("matcherType", config.matcherType)
        obj.put("preferExternalStorage", config.preferExternalStorage)
        obj.put("rootDir", config.rootDir)

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

        return obj.toString(2)
    }

    private fun getConfigFile(config: MonitorConfig): File {
        val internalFile = File(context.filesDir, "${config.rootDir}/$CONFIG_FILE_NAME")

        return if (config.preferExternalStorage) {
            val externalDir = File("/storage/emulated/0/", config.rootDir)
            if (externalDir.exists() || externalDir.mkdirs()) {
                File(externalDir, CONFIG_FILE_NAME)
            } else {
                Log.w(TAG, "无法创建外部存储目录，使用内部存储")
                internalFile
            }
        } else {
            internalFile
        }
    }

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
            if (_configFlow.value != config) {
                val json = serializeConfig(config)
                configFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                configFile.writeText(json)
                _configFlow.value = config
                Log.d(TAG, "本地配置已保存")
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存本地配置失败: ${e.message}", e)
        }
    }
}
