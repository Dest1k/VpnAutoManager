package com.vpnauto.manager.model

import com.google.gson.Gson
import android.util.Base64
import java.net.URLDecoder

data class ServerConfig(
    val raw: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val name: String,
    var pingMs: Long = -1L,
    var isReachable: Boolean = false
)

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val mirrorUrls: List<String> = emptyList(),
    val type: SubscriptionType,
    var lastUpdated: Long = 0L,
    var serverCount: Int = 0,
    var isEnabled: Boolean = true
)

enum class SubscriptionType {
    BLACK_VLESS_MOBILE, BLACK_VLESS_FULL, BLACK_SS_ALL,
    WHITE_CIDR_MOBILE, WHITE_CIDR_ALL, WHITE_CIDR_CHECKED, WHITE_SNI_ALL, CUSTOM
}

data class AppState(
    var connectedServer: ServerConfig? = null,
    var isConnected: Boolean = false,
    var lastUpdateTime: Long = 0L,
    var updateIntervalHours: Int = 2
)

object ConfigParser {

    fun parseSubscription(rawContent: String): List<ServerConfig> {
        // Попробовать Clash YAML формат
        if (rawContent.contains("proxies:") || rawContent.trimStart().startsWith("proxy-groups:")) {
            val clashServers = parseClashYaml(rawContent)
            if (clashServers.isNotEmpty()) return clashServers
        }
        val lines = tryDecodeBase64(rawContent) ?: rawContent.lines()
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { parseUri(it) }
    }

    private fun tryDecodeBase64(raw: String): List<String>? {
        return try {
            val clean = raw.trim().replace("\n", "").replace("\r", "")
            val decoded = String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
            val lines = decoded.lines()
            if (lines.any { l -> l.startsWith("vless://") || l.startsWith("vmess://")
                        || l.startsWith("ss://") || l.startsWith("trojan://") }) lines
            else null
        } catch (e: Exception) { null }
    }

