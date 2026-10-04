package com.huhobot.penguin

import com.huhobot.penguin.config.PenguinConfig
import com.huhobot.penguin.qq.GroupMessage
import com.huhobot.penguin.qq.QQClient
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.ServerChatEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Penguin 的 Forge 1.20.1 最小聊天桥。
 *
 * 第一版只做：
 * - QQ 群消息 -> MC
 * - MC 玩家聊天 -> QQ 群
 * - 玩家进/退服通知
 *
 * 不包含远程命令、白名单、群管理、AI、Addon 等高权限功能。
 */
@Mod(PenguinForgeMod.MOD_ID)
object PenguinForgeMod {
    const val MOD_ID = "penguin_server_forge"
    const val MOD_NAME = "PenguinServer-Forge"

    private val logger = LoggerFactory.getLogger(MOD_NAME)

    private lateinit var config: PenguinConfig
    private lateinit var qqClient: QQClient

    @Volatile
    private var server: MinecraftServer? = null

    /**
     * QQ 对 MC 主动消息有限制：回复某条近期群消息时应携带 msg_id。
     * 因此按群缓存最后一条群消息 id，MC -> QQ 时优先作为回复发送。
     */
    private val lastMessageIdByGroup = ConcurrentHashMap<String, String>()

    /** bot.groups 为空时，自动记住本次运行中实际收到过消息的群。 */
    private val seenGroups = ConcurrentHashMap.newKeySet<String>()

    init {
        logger.info("$MOD_NAME 正在初始化……")
        config = PenguinConfig.load()
        qqClient = QQClient(config)

        qqClient.onGroupMessage { message -> onQqGroupMessage(message) }

        MinecraftForge.EVENT_BUS.register(this)
        logger.info("$MOD_NAME 初始化完成")
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        server = event.server

        if (config.botAppId.isBlank() || config.botSecret.isBlank()) {
            logger.warn("未配置 bot.app-id / bot.secret；已生成 config/penguin-server-forge.json，QQ 网关暂不启动")
            return
        }

        qqClient.start()
        logger.info("QQ 网关启动请求已提交")
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        try {
            qqClient.stop()
        } catch (e: Exception) {
            logger.warn("停止 QQ 网关时出现异常：${e.message}")
        } finally {
            server = null
        }
    }

    @SubscribeEvent
    fun onServerChat(event: ServerChatEvent) {
        if (!config.chatPostChat) return

        val raw = event.rawText
        val prefix = config.chatStartWith
        if (prefix.isNotEmpty() && !raw.startsWith(prefix)) return

        val content = if (prefix.isEmpty()) raw else raw.removePrefix(prefix)
        if (content.isBlank()) return

        val formatted = config.chatFromGame
            .replace("{name}", event.username)
            .replace("{message}", content)

        sendToBridgeGroups(formatted)
    }

    @SubscribeEvent
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        if (!config.joinLeaveEnabled) return
        val name = event.entity.name.string
        val text = config.joinFormat
            .replace("{server}", config.serverName)
            .replace("{name}", name)
        sendToBridgeGroups(text)
    }

    @SubscribeEvent
    fun onPlayerLeave(event: PlayerEvent.PlayerLoggedOutEvent) {
        if (!config.joinLeaveEnabled) return
        val name = event.entity.name.string
        val text = config.leaveFormat
            .replace("{server}", config.serverName)
            .replace("{name}", name)
        sendToBridgeGroups(text)
    }

    private fun onQqGroupMessage(message: GroupMessage) {
        // bot.groups 非空时只桥接明确配置的群。
        if (config.botGroups.isNotEmpty() && message.groupId !in config.botGroups) return

        seenGroups.add(message.groupId)
        lastMessageIdByGroup[message.groupId] = message.id

        val displayName = message.username?.takeIf { it.isNotBlank() } ?: message.userId
        val text = config.chatFromGroup
            .replace("{name}", displayName)
            .replace("{message}", readableContent(message))

        broadcastToGame(text)

        if (config.debugLogEvents) {
            logger.info("QQ -> MC group=${message.groupId} user=$displayName")
        }
    }

    private fun readableContent(message: GroupMessage): String {
        var content = message.content

        if (content.isBlank() && !message.attachments.isNullOrEmpty()) {
            val attachment = message.attachments.firstOrNull()
            val contentType = attachment?.get("content_type") as? String
            content = when {
                contentType?.startsWith("image/") == true -> "[图片]"
                contentType == "voice" -> {
                    val asr = attachment?.get("asr_refer_text") as? String
                    if (asr.isNullOrBlank()) "[语音]" else "[语音：$asr]"
                }
                contentType?.startsWith("video/") == true -> "[视频]"
                contentType == "file" -> "[文件]"
                else -> "[附件]"
            }
        }

        return content
            .replace(Regex("<faceType=6,faceId=\\\"0\\\",ext=\\\"[^\\\"]+\\\">"), "[图片]")
            .replace(Regex("<faceType=[^>]+>"), "[表情]")
            .replace(Regex("<[^>]+>"), "")
            .trim()
            .ifBlank { "[空消息]" }
    }

    private fun broadcastToGame(message: String) {
        val srv = server ?: return
        srv.execute {
            try {
                srv.playerList.broadcastSystemMessage(Component.literal(message), false)
            } catch (e: Exception) {
                logger.error("广播 QQ 消息到游戏失败", e)
            }
        }
    }

    private fun sendToBridgeGroups(content: String) {
        val targets: Collection<String> = if (config.botGroups.isNotEmpty()) {
            config.botGroups
        } else {
            seenGroups
        }

        if (targets.isEmpty()) {
            if (config.debugLogEvents) {
                logger.info("尚未知道目标 group_openid；请先在目标 QQ 群发送一条消息，或在 bot.groups 中填写 group_openid")
            }
            return
        }

        for (groupId in targets) {
            qqClient.sendGroupMessage(groupId, content, lastMessageIdByGroup[groupId])
        }
    }
}
