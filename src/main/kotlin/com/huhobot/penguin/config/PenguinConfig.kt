package com.huhobot.penguin.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.huhobot.penguin.qq.Intents
import net.minecraftforge.fml.loading.FMLPaths
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("PenguinServer-Forge/Config")
private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

/**
 * Forge MVP 配置。
 * 只保留 QQ <-> MC 聊天桥实际需要的配置，避免把远程命令/群管等高权限功能带进第一版。
 */
class PenguinConfig private constructor(private val raw: MutableMap<String, Any?>) {
    val botAppId: String get() = getString("bot.app-id", "")
    val botSecret: String get() = getString("bot.secret", "")
    val botName: String get() = getString("bot.name", "HuHoBot")
    val botGroups: List<String> get() = getList("bot.groups")

    val serverName: String get() = getString("serverName", "愚者")

    val chatFromGame: String get() = getString("chat-format.from-game", "[MC] <{name}> {message}")
    val chatFromGroup: String get() = getString("chat-format.from-group", "[QQ] <{name}> {message}")
    val chatPostChat: Boolean get() = getBool("chat-format.post-chat", true)
    val chatStartWith: String get() = getString("chat-format.start-with", "")

    val joinLeaveEnabled: Boolean get() = getBool("join-leave.enabled", true)
    val joinFormat: String get() = getString("join-leave.join-format", "[{server}] 🟢 {name} 进入服务器")
    val leaveFormat: String get() = getString("join-leave.leave-format", "[{server}] 🔴 {name} 退出服务器")

    private val qqIntentsRaw: Int get() = (raw["qq.intents"] as? Number)?.toInt() ?: 0
    val qqIntentsAuto: Boolean get() = getBool("qq.intents-auto", false)
    val qqIntents: Int
        get() {
            if (qqIntentsRaw != 0) return qqIntentsRaw
            return if (qqIntentsAuto) Intents.DEFAULT_GROUP else Intents.GROUP
        }

    val debugLogEvents: Boolean get() = getBool("debug.log-events", false)

    fun getString(key: String, default: String): String = raw[key]?.toString() ?: default

    fun getBool(key: String, default: Boolean): Boolean {
        val value = raw[key] ?: return default
        return when (value) {
            is Boolean -> value
            is String -> value.lowercase() in listOf("true", "1", "yes", "on")
            is Number -> value.toInt() != 0
            else -> default
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getList(key: String): List<String> {
        val value = raw[key] ?: return emptyList()
        return (value as? List<*>)?.map { it.toString() } ?: emptyList()
    }

    companion object {
        private const val CONFIG_VERSION = 1

        private val defaults: Map<String, Any?> = mapOf(
            "config-version" to CONFIG_VERSION,
            "bot.app-id" to "",
            "bot.secret" to "",
            "bot.name" to "HuHoBot",
            "bot.groups" to emptyList<String>(),
            "serverName" to "愚者",
            "chat-format.from-game" to "[MC] <{name}> {message}",
            "chat-format.from-group" to "[QQ] <{name}> {message}",
            "chat-format.post-chat" to true,
            "chat-format.start-with" to "",
            "join-leave.enabled" to true,
            "join-leave.join-format" to "[{server}] 🟢 {name} 进入服务器",
            "join-leave.leave-format" to "[{server}] 🔴 {name} 退出服务器",
            // MVP 只订阅群消息，少申请不必要的事件权限。
            "qq.intents" to 0,
            "qq.intents-auto" to false,
            "debug.log-events" to false
        )

        fun configFile(): File = FMLPaths.CONFIGDIR.get().resolve("penguin-server-forge.json").toFile()

        fun load(): PenguinConfig {
            val file = configFile()
            var nested: Map<String, Any?> = emptyMap()

            if (file.exists()) {
                try {
                    val type = object : TypeToken<Map<String, Any?>>() {}.type
                    nested = gson.fromJson(file.readText(), type) ?: emptyMap()
                } catch (e: Exception) {
                    logger.warn("读取配置失败，将补齐默认配置：${e.message}")
                }
            }

            val flat = flatten(nested, "").toMutableMap()
            var changed = !file.exists()
            for ((key, value) in defaults) {
                if (key !in flat) {
                    flat[key] = value
                    changed = true
                }
            }
            if ((flat["config-version"] as? Number)?.toInt() != CONFIG_VERSION) {
                flat["config-version"] = CONFIG_VERSION
                changed = true
            }

            if (changed) {
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(gson.toJson(nest(flat)) + "\n")
                    logger.info("配置已生成/升级：${file.absolutePath}")
                } catch (e: Exception) {
                    logger.warn("写入配置失败：${e.message}")
                }
            }

            return PenguinConfig(flat)
        }

        @Suppress("UNCHECKED_CAST")
        private fun flatten(map: Map<String, Any?>, prefix: String): Map<String, Any?> {
            val out = mutableMapOf<String, Any?>()
            for ((key, value) in map) {
                val full = if (prefix.isEmpty()) key else "$prefix.$key"
                if (value is Map<*, *> && value.isNotEmpty() && value.keys.firstOrNull() is String) {
                    out.putAll(flatten(value as Map<String, Any?>, full))
                } else {
                    out[full] = value
                }
            }
            return out
        }

        private fun nest(flat: Map<String, Any?>): Map<String, Any?> {
            val root = mutableMapOf<String, Any?>()
            for ((key, value) in flat) {
                val parts = key.split('.')
                var node = root
                for (i in 0 until parts.size - 1) {
                    val part = parts[i]
                    @Suppress("UNCHECKED_CAST")
                    node = node.getOrPut(part) { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>
                }
                node[parts.last()] = value
            }
            return root
        }
    }
}