    fun parseUri(uri: String): ServerConfig? {
        return try {
            when {
                uri.startsWith("vless://")    -> parseVless(uri)
                uri.startsWith("vmess://")    -> parseVmess(uri)
                uri.startsWith("ss://")       -> parseShadowsocks(uri)
                uri.startsWith("trojan://")   -> parseTrojan(uri)
                uri.startsWith("hysteria2://") || uri.startsWith("hy2://") -> parseHysteria2(uri)
                uri.startsWith("tuic://")     -> parseTuic(uri)
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun parseVless(uri: String): ServerConfig? {
        val withoutScheme = uri.removePrefix("vless://")
        val hashIdx = withoutScheme.lastIndexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(withoutScheme.substring(hashIdx + 1), "UTF-8") else ""
        val main = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val atIdx = main.indexOf('@')
        if (atIdx < 0) return null
        val hostPort = main.substring(atIdx + 1).substringBefore('?')
        val (host, port) = splitHostPort(hostPort) ?: return null
        return ServerConfig(uri, "vless", host, port, name.ifEmpty { "$host:$port" })
    }

    private fun parseVmess(uri: String): ServerConfig? {
        val b64 = uri.removePrefix("vmess://")
        val json = String(Base64.decode(b64 + "==", Base64.DEFAULT), Charsets.UTF_8)
        val map = Gson().fromJson(json, Map::class.java)
        val host = map["add"] as? String ?: return null
        val port = (map["port"] as? Double)?.toInt() ?: (map["port"] as? String)?.toInt() ?: return null
        val name = (map["ps"] as? String) ?: "$host:$port"
        return ServerConfig(uri, "vmess", host, port, name)
    }

    private fun parseShadowsocks(uri: String): ServerConfig? {
        val withoutScheme = uri.removePrefix("ss://")
        val hashIdx = withoutScheme.lastIndexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(withoutScheme.substring(hashIdx + 1), "UTF-8") else ""
        val main = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val atIdx = main.indexOf('@')
        return if (atIdx >= 0) {
            val hostPort = main.substring(atIdx + 1).substringBefore('?')
            val (host, port) = splitHostPort(hostPort) ?: return null
            ServerConfig(uri, "ss", host, port, name.ifEmpty { "$host:$port" })
        } else {
            val decoded = String(Base64.decode(main + "==", Base64.DEFAULT), Charsets.UTF_8)
            val parts = decoded.split('@')
            if (parts.size < 2) return null
            val (host, port) = splitHostPort(parts[1]) ?: return null
            ServerConfig(uri, "ss", host, port, name.ifEmpty { "$host:$port" })
        }
    }

    private fun parseTrojan(uri: String): ServerConfig? {
        val withoutScheme = uri.removePrefix("trojan://")
        val hashIdx = withoutScheme.lastIndexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(withoutScheme.substring(hashIdx + 1), "UTF-8") else ""
        val main = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val atIdx = main.indexOf('@')
        if (atIdx < 0) return null
        val hostPort = main.substring(atIdx + 1).substringBefore('?')
        val (host, port) = splitHostPort(hostPort) ?: return null
        return ServerConfig(uri, "trojan", host, port, name.ifEmpty { "$host:$port" })
    }

    private fun parseHysteria2(uri: String): ServerConfig? {
        val withoutScheme = uri.removePrefix("hysteria2://").removePrefix("hy2://")
        val hashIdx = withoutScheme.lastIndexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(withoutScheme.substring(hashIdx + 1), "UTF-8") else ""
        val main = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val hostPart = main.substringAfter('@').ifEmpty { main }.substringBefore('?')
        val (host, port) = splitHostPort(hostPart) ?: return null
        return ServerConfig(uri, "hysteria2", host, port, name.ifEmpty { "$host:$port" })
    }

    private fun parseTuic(uri: String): ServerConfig? {
        val withoutScheme = uri.removePrefix("tuic://")
        val hashIdx = withoutScheme.lastIndexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(withoutScheme.substring(hashIdx + 1), "UTF-8") else ""
        val main = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val atIdx = main.lastIndexOf('@')
        if (atIdx < 0) return null
        val hostPort = main.substring(atIdx + 1).substringBefore('?')
        val (host, port) = splitHostPort(hostPort) ?: return null
        return ServerConfig(uri, "tuic", host, port, name.ifEmpty { "$host:$port" })
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        if (hostPort.startsWith('[')) {
            val bracket = hostPort.lastIndexOf(']')
            if (bracket < 0) return null
            val host = hostPort.substring(1, bracket)
            val port = hostPort.substring(bracket + 2).toIntOrNull() ?: return null
            return Pair(host, port)
        }
        val lastColon = hostPort.lastIndexOf(':')
        if (lastColon < 0) return null
        val host = hostPort.substring(0, lastColon)
        val port = hostPort.substring(lastColon + 1).toIntOrNull() ?: return null
        return Pair(host, port)
    }

    /**
     * Парсит Clash YAML формат (proxies: [...]).
     * Поддерживает ss, vmess, trojan, vless типы.
     */
    private fun parseClashYaml(content: String): List<ServerConfig> {
        val results = mutableListOf<ServerConfig>()
        val proxiesIdx = content.indexOf("proxies:")
        if (proxiesIdx < 0) return emptyList()
        val proxiesBlock = content.substring(proxiesIdx)
        // Split on "  - " to get individual proxy entries
        val proxyBlocks = proxiesBlock.split("\n  - ").drop(1)
        // Helper: extract a YAML scalar field value
        fun yamlField(block: String, key: String): String? {
            val prefix = "$key:"
            val idx = block.indexOf(prefix)
            if (idx < 0) return null
            return block.substring(idx + prefix.length)
                .trimStart()
                .substringBefore('\n')
                .substringBefore(',')
                .substringBefore('}')
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .ifEmpty { null }
        }
        for (block in proxyBlocks) {
            try {
                val name   = yamlField(block, "name")   ?: continue
                val type   = yamlField(block, "type")   ?: continue
                val server = yamlField(block, "server") ?: continue
                val port   = yamlField(block, "port")?.toIntOrNull() ?: continue
                val proto  = when (type.lowercase()) {
                    "ss"              -> "ss"
                    "vmess"           -> "vmess"
                    "trojan"          -> "trojan"
                    "vless"           -> "vless"
                    "hysteria2","hy2" -> "hysteria2"
                    "tuic"            -> "tuic"
                    else              -> continue
                }
                results.add(ServerConfig(block, proto, server, port, name))
            } catch (_: Exception) {}
        }
        return results
    }

}
