# Vault Assist v0.1.1 — Minecraft 26.3 / Fabric

**模式：沉重核心提示 + 玩家手动开锁。** 支持普通宝库和不祥宝库。客户端不自动使用钥匙、不发送额外交互包。

## 用法

1. 安装 Minecraft **26.3**、Fabric Loader 0.19.5 或更新兼容版、相应版本 Fabric API。
2. 将编译好的 `vault-assist-26.3-0.1.0.jar` 放到 `.minecraft/mods/`。
3. 对准宝库，看屏幕下方的展示物品提示。展示沉重核心时会变绿，**自己决定是否右键**。
4. 开锁后仍然对准该宝库，直到模组观察到 `UNLOCKING` 状态变化；到奖励弹出、拾取完成后判断结果。
5. 若 **该次开锁最终奖励包含沉重核心**，输入 `/vaultassist hit`；否则输入 `/vaultassist miss`；无法确定就用 `/vaultassist skip`。
6. `/vaultassist stats` 查看仅包含人工确认的命中率；`/vaultassist status` 状态；`/vaultassist on` 和 `/vaultassist off` 控制提示；`/vaultassist note 文本` 写 CSV 备注。

**特别重要：** `/vaultassist hit` 代表“实际奖励出现沉重核心”，不代表“展示物品与奖励必然相同”。若统计的是展示命中率，请仅在开锁前展示沉重核心的情况下做实验，其他试验单独用备注区分。本版没有可靠的自动归属识别：旁人开锁可能触发观察事件。不要确认非自己的开锁，直接 skip。

## 日志

- `.minecraft/config/vaultassist/events.csv`：展示变化、目标出现、开锁状态、人工确认、备注，包含 UTC、Unix 毫秒、客户端单调时钟纳秒、维度、坐标、类型、物品等。
- `.minecraft/config/vaultassist/stats.properties`：确认总次数/命中/未命中，重启保留。
- 本版的 `UNLOCK_OBSERVED` 是**客户端看到开锁状态**的时刻，不是右键发包时刻；`DISPLAY` 是客户端获知展示内容时刻，不是服务器抽取时刻。两者不能直接用于测量服务端网络延迟。
- 记录实际奖励必须人工确认；不读取服务端随机种子、隐藏战利品表或 `items_to_eject`。

## 编译

直接把整个源码 ZIP 上传到 GitHub 仓库解压后的根目录，推送 `main`，打开 **Actions → Build Fabric 26.3 → Run workflow**，构建成功后下载 Artifact。

v0.1.1 修复 Gradle 配置：Minecraft 26.3 非混淆 Loom 插件使用 `implementation` 而不是 `modImplementation`，解决 `Could not find method modImplementation()`。

本工程使用 Gradle **9.7.1**、Java **25**、Fabric Loom **1.18.2**，预设 Fabric API **0.162.0+26.3**。本地有 Gradle 的话执行 `gradle build`，结果位于 `build/libs/`。源码包不包含 Gradle Wrapper；GitHub Actions 会自动安装正确版本 Gradle。

**开发验证情况：** 源码已打包并做文件/结构检查，但当前打包环境只有 JDK 21 且没有可用的外部 Maven 网络，**尚未完成 Java 25 + Minecraft 26.3 依赖的真实编译/进服测试**。如果 Actions 报错，提供日志可据此修复。本版是测试版，不保证在 EarthMC 特定插件环境下能监听到全部状态切换。

## 机制说明

展示物品从客户端同步的 `VaultBlockEntity.getSharedData().getDisplayItem()` 读取。根据 Minecraft 26.3 原版源码，真正开锁时由服务端执行独立奖励表生成，不能保证当前展示物品就是最终掉落。日志用于实测，而非宣称可预测结果。



## v0.1.2 编译兼容修复
- Fabric 客户端命令改用 `ClientCommands`。
- Minecraft 26.3 的界面检测改用 `mc.gui.screen()`。
- 动作栏使用 `mc.gui.setOverlayMessage()`，聊天输出使用 `mc.gui.getChat().addMessage()`。
- 维度记录使用 `ResourceKey.toString()`，不再调用已不存在的 `location()`。
- 此版本未在 GitHub Actions 中实际编译验证。


## v0.1.3 兼容修复
参考 EarthMC Market v0.5.50 中已使用的 `mc.player.sendSystemMessage(Component.literal(...))`，移除在 26.3 无法编译的 `mc.gui.setOverlayMessage` 和 `mc.gui.getChat()` 调用。提示改为仅在展示物品变化时向客户端聊天栏显示（不刷屏）；仍是纯提示，不会自动开锁。此次修复尚未通过完整 Gradle 编译与进服测试。
