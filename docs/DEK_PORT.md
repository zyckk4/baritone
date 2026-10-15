# Minecraft 1.21.11：官方基线与 Dekrom 改进移植

本 fork 以 **官方 Minecraft 1.21.11 最新分支**为基线，保留其功能，再适配 Dekrom 的修复。
核对日期：2026-10-15。这里的“最新”限定 Minecraft 1.21.11，不是 Minecraft 26.x 分支。

## 固定来源与归属

- 官方：[`cabaletta/baritone` `23723891da460ef15797b02fe5b385b0c5b163cc`](https://github.com/cabaletta/baritone/commit/23723891da460ef15797b02fe5b385b0c5b163cc)，`1.21.11` / `v1.17.0`。
- 移植来源：[`dekrom/baritone` `110d0afb016bb63f047e4e651504cb0067311964`](https://github.com/dekrom/baritone/commit/110d0afb016bb63f047e4e651504cb0067311964)，`1.21.11` / `v1.3.0-1.21.11`。
- Dek 的直接基线为 Meteor `048a403de15e127ad719cb3fb2ac8e2e317ff2c3`，落后官方许多架构更新，所以本次按功能适配，没有整文件替换。
- 保留原项目 LGPL-3.0-or-later 许可与版权头。下列原始修复由 **Dek <dekrom1@gmail.com>** 编写；本 fork 的集成适配和额外回归测试由 zyckk4 维护。

## 移植范围

以下短 SHA 均属于 [Dekrom 的提交历史](https://github.com/dekrom/baritone/commits/1.21.11/)。

| 范围 | 原始提交 | 行为 |
| --- | --- | --- |
| 地面起飞恢复 | `43ff9218`, `06095f8b` | 悬崖起飞搜索失败后有限次数原地起飞；先寻找头顶空气空间；避免地面目标已满足后永久等待 |
| 烟花时序 | `20d68fd2` | 开翼后下一 tick 才点火，使用本 tick 移动旋转，实体回包前最多预测 10 ticks 推进 |
| 熔岩逃生 | `059dc018` | 向上游、尝试开翼，开翼后以爬升为优先恢复飞行 |
| 解算恢复 | `5f1df741`, `d629c8b7`, `e94aae32` | 无可达路径俯仰角时扫描生存角度；修正重力/缓降/垂直向量；可配置前视节点数 |
| 飞行路径重算 | `04e981ff`, `652fc9c3` | 节点被地形堵塞时重算；未到达重接点的片段不拼接旧路径尾部 |
| 真实区块刷新 | `ee464f40` | 忽略 EmptyChunk；碰撞或重算失败后对附近已加载区块限频重新打包 |
| 原生上下文安全 | `a7af79f4`, `f21b36c0` | 适配官方持久上下文、双执行队列及锁；拒绝销毁后的工作与迟到回调，释放前等待，超时不释放仍可能被访问的内存 |
| 原子缓存保存 | `ce2ea38c` | 先完成临时 gzip，再原子替换；写入失败保留旧文件；不支持原子移动时回退普通替换 |
| 下落成本 | `1ac3cc52`, `7ab9473c` | 不再额外计入零初速 tick，落到方块上表面时使用实际距离 |
| 目标与世界高度 | `a68d6abd`, `28bd699b`, `96e308d9` | 修正多个危险点包围盒、负 minY 世界的上界、相邻目标终点启发值 |
| 地面碰撞与回溯成本 | `74e6f26c`, `7163c2d2`, `22b63af0` | 含水障碍仍需可通行；跳跃服从不可通行判定；回溯边权保持正数 |
| 线程与分配 | `a3ffadc0`, `712b065d`, `ee7c6d91` | 工具/水面行走状态快照、线程安全工具缓存、可见取消标志及减少每 tick 分配 |

本次还对移植接缝做了小范围修正：工具栈使用 `copy()`；缓存临时文件每次唯一；修正官方对角线第二个角的 magma 判定引用。
世界高度检查保留玩家脚部节点可站在最高方块顶面的语义。
官方高空路径还修正了垂直目标的除零问题；无法找到下降通道时返回未完成片段，避免直接连接尚不可达的低空终点。

## 保留的官方功能

- 主世界、下界和末地鞘翅寻路及其维度高度规则。
- `elytraUseCache`、新版磁盘地形缓存接口及 native pathfinder **1.6**。
- `IElytraProcess.getPath()` 公共 API。
- 官方 `expandTowards` 飞行碰撞体积修复、byte[] 预计算缓存和现有地面寻路设置。
- Fabric、Forge、NeoForge、Tweaker 构建结构；Meteor/addon 应选 Fabric **API** 构件。

没有导入 Dek 的旧 Meteor 打包体系、旧 native 依赖、Nether-only 命令限制或 Minecraft 26.x 专属代码。

## 使用

1. 使用 Minecraft **1.21.11** 和 Java **21**，为现有 Baritone 配置留好备份。
2. Meteor / addon 场景放入 `baritone-api-fabric-1.17.0-2b2t.1*.jar`，移除同一实例中其他 Baritone JAR。
3. 启动后先验证 `#help`、普通地面寻路和取消，再在可恢复的环境测试鞘翅。
4. 普通自动起飞仍需 `#set elytraAutoJump true`；默认值保留为 `false`。
5. `elytraStandingTakeoff` 默认 `true`，用于自动悬崖起飞失败后的后备。
6. `elytraPathLookahead` 默认 `20`，单位为路径节点数，不是速度或距离。

熔岩逃生属于已激活飞行任务的求生行为，可能继续消耗烟花；它不等同于普通起飞的有限重试。
预测烟花推进只用于短时间解算，并不证明服务器接受了烟花。原子文件替换也不提供跨实例缓存合并。
文件系统不支持原子移动时，回退方案仅保证先完成写入再替换，不能保证替换操作失败或系统断电后的原子性。

## 构建与验证

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat test :fabric:build -Pavailable_loaders=fabric --console=plain
```

完整构建使用 `./gradlew build`。fork 的版本由 `gradle.properties` 中 `mod_version` 决定，
可通过 `-Pmod_version=...` 覆盖，不会误用继承来的官方 Git tag。
GitHub Actions 构件版本追加来源提交短 SHA，并上传测试报告、`source-commit.txt` 和 SHA-256 清单。

验证必须区分：源码审查、单元/离线原生检查、构建产物，以及真实游戏/服务器。
**本次没有登录 2b2t；不宣称 Grim 接受率、长途稳定性、飞行速度或实际脱困成功率已经验收。**
建议实服验证覆盖：平地/坑底恢复、烟花高延迟回包、取消后立即重启、切维度/退出世界、低烟花/低耐久降落、主世界与末地的原有飞行。

## 后续同步

`1.21.11-2b2t` 为集成分支；官方 `1.21.11` 历史保留在 fork 中。
后续先合入 `upstream/1.21.11`，逐项检查本表中上游已修复的内容，再运行完整构建和回归测试。
不要用 Dek 分支替换官方目录，也不要将单次离线通过视为服务器验收。
