package youyou.monitor.config.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用内代理（monitor-proxy）配置。
 *
 * 代理使用哪种协议完全由 [coreConfigUrl] 指向的 Xray 配置文件决定，这里只放切换策略和配置文件的位置。
 * 配置里没有 `proxy` 段时使用默认值：直连优先，直连不通再从 WebDAV 取 Xray 配置；取不到配置就一直直连。
 */
data class ProxyConfig(
    /** 总开关 */
    val enabled: Boolean = true,
    /** [MODE_AUTO]：直连优先，失败再切代理；[MODE_ALWAYS]：始终走代理；[MODE_OFF]：不使用 */
    val mode: String = MODE_AUTO,
    /** 直连连续未在线多少秒后切到代理 */
    val failThresholdSec: Int = 30,
    /** 代理模式下探测直连是否恢复的间隔（秒） */
    val recoverProbeIntervalSec: Int = 300,
    /** 直连探测通过后，需要稳定在线多少秒才算恢复 */
    val recoverStableSec: Int = 60,
    /**
     * Xray 客户端配置的位置：
     * - 以 http:// 或 https:// 开头：完整下载地址；
     * - 否则是 WebDAV 服务器 monitorDir 下的相对路径（例如 xray_core.json）。
     */
    val coreConfigUrl: String = "xray_core.json",
    /** 配置文件的 sha256（十六进制）。为空表示不校验 */
    val coreConfigSha256: String = "",
    /**
     * 走代理的服务器端口。每个端口在 127.0.0.1 上开一个同端口的 dokodemo-door（TCP+UDP），
     * 转发到 ID 服务器主机的同一端口。默认是 hbbs/hbbr 的 21115、21116、21117；
     * 21118 是 RustDesk「直接 IP 访问」在本机监听的端口，不能占用。
     */
    val forwardPorts: List<Int> = listOf(21115, 21116, 21117)
) {
    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_ALWAYS = "always"
        const val MODE_OFF = "off"

        fun fromJson(obj: JSONObject?): ProxyConfig {
            if (obj == null) return ProxyConfig()
            val d = ProxyConfig()
            return ProxyConfig(
                enabled = obj.optBoolean("enabled", d.enabled),
                mode = obj.optString("mode", d.mode),
                failThresholdSec = obj.optInt("failThresholdSec", d.failThresholdSec),
                recoverProbeIntervalSec = obj.optInt("recoverProbeIntervalSec", d.recoverProbeIntervalSec),
                recoverStableSec = obj.optInt("recoverStableSec", d.recoverStableSec),
                coreConfigUrl = obj.optString("coreConfigUrl", d.coreConfigUrl),
                coreConfigSha256 = obj.optString("coreConfigSha256", d.coreConfigSha256),
                forwardPorts = obj.optJSONArray("forwardPorts")?.let { arr ->
                    (0 until arr.length()).map { arr.getInt(it) }.filter { it in 1..65535 }
                }?.takeIf { it.isNotEmpty() } ?: d.forwardPorts
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("mode", mode)
        put("failThresholdSec", failThresholdSec)
        put("recoverProbeIntervalSec", recoverProbeIntervalSec)
        put("recoverStableSec", recoverStableSec)
        put("coreConfigUrl", coreConfigUrl)
        put("coreConfigSha256", coreConfigSha256)
        put("forwardPorts", JSONArray(forwardPorts))
    }
}
