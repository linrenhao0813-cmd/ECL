# 2026-10-26 问题清单整改

本次根据用户粘贴的 20 条静态审查意见逐项核对实际调用链，修复已确认的问题并补充回归。未使用 subagent；修复完成后按用户授权提交到本地 Git，未推送或发布。原清单没有运行测试，以下结论区分代码事实、自动化证据与尚未实测的性能判断。

| 编号 | 核实与处理 |
| --- | --- |
| 1 | 原描述不成立：`InstanceOperationLease` 使用 `FileChannel.tryLock`，没有端口或 PID 锁持有协议；`GameLaunchCoordinator` 已记录游戏进程标记。补真实 Java 子进程强杀测试，验证遗留锁文件可重新取得、死游戏标记不再阻止维护。Windows 进程退出后允许系统短暂释放窗口，测试有 5 秒期限。运行中的游戏应继续禁止维护。修正 `FileLockLease` 的过时注释。 |
| 2 | 未复现元数据直接任意写入：恢复先安全解压、比较 ZIP 与元数据清单，再按内容枚举移动目录；schemaVersion 已校验。补元数据 16 MiB 有界读取、文件路径/内容根/重复项/总量溢出/空条目检查；备份文件祖先和恢复工作区复用 FileUtil 安全检查。 |
| 3 | 新密钥原本已经要求安全提供器，但旧密钥迁移确实可回退到机器派生包装。删除弱包装写入分支，非 Windows 读取、创建和迁移都要求提供器；原文件在提供器缺失时保留。旧本地格式只为向安全提供器迁移而读取。 |
| 4 | 确认：`ready.join` 和 owner 的非中断锁等待会无限等。改为 `acquireInterruptibly` 与 30 秒 `get`，中断/超时同时通知 finish 并中断 owner，保留调用方中断标志。回归覆盖中断、真实超时、后续重取和原有借用生命周期。 |
| 5 | 现有 `finish` 已在队列锁内让取消获胜，`cancel` 也在同一锁内设置标志及中断 runner；无需重写。补 100 轮取消/完成竞争测试，验证终态、Future 一致及下一任务可执行。 |
| 6 | 确认完成回调使用扫描字节数。改为实际归档字节结算，中途不提前报告满额；回归在写入期间追加源文件。 |
| 7 | 原本已有每 JVM 一次的启动清理；补每次准备预览前重试。删除失败和 closeQuietly 失败记日志，准备阶段还发送本地化进度警告；保留 24 小时门槛和清理路径边界。Windows 只读文件测试覆盖失败告警、保留文件、解除只读后重试成功。 |
| 8 | 现有工作流已经有 `finishWithoutMutation` 统一终态，不存在清单所称的 `completePreparation` 接口。删除取消路径重复收尾，转用 `finishCancelled`；保留代际检查以丢弃过期回调，不额外引入状态对象。现有 headless 工作流回归负责验证取消及错误后控件恢复。 |
| 9 | 不增加读取锁：`list` 每次直接读取文件，没有账户列表缓存；写入本来就锁内重读并原子替换。加锁无法消除读取之后其他窗口继续变更产生的自然时序差。现有双账户服务测试覆盖新增、删除、默认账户在下次读取可见。 |
| 10 | 原描述不成立：实际类型是每次 resolve 新建的 `ResolutionContext`，缓存不是解析器实例字段。补同一 resolver 两次解析之间删除依赖文件的回归，证明下一次重新验证并下载依赖。 |
| 11 | 图标线程池从 8 降为 2，复用现有 HTTP 客户端、连接池及缓存。降低资源竞争，未声称实测下载提速。 |
| 12 | 保留 core 的 60 秒安全停止 API；GUI 按服务端 ID 跟踪停止等待，显示本地化等待状态、禁止重复 stop，同时保留列表浏览和日志轮询。headless 测试在 stop 仍等待时核对列表与日志刷新，并覆盖停止成功及超时不强杀。 |
| 13 | 保留内容哈希：mtime/size 无法发现同大小、同时间戳的篡改，原生库直接加载代码，不能为未测量的性能收益削弱完整性校验。未进行启动性能基准。 |
| 14 | 单线程 executor 的工作线程复用本来会处理残留中断，不盲目清除新任务自己的取消信号。扩展真实本地 HTTP 下载测试：先取消已进入监听器的任务，紧接着用同一 downloader 成功下载并校验客户端。 |
| 15 | 确认：畸形请求原本返回 200 空数组。改为 400，并要求数组每项为字符串，debug 日志不包含请求体；测试覆盖畸形 JSON、错误根节点、null、数字及混合数组。 |
| 16 | 类型化 set 原本将值转为字符串；改为复用 `setAll`，保存原生数字/布尔值，读取继续兼容历史字符串。 |
| 17 | MRPACK 祖先路径检查委托 `FileUtil.validateExistingAncestors`，移除重复 Windows 属性检查，保留路径解析与边界校验。 |
| 18 | MRPACK 和原生库复用 `ZipUtil.ExtractionLimits`，删除原生库重复 record；保留备份、整合包、原生库各自原有预算及提取流程，不把不同安全阈值强行合并成同一数值。 |
| 19 | 原描述不成立：`JvmArgumentPolicy` 已拦 `-javaagent`、`-agentpath`、`-agentlib`、`-Xbootclasspath`、参数文件及错误处理命令参数。内部皮肤注入不经过用户参数入口。 |
| 20 | 仓库只有 `LauncherTaskLifecycleTest`，没有可复用的同名生产类。没有为尚未复现的问题批量迁移十余个 GUI 工作流；本轮只统一已涉及的升级取消收尾和服务端停止状态。跨工作流的大范围架构重构留作独立工作。 |

