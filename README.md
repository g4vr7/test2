# PenguinServer Forge MVP

这是基于 HuHoBot `PenguinClient-Fabric` 的 Forge 1.20.1 最小移植实验版。

目标环境：

- Minecraft 1.20.1
- Forge 47.4.12
- Java 17+（用户当前服务器为 Java 21，兼容）
- Kotlin for Forge 4.11.0

本 MVP 只保留：

- QQ 群消息 -> Minecraft
- Minecraft 玩家聊天 -> QQ 群
- 玩家进服 / 退服通知
- QQ WebSocket 连接、心跳、断线重连、token 刷新

刻意不包含：

- 远程执行服务器命令
- 白名单管理
- QQ 群管理
- AI 审核
- Addon
- WebUI
- 扫码绑定

这样第一轮测试的权限面和故障面都尽可能小。

## 为什么它适合愚者 0.3

愚者 1.20.1 Forge 整合包本身包含 Kotlin for Forge 4.11.0，所以正常情况下只需要额外把本模组 jar 放进服务器 `mods/`。

## 首次启动

首次启动会生成：

`config/penguin-server-forge.json`

把 QQ 开放平台机器人凭据填入：

```json
{
  "bot": {
    "app-id": "你的 AppID",
    "secret": "你的 AppSecret",
    "name": "克劳狄乌斯",
    "groups": []
  }
}
```

不要把 AppSecret 提交到 GitHub。

### bot.groups 为空时

MVP 会自动记住“本次启动后实际收到过消息的 QQ 群”。因此机器人启动后，先在目标群里发一条普通消息；之后游戏消息就会转发回该群。

这么做还有一个好处：程序会缓存那条 QQ 群消息的 `msg_id`，MC -> QQ 时优先作为回复发送，以适应 QQ 官方机器人对主动群消息的限制。

若以后拿到了固定 `group_openid`，也可以直接写入 `bot.groups`。

## 构建

GitHub Actions 会自动执行：

```bash
./gradlew build
```

构建产物位于：

`build/libs/penguin-server-forge-0.1.0-mvp.jar`

## License

本项目基于原项目代码修改，继续使用 GNU AGPL-3.0。
