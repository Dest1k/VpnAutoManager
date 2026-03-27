package com.vpnauto.manager.service

import com.vpnauto.manager.model.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    fun build(server: ServerConfig, socksPort: Int = 10808): String {
        val root = JSONObject()

        // warning — подавляет XTLS-padding/debug строки (debug генерирует их на каждый пакет)
        root.put("log", JSONObject().apply { put("loglevel", "warning") })

        // DNS: явно через direct, чтобы xray's app/dns не шёл через proxy.
        // Без явного outboundTag xray роутит внутренние DNS-запросы через
        // основной outbound (proxy), что видно в логах как [xray.system >> proxy].
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", "8.8.8.8")
                    put("port", 53)
                    put("outboundTag", "direct")
                })
                put(JSONObject().apply {
                    put("address", "1.1.1.1")
                    put("port", 53)
                    put("outboundTag", "direct")
                })
            })
        })

        // Inbound: SOCKS5 для tun2socks
        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks-in")
                put("protocol", "socks")
                put("port", socksPort)
                put("listen", "127.0.0.1")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                    put("ip", "127.0.0.1")
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http"); put("tls")
                    })
                    // routeOnly: sniffing только для роутинга, НЕ подменяет IP-назначение.
                    // Без этого xray override-ит dst с оригинального IP на sniffed-домен
                    // → делает лишний DNS-резолв → может получить другой IP.
                    put("routeOnly", true)
                })
            })
        })

        // Outbounds
        root.put("outbounds", JSONArray().apply {
            put(buildOutbound(server))
            put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
            put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
        })

        // Routing: Telegram DC → proxy (приоритет), loopback → direct
        // Приватные IP не достигают xray (они не в TUN маршрутах)
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {

                // 1. Telegram DC IP-диапазоны — принудительно через proxy
                //    (до правила UDP/443, иначе Telegram UDP блокируется)
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("ip", JSONArray().apply {
                        put("149.154.160.0/20")   // DC1-DC5
                        put("91.108.4.0/22")       // DC2
                        put("91.108.8.0/22")       // DC3
                        put("91.108.12.0/22")      // DC4
                        put("91.108.16.0/22")      // DC5
                        put("91.108.20.0/22")      // DC media
                        put("91.108.56.0/22")      // DC updates
                        put("95.161.64.0/20")      // Telegram CDN
                        put("185.76.151.0/24")     // Telegram web
                        put("2001:b28:f23d::/48")  // DC IPv6
                        put("2001:b28:f23f::/48")  // DC IPv6
                        put("2001:67c:4e8::/48")   // DC IPv6
                    })
                })
                // 2. Telegram домены — принудительно через proxy
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("domain", JSONArray().apply {
                        put("domain:telegram.org")
                        put("domain:t.me")
                        put("domain:telegram.me")
                        put("domain:telegra.ph")
                        put("domain:telesco.pe")
                        put("domain:tdesktop.com")
                        put("domain:telegram.dog")
                    })
                })

                // 3. UDP/443 (QUIC/HTTP3) → block.
                //    tun2socks пересылает UDP в xray через SOCKS5 UDP ASSOCIATE.
                //    "direct" создаёт петлю если addDisallowedApplication не сработал.
                //    "block" (blackhole) молча дропает пакет — петель нет.
                //    Chrome видит таймаут QUIC и откатывается на TCP/TLS автоматически.
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "udp")
                    put("port", "443")
                    put("outboundTag", "block")
                })

                // 4. DNS (UDP+TCP порт 53) — напрямую, без тоннеля.
                //    Убирает задержку 3-4с на каждый DNS-запрос.
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "udp,tcp")
                    put("port", "53")
                    put("outboundTag", "direct")
                })

                // 4b. DNS-over-TLS (порт 853) — напрямую.
                //    Android (Private DNS) и некоторые приложения используют DoT на 853.
                //    В логах: tcp:8.8.8.8:853 [socks-in >> proxy] — это лишние RTT
                //    через туннель и возможный сбой если сервер блокирует TLS-in-TLS.
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp")
                    put("port", "853")
                    put("outboundTag", "direct")
                })

                // 5. Loopback → direct
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply { put("127.0.0.0/8"); put("::1/128") })
                })

                // 6. Весь остальной UDP → direct.
                //    VLESS/VMess с XTLS-vision НЕ поддерживают UDP-relay.
                //    Если игровой UDP (порты 3478, 27015 и т.д.) попадает в proxy-outbound,
                //    xray молча теряет пакеты и со временем зависает → рушит весь тоннель.
                //    freedom-outbound безопасен: xray исключён из TUN через
                //    addDisallowedApplication, поэтому петля невозможна.
                //    Telegram UDP уже перехвачен правилом 1 (IP-диапазоны), сюда не доходит.
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "udp")
                    put("outboundTag", "direct")
                })
            })
        })

        return root.toString(2)
    }

    private fun buildOutbound(server: ServerConfig): JSONObject =
        when (server.protocol.lowercase()) {
            "vless"                    -> buildVless(server)
            "vmess"                    -> buildVmess(server)
            "trojan"                   -> buildTrojan(server)
            "ss", "shadowsocks"        -> buildShadowsocks(server)
            "hysteria2", "hy2"         -> buildHysteria2(server)
            "tuic"                     -> buildTuic(server)
            else                       -> buildVless(server)
        }

    // ─── VLESS ───────────────────────────────────────────────────
    private fun buildVless(server: ServerConfig): JSONObject {
        val params = parseUriParams(server.raw)
        val uuid   = extractVlessUuid(server.raw)
        if (uuid.isEmpty()) throw IllegalArgumentException("VLESS UUID пуст для ${server.name}")
        return JSONObject().apply {
            put("protocol", "vless"); put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", server.host); put("port", server.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", uuid)
                                put("encryption", "none")
                                // flow="" вызывает предупреждение "VLESS with no Flow deprecated"
                                val flow = params["flow"]
                                if (!flow.isNullOrEmpty()) put("flow", flow)
                            })
                        })
                    })
                })
            })
            put("streamSettings", buildStream(params))
        }
    }

    // ─── VMess ───────────────────────────────────────────────────
    private fun buildVmess(server: ServerConfig): JSONObject {
        val d = parseVmessJson(server.raw)
        val id = d["id"] as? String ?: ""
        val net = d["net"] as? String ?: "tcp"
        val tls = d["tls"] as? String ?: ""
        val sni = d["sni"] as? String ?: d["host"] as? String ?: ""
        val path = d["path"] as? String ?: "/"
        val host = d["host"] as? String ?: ""
        val params = mapOf("net" to net, "tls" to tls, "sni" to sni, "path" to path, "host" to host)
        return JSONObject().apply {
            put("protocol", "vmess"); put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", server.host); put("port", server.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", id); put("alterId", 0); put("security", "auto")
                            })
                        })
                    })
                })
            })
            put("streamSettings", buildStream(params))
        }
    }

    // ─── Trojan ───────────────────────────────────────────────────
    private fun buildTrojan(server: ServerConfig): JSONObject {
        val params   = parseUriParams(server.raw)
        val password = server.raw.removePrefix("trojan://").substringBefore('@').substringBefore('?')
        return JSONObject().apply {
            put("protocol", "trojan"); put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", server.host); put("port", server.port); put("password", password)
                    })
                })
            })
            put("streamSettings", buildStream(params + mapOf("tls" to "tls")))
        }
    }

    // ─── Shadowsocks ───────────────────────────────────────────────
    private fun buildShadowsocks(server: ServerConfig): JSONObject {
        val raw = server.raw.removePrefix("ss://")
        val (method, password) = try {
            val decoded = android.util.Base64.decode(
                raw.substringBefore('@').substringBefore('#'),
                android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE
            ).toString(Charsets.UTF_8)
            decoded.substringBefore(':') to decoded.substringAfter(':')
        } catch (_: Exception) {
            "chacha20-ietf-poly1305" to raw.substringBefore('@').substringAfter(':')
        }
        return JSONObject().apply {
            put("protocol", "shadowsocks"); put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", server.host); put("port", server.port)
                        put("method", method); put("password", password)
                    })
                })
            })
        }
    }

    // ─── Hysteria2 ─────────────────────────────────────────────────
    // Hysteria2 — QUIC-нативный протокол. xray управляет TLS/QUIC внутри outbound;
    // streamSettings с network:tcp здесь не используются и ломают соединение.
    private fun buildHysteria2(server: ServerConfig): JSONObject {
        val params   = parseUriParams(server.raw)
        val password = server.raw.removePrefix("hysteria2://").removePrefix("hy2://")
            .substringBefore('@').substringBefore('?')
        return JSONObject().apply {
            put("protocol", "hysteria2"); put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", server.host); put("port", server.port)
                        put("password", password)
                        val sni = params["sni"] ?: server.host
                        if (sni.isNotEmpty()) put("sni", sni)
                        if (params["insecure"] == "1") put("allowInsecure", true)
                    })
                })
            })
        }
    }

    // ─── TUIC ──────────────────────────────────────────────────────
    // TUIC — QUIC-нативный протокол. streamSettings с network:tcp здесь неприменимы.
    private fun buildTuic(server: ServerConfig): JSONObject {
        val params   = parseUriParams(server.raw)
        val userInfo = server.raw.removePrefix("tuic://").substringBefore('@')
        val uuid     = userInfo.substringBefore(':')
        val password = userInfo.substringAfter(':')
        return JSONObject().apply {
            put("protocol", "tuic"); put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", server.host); put("port", server.port)
                        put("uuid", uuid); put("password", password)
                        put("congestionControl", params["congestion_control"] ?: "bbr")
                        val sni = params["sni"] ?: server.host
                        if (sni.isNotEmpty()) put("sni", sni)
                        if (params["allow_insecure"] == "1") put("allowInsecure", true)
                    })
                })
            })
        }
    }

    // ─── Stream settings ──────────────────────────────────────────
    private fun buildStream(p: Map<String, String>): JSONObject {
        val net = p["net"] ?: p["type"] ?: "tcp"
        val tls = p["security"] ?: p["tls"] ?: ""
        val sni = p["sni"] ?: p["host"] ?: ""
        val fp  = p["fp"] ?: ""
        val pbk = p["pbk"] ?: ""
        val sid = p["sid"] ?: ""
        return JSONObject().apply {
            put("network", net)
            when (net) {
                "ws"   -> put("wsSettings", JSONObject().apply {
                    put("path", p["path"] ?: "/")
                    // "host" in "headers" deprecated since xray v1.8 → use top-level "host"
                    val wsHost = p["host"] ?: ""
                    if (wsHost.isNotEmpty()) put("host", wsHost)
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", p["serviceName"] ?: p["path"] ?: "")
                })
                "h2"   -> put("httpSettings", JSONObject().apply {
                    put("path", p["path"] ?: "/")
                    put("host", JSONArray().apply { put(p["host"] ?: "") })
                })
            }
            when {
                tls == "reality" -> put("realitySettings", JSONObject().apply {
                    put("show", false)
                    put("fingerprint", fp.ifEmpty { "chrome" })
                    put("serverName", sni)
                    put("publicKey", pbk)
                    put("shortId", sid)
                    put("spiderX", p["spx"] ?: "")
                })
                tls == "tls"     -> put("tlsSettings", JSONObject().apply {
                    put("serverName", sni)
                    put("allowInsecure", false)
                    if (fp.isNotEmpty()) put("fingerprint", fp)
                })
            }
            if (tls.isNotEmpty()) put("security", tls)
        }
    }

    // ─── Парсеры ──────────────────────────────────────────────────
    private fun parseUriParams(uri: String): Map<String, String> =
        uri.substringAfter('?').substringBefore('#').split('&').mapNotNull { part ->
            val eq = part.indexOf('='); if (eq < 0) null
            else part.substring(0, eq) to runCatching {
                java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }.getOrDefault(part.substring(eq + 1))
        }.toMap()

    private fun extractVlessUuid(uri: String): String =
        uri.removePrefix("vless://").substringBefore('@').substringBefore('?')

    @Suppress("UNCHECKED_CAST")
    private fun parseVmessJson(uri: String): Map<String, Any> = try {
        val b64 = uri.removePrefix("vmess://")
        val json = android.util.Base64.decode(
            b64 + "==", android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE
        ).toString(Charsets.UTF_8)
        val obj = org.json.JSONObject(json)
        obj.keys().asSequence().associateWith { obj.get(it) }
    } catch (_: Exception) { emptyMap() }
}