原清单末尾额外点名的事项：生产代码搜索未发现无参 `getBytes()`；`LocalServerProfile` / `RunningProcess` 格式本来没有 schemaVersion，不凭空增加字段；任务裁剪之后已有 `fireChanged(true)`。实例显示设置的跨窗口覆盖问题另做锁内单实例更新及回归。未列出的其他“小问题”没有具体路径或触发条件，不推测修复。

## 验收记录

- `.\gradlew.bat check --rerun-tasks --max-workers=2 --console=plain`：成功，25 个任务全部实际执行；JUnit 共 817 项，816 通过、1 跳过、0 失败。core 679 项（1 跳过），GUI 138 项。
- 唯一跳过：`LocalServerManagerTest.rejectsSymlinkSourcesAndManagedPathRedirects`，Windows 不允许该测试创建符号链接。另有 Windows junction 回归和只读文件清理回归实际执行通过，不能把它们说成已覆盖跳过的场景。
- Checkstyle、SpotBugs 主代码/测试代码及 JaCoCo 门槛均通过；core 行/分支覆盖率为 77.76% / 60.24%，GUI 为 53.13% / 40.39%。
- `captureLauncherUi --max-workers=2` 分别使用 `forest`、`local-servers`、`local-servers-en`、`local-servers-zh-tw` 模式成功，四张图片已逐张查看；本地服务端截图覆盖表单首屏，停止期间状态与日志持续刷新由 headless 回归验证。快照有既有的 JavaFX unnamed module 配置警告，命令成功。
- `.\gradlew.bat packageWindowsApp --max-workers=2 --console=plain`：成功。完整镜像位于 `dist/windows/ECL/`，共 374 个文件；已核对 `ECL.exe`、`app/ECL.cfg`、`runtime/bin/server/jvm.dll`、`runtime/lib/modules`。boot/core/gui 三个项目 JAR 的 SHA-256 均与各模块本轮 `build/libs` 一致。
- 已审阅 `git status`、`git diff`，`git diff --check` 通过；变更只包含本轮源码、回归测试、文案和文档。构建日志保存在忽略的 `build/repair-*.log`，快照与完整应用镜像均在生成目录，不加入源码提交。

较早的红灯记录用于证明回归可复现，包括中断、迁移弱保护、元数据读取、进度、HTTP 400、设置 JSON 类型和服务端停止交互。中间发现并修正了测试接口实现、未用导入及测试 SecureRandom 的 SpotBugs 问题，最终以上述完整重跑为准。

未覆盖真实 Microsoft 登录、Minecraft 客户端崩溃/联机或真实 Minecraft 服务端保存；子进程测试和 headless 服务端进程模拟不能替代这些场景。本次仅按用户授权提交到本地 Git，未推送或发布。
