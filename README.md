# Vault Assist v0.2.0 — Minecraft 26.2 Fabric

源自已经在用户 26.2 客户端成功加载/监控的 v0.1.4。

## 功能
- 普通宝库默认 `minecraft:emerald`，不祥宝库默认 `minecraft:heavy_core`。
- 对准宝库，目标出现在服务端同步的展示物品里时，自动发出一次原版右键交互。
- 仅在 `/vaultassist auto on` 之后自动开锁；重启游戏自动模式恢复 OFF。
- 需要对应试炼钥匙在主手或副手；不会自动切换背包物品。
- 每个维度+坐标的宝库每次游戏仅发送一次自动交互，防止连点重复消耗。
- 开锁实际掉落由服务器独立决定，展示物品 **不保证** 开出。日志记录客户端观察与发包时间。

## 命令
```
/vaultassist status
/vaultassist on
/vaultassist off
/vaultassist auto on
/vaultassist auto off
/vaultassist target normal minecraft:emerald
/vaultassist target ominous minecraft:heavy_core
/vaultassist target normal minecraft:diamond
/vaultassist resetattempts
/vaultassist stats
/vaultassist hit
/vaultassist miss
/vaultassist skip
/vaultassist note <文字>
```

配置：`.minecraft/config/vaultassist/settings.properties`。日志：`events.csv`。原有人工核对命中率机制保留，`hit`/`miss` 含义仍是人工确认沉重核心；普通宝库开锁请勿用它统计绿宝石命中率。

## 已知限制
- 不自动从背包取钥匙，必须主手/副手持钥匙。
- 自动操作请求发送后，服务器可能拒绝（例如此宝库已开过、距离或权限问题）。
- 每个坐标本次客户端会话只尝试一次；需要重新测试可用 `/vaultassist resetattempts`，可能再次发送交互。
- 本源码构建尚未在本机 Java 25 环境实际执行，先由 GitHub Actions 编译验证。

## 上传编译
GitHub 仓库解压覆盖后：`git add . && git commit -m "Vault Assist 0.2 auto open" && git push`。Actions 自动运行，成功后下载 Artifact 中的 JAR。
