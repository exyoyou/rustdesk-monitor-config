package youyou.monitor.config.repository

import kotlinx.coroutines.flow.Flow
import youyou.monitor.config.model.MonitorConfig

/**
 * 配置仓储接口
 */
interface ConfigRepository {
    /**
     * 获取当前配置（Flow 自动更新）
     */
    fun getConfigFlow(): Flow<MonitorConfig>

    /**
     * 获取当前配置值（同步）
     */
    suspend fun getCurrentConfig(): MonitorConfig

    /**
     * 更新配置
     */
    suspend fun updateConfig(config: MonitorConfig)

    /**
     * 从远程同步配置
     */
    suspend fun syncFromRemote(): Result<Unit>
}
