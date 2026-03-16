package youyou.monitor.config.repository

import youyou.monitor.config.model.MonitorConfig

/**
 * 远程配置拉取器（适配层）
 *
 * monitor-config 不直接依赖具体网络实现（例如 WebDAV），
 * 由上层模块注入该接口实现。
 */
fun interface RemoteConfigFetcher {
    suspend fun fetch(currentConfig: MonitorConfig): Result<MonitorConfig>
}
