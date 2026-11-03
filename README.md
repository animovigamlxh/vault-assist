# Vault Assist v0.2.1 — Minecraft 26.2 Fabric

基于 v0.2.0 优化：在客户端 Tick 开始和结束都检查目标，目标匹配后先发送原版交互，再写 CSV / 发送聊天提示。保留 v0.2.0 的命令、配置、奖励目标和按坐标防重复交互逻辑。**并非底层网络包事件驱动监听**；现有 Fabric API 未提供可直接使用的宝库展示变更回调，为避免引入未经验证的 Mixin，本版采用两个 Tick 观察点。

## 默认与设置

- 普通宝库：`minecraft:emerald`，对应普通试炼钥匙。
- 不祥宝库：`minecraft:heavy_core`，对应不祥试炼钥匙。
- 使用 `/vaultassist auto on` 手动开启；重启后自动开锁仍关闭。
- 改目标：`/vaultassist target normal minecraft:diamond`、`/vaultassist target ominous minecraft:heavy_core`。
- `/vaultassist status`、`/vaultassist auto off`、`/vaultassist resetattempts`。
- 不会自动从背包拿钥匙，只检查主手/副手。不保证开出展示奖励。

## 时序日志

`.minecraft/config/vaultassist/events.csv`，新增记录：`phase=start|end`、`display_age_ns`、`call_duration_ns`。`display_age_ns` 是客户端**首次观察**物品变化到开始调用交互的耗时，绝不是服务器生成展示物品到收到开锁请求的真实耗时；`call_duration_ns` 是本地 Java 调用耗时，不代表服务器确认。日志按 CSV 追加，依然可以用原有 `/vaultassist hit`、`miss`、`skip` 人工记录中奖情况。

## 注意

- 发送了一次交互请求不等于服务器已接受；同一坐标本会话只自动尝试一次，防止重复消耗钥匙。
- 仅对准宝库才检查，不扫描视野外宝库；自动模式默认关闭。
- 源码依赖配置沿用 26.2（Java 25、Loader 0.19.3、Fabric API 0.156.0+26.2）。
- 尚未在当前环境运行 Java 25 / Gradle 完整构建，需 GitHub Actions 验证。

## GitHub 更新

解压覆盖旧工程，运行 `git add . && git commit -m "Optimize Vault Assist v0.2.1" && git push origin main`。
