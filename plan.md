# ECL 全项目代码审查方案（plan.md）

> 审查日期：2026-08-23 ｜ 范围：ecl-core / ecl-gui / ecl-cli 全部源码（402 个 Java 文件）
> 方式：按模块分 7 个分区（auth+config / modrinth / download+curseforge+task / game+launch / util+launcher+event / GUI / CLI）逐文件扫描
> 维度：① 逻辑错误与潜在 bug ② 边界条件与异常处理 ③ 安全隐患 ④ 性能瓶颈 ⑤ 可维护性与规范
> 总体评价：工程质量高于同类启动器平均水平（zip-slip 防护、原子写、AES-GCM+DPAPI、XXE 防护、路径穿越防御均有正面实现）。主要风险集中在：**下载链路安全校验缺失、并发/竞态、双份实现分叉、状态一致性**。

## 问题统计

| 严重度 | 数量 | 说明 |
|---|---|---|
| 高 | 18 | 含 2 个安全类（路径穿越、URL scheme 未校验） |
| 中 | 26 | 并发、异常处理、行为不一致为主 |
| 低 | 15（精选） | 死代码、命名、退出码语义等 |

---

## 一、高严重度（18 项，按风险排序）

### 安全类

| # | 位置 | 问题 | 影响 | 修复建议 |
|---|---|---|---|---|
| H1 | ecl-core `download/install/DownloadAssetsTask.java:44,57-58` | `assetId`/`hash` 未校验格式直接拼接路径，`new File(assetDir, subPath)` 未走 `safeResolveUnder`；旧实现 GameDownloader 的 `[0-9a-fA-F]{40}` 校验在 install 新流程中丢失 | 恶意/损坏的资源索引可写文件到 assets 目录外任意位置（路径穿越） | 补 hash 格式校验 + `FileUtil.safeResolveUnder`，或复用 GameDownloader 原逻辑 |
| H2 | ecl-core `modrinth/pack/MrpackFileInstaller.java:58-65`、`modrinth/download/ModFileDownloadService.java:95-104` | mrpack 索引 `downloads` URL 与 API 返回 `file.url` 均未校验 scheme/来源，直接交给 HttpUtil | 恶意 .mrpack（hash 也由攻击者控制）或被劫持的 API 可诱导访问 `file://`、内网地址，写入任意内容 | 下载前强制 http/https scheme 校验；mrpack 文件限制主机白名单（cdn.modrinth.com） |
| H3 | ecl-core `launcher/ModLoaderInstaller.java:224-235` | `mergeDirectory` 路径穿越校验条件 `!destination.startsWith(...) && target.isAbsolute()` —— target 为相对路径时整个校验被短路跳过 | zip-slip 防线依赖调用方巧合传绝对路径，重构后可能静默失效 | 去掉 `&& target.isAbsolute()`，入口统一 `toAbsolutePath().normalize()` 后比较 |
| H4 | ecl-core `util/HttpRequestExecutor.java:34-46,167-174` | `request()` 响应体整读入内存且无大小上限（`getBytes()` 有 maxBytes，此处没有）；body 读取阶段无超时 | 恶意/被劫持镜像源可致 OOM 或线程永久挂起 | request() 增加 maxBytes 截断；body 读取循环结合限速器并校验累计字节 |

### 并发 / 数据完整性类

| # | 位置 | 问题 | 影响 | 修复建议 |
|---|---|---|---|---|
| H5 | ecl-core `download/DownloadTaskCenter.java:86-91,394-403` | 构造器设置全局 `HttpUtil.setDownloadRateLimit...`，close 时恢复"创建前快照"；A 创建→B 创建→A close 会覆盖 B 的限速 | 多 TaskCenter 并存时限速配置错乱 | 限速移出 TaskCenter，由配置层统一管理 |
| H6 | ecl-core `download/DownloadTaskCenter.java:215` + `DownloadTaskExecutor.java:29,48` | `entry.runner` 赋值/清空在锁外执行，cancel() 持锁读到 runner 后 interrupt；线程已归还线程池执行其它任务时会误中断 | 偶发无关下载被取消，难复现 | 锁内确认 status==RUNNING/CANCELLING 后再 interrupt |
| H7 | ecl-core `download/GameDownloader.java:74-84` | `downloadVersionAsync` 先 `cancelDownload()`（仅中断请求）立即 submit 新任务，新旧任务并发写同一 versions 目录与 `.part` 文件 | 断点文件互相破坏，产物损坏 | 等待旧 Future 终止后再提交新任务 |
| H8 | ecl-core `modrinth/transaction/FileModInstallationTransaction.java:217-246`、`pack/PackUpdateTransaction.java:218-262` | `recoverIncompleteTransactions` 无法区分"崩溃遗留"与"另一进程进行中"，事务目录无锁/活性检测 | 双开启动器指向同一 game dir 时回滚另一进程正在提交的事务，mod 文件丢失 | 事务目录加 FileLock 或 PID+心跳，恢复前检测持有者存活 |
| H9 | ecl-core `config/SettingsAutoSaveScheduler.java:30-48` | 竞态：T1 save 中 → B setXxx+markDirty → T1 markClean 将 dirty 置回 false；B 的修改无任务调度、`close()` 兜底也失效 | GUI 开 autoSave 后设置偶发静默丢失 | markClean 用版本号比对"save 开始后无新 markDirty 才清位" |
| H10 | ecl-core `game/InstanceManager.java:23-39` | renameInstance 多步 move 无回滚；copyInstance 中途失败不清理已拷贝的 target（finally 只删 staging） | 磁盘满/文件占用时产生"半个实例"，启动行为不可预测 | 失败时逆向回滚；copy 失败删除已创建 target |
| H11 | ecl-core `util/JsonFileStore.java:17-25` | `write()` 直接覆盖目标文件，无 temp+atomic move（同工程其它写路径都做了） | version_manifest.json / profile JSON 写入途中崩溃留下截断文件 | 写同目录临时文件后 `Files.move(ATOMIC_MOVE)`，检查 mkdirs() 返回值 |

### 功能性 bug 类

| # | 位置 | 问题 | 影响 | 修复建议 |
|---|---|---|---|---|
| H12 | ecl-core `launch/LaunchVariables.java:37` + `NativeLibraryExtractor.java:63-65` | natives 目录两套来源：extractor 解压到 `<instance>/natives-windows`，`${natives_directory}` 永远指向 `versions/<id>/natives` | LWJGL2 版本（≤1.12）隔离实例启动必 `UnsatisfiedLinkError`；两处重复解压 | natives 目录单一来源（放入 LaunchOptions/environment），双方读同一值 |
| H13 | ecl-core `launch/JavaVersionRequirement.java:38-41` + `launcher/ModLoaderInstaller.java:271-281` | 1.17 系列推断为 Java 8（实际要求 16）；pre-release/快照版本 parseInt 失败也落到 Java 8 | 1.17 魔改版本（缺 javaVersion 字段）启动必挂 `UnsupportedClassVersionError`；1.20.5+ 快照装 Forge 触发错误运行时 | `minor==17` 返回 16；快照解析失败按版本段首数字兜底走高版本分支 |
| H14 | ecl-core `auth/DefaultAccountService.java:48-50` | `list()` 中任一 entry 解码失败即抛异常中止；而 save/remove/setDefault 全依赖 list() | 一条损坏账户 → 账户管理永久不可用，只能手工编辑 accounts.json | list() 对损坏条目降级"跳过+warn"，让 remove 能删坏条目 |
| H15 | ecl-gui `com/ecl/ui/LauncherUI.java:2466` | `downloadSelectedContent` 的后台 lambda 中 `dialogStatus.setText(...)` 未包 `Platform.runLater`（同 lambda 其它 setText 均有包裹） | 非 FX 线程更新 UI，IllegalStateException 或未定义行为 | `Platform.runLater` + generation 守卫 |
| H16 | ecl-cli `LaunchCommand.java:101-107` | 非 `--wait`/`--json` 组合下游戏进程 stdout 管道无人读取（PIPE + redirectErrorStream） | 管道缓冲区（Windows ~64KB）写满后游戏进程永久阻塞 | 启动子进程统一 `Redirect.INHERIT`（后台模式重定向到日志文件） |
| H17 | ecl-cli `LaunchCommand.java:104-107` | `launch()` 进程创建成功即输出 `"started": true` 退出 0 | JVM 参数/Java 路径错误数百毫秒内崩溃，脚本误判成功 | 非 --wait 时等 1-2 秒探测 `isAlive()`，崩溃返回非零 |
| H18 | ecl-core `download/GameDownloader.java:215-313` vs `download/install/*` | downloadLibraries/downloadAssets 等整段逻辑双份维护且已分叉（hash 校验、verified-marker 只在旧侧，见 H1） | 修 bug 只修一半，安全防线随分叉丢失 | GameDownloader 委托 install 包实现，删除副本（建议随本次下载模块改造一并收敛） |

---

## 二、中严重度（26 项）

### 逻辑 / 行为不一致

| # | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| M1 | core `util/JavaRuntimeUtil.java:104-149,269-309` + `runtime/DefaultJavaManager.java:39-50` | Java 选择策略三处不一致：resolveExact（精确否则下载）/ path 版允许 `>=` / select 取"最小高于者"；Java 探测每次为每个候选 spawn `java -version` 子进程且无缓存，CLI/GUI/启动路径均受影响 | 统一为"精确 > 最低满足的更高版本 > 下载"一套策略；按路径缓存 featureVersion（用现成 BoundedCache） |
| M2 | core `launch/DefaultLauncher.java:142` + `GameProcess.java:72-88` | `Phase.EXITED` 事件 core 层无发布点、`whenExited()` 无人调用，exitFuture 永不完成；PlaytimeTracker 同样无调用方 | DefaultLauncher.launch 内订阅 whenExited() 并发布 EXITED 事件 |
| M3 | core `game/InstanceManager.java:60-64` | copyTree 默认 FOLLOW_LINKS 跟随符号链接复制目标内容；visitFileFailed 未处理直接中断 | 过滤 isSymbolicLink；覆写 visitFileFailed 记录并跳过 |
| M4 | core `pack/DefaultPackService.java:278-284` | safeName 未过滤 Windows 非法字符 `<>:"|?*`、保留名 CON/NUL、尾部点/空格；整合包 name 直接成为实例目录名 | 复用 FileUtil 级白名单校验 |
| M5 | core `game/DefaultGameRepository.java:140-150` | 与 `FileUtil.requireSafeVersionId` 两套版本 ID 校验规则并存，细节不一致 | 收敛为 FileUtil 一处 |
| M6 | gui `GameLaunchCoordinator.java:126` | 全局单例 downloader 的 listener 被并发任务覆盖，A 的进度/完成回调丢失（"下载完成但游戏没启动"） | 每次下载独立 downloader 实例或 listener 随任务传递 |
| M7 | gui `server/ServerDirectoryService.java:139-140` | servers 用 toLowerCase 去重，statuses 用原始大小写作键且 put 非 putIfAbsent → 状态错配 | statuses 统一 toLowerCase(Locale.ROOT) + putIfAbsent |
| M8 | cli `ModDisableCommand.java:22-24` | 对已禁用 mod 再 disable 生成 `.disabled.disabled`；enable 已启用文件抛 NoSuchFileException | 操作前检查源/目标状态，幂等返回 |
| M9 | cli `PackImportCommand.java:35-46` | MRPACK 分支忽略 `--instances` 选项，与其它格式语义不一致 | MRPACK + --instances 显式报错或透传 MrpackInstaller |
| M10 | cli `CliAccountAuthenticator.java:22-30` | 无默认账户时静默回退离线 Player 启动 | 账户库非空但无默认时打明确警告 |
| M11 | core `modrinth/provider/CurseForgeMetadataProvider.java:157-160` | downloadUrl 为空时合成 `curseforge://` 占位 URI 进入安装计划，到下载阶段才晦涩失败 | selectInstallFile 阶段过滤非 http(s) scheme |
| M12 | core `modrinth/service/ModInstallationService.java:90-121` | `downloadAll(...).join()` 阻塞 orchestration 线程；取消不传播；CancellationException 被包装成安装失败 | 用 thenCompose 替代 join；CancellationException 原样抛出 |

### 异常处理 / 边界条件

| # | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| M13 | core `download/GameDownloadBatchExecutor.java:47-78` | 注释称"首个失败中止"，实际等全部任务结束才抛错且不取消未开始任务 → fail-slow | 首个失败即 cancel 其余 Future |
| M14 | core `download/GameDownloader.java:158-167` | 线程被中断时 catch 块 return，Future 正常完成，取消被吞为"成功" | 中断时抛 CancellationException |
| M15 | core `download/CurseForgeDownloader.java:109-123` | 依赖链任一缺失即抛异常（主文件已落盘但结果丢失）；递归串行且无深度上限 | 依赖失败聚合为报告；限制递归深度 |
| M16 | core `curseforge/CurseForgeApiClient.java:156-169` | 指纹匹配按"下标一致"配对 exactFingerprints/exactMatches，依赖 API 返回顺序 | 以 `match.file.fileFingerprint` 为准配对 |
| M17 | core `download/install/DownloadLibrariesTask.java:89` + GameDownloader:264 | 不可信 JSON `artifact.get("url").getAsString()` 缺字段时裸 NPE | 判空抛带上下文的 IOException |
| M18 | core `config/SettingsManager.java:60-66` | 损坏 settings.json 静默置空，下次保存覆盖原文件且无备份 | 解析失败先改名 `settings.json.corrupt` 再重置 |
| M19 | core `auth/YggdrasilAuth.java:170-196` | 响应缺 accessToken/clientToken 字段时裸 NPE 且逃逸 catch(IOException) 契约；validate() 把网络故障等同 token 失效 | 仿 requireString 判空转 IOException；仅 401/403 返回 false |
| M20 | core `modrinth/repository/FileInstalledModRepository.java:40-47` | findAll 只 catch RuntimeException，Jackson 的 IOException 子类裸逃逸；索引损坏无恢复路径 | catch IOException 统一包装；损坏时备份并降级 rescan |
| M21 | core `event/EventBus.java:89-97` | errorSink 自身抛异常会中断整个分发，违背"单 handler 失败不影响其余"承诺 | errorSink.accept 包 try/catch 回退默认日志 |
| M22 | core `util/HttpJsonClient.java:66-73` + `JsonUtil.java:13-29` | getJson 解析失败抛未受检异常（签名只声明 IOException）；getString 无类型容错（getInt 有） | 包装为 IOException；getString catch RuntimeException 返回默认值 |
| M23 | cli `EclCli.java:44-57,62-64` | 全局异常处理器丢弃堆栈；缺子命令时退出码 0 | 非预期异常 printStackTrace；usage 后返回 2 |
| M24 | core `desktop/DesktopShortcutService.java:39-92` | PowerShell waitFor 无超时；bat 回退未转义 `%`；quoteWindowsArgument 用 `\"` 不符合 MSVCRT 规则 | waitFor 加超时；`%`→`%%`；标准 MSVCRT 转义 |

### 安全 / 隐私

| # | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| M25 | core `util/CryptoUtil.java:136-165` | 系统属性 `-Decl.crypto.keyFile` 即关闭密钥加密（明文落盘），无环境区分 | 限定测试 classpath 生效或明文路径每次打 WARN |
| M26 | gui `modrinth/ui/ChineseDescriptionService.java:42-52` | 模组描述静默外发 Google 非官方翻译端点与 MyMemory API，无开关无告知 | 设置页加可见开关，隐私声明注明 |

---

## 三、低严重度（精选 15 项）

| # | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| L1 | core `auth/YggdrasilAuth.java:149-194` | 密码 char[] 转 String 后 finally 置 null 是 no-op 死代码，注释误导 | 删 finally 块；接受 HTTP 层 String 化现实并简化签名 |
| L2 | core `auth/DefaultAccountService.java:71-76` | addOffline check-then-act 非原子，并发双默认账户 | 整体加 synchronized |
| L3 | core `auth/offline/OfflineSkinServer.java:104-113,224` | close() 无视引用计数强制停服；请求体 readAllBytes 无上限 | 收敛 Lease 生命周期；限 64KB |
| L4 | core `download/DownloadTaskNotifier.java:31-47` | listener 在下载线程同步执行 + throttle check-then-act 竞态 | 独立分发线程；throttle 加同步块 |
| L5 | core `download/DownloadTaskCenter.java:458-464` | 重试字芔回退后速度长期显示失真 | 回退时同步重置基准 |
| L6 | core `curseforge/CurseForgeFingerprint.java:16-66` | 文件读两遍（count + hash 各扫一次） | 单遍同时完成 |
| L7 | core `modrinth/pack/MrpackFileInstaller.java:36-105` | 索引文件逐个串行同步下载，未复用并行 ModFileDownloadService；进度分母含服务端文件失真 | 批量提交线程池；分母过滤 isClientFile |
| L8 | core `modrinth/service/DefaultModUpdateService.java:104-111` | allOf+join，单个项目失败屏蔽全部更新提示 | 逐项 exceptionally 降级 warning |
| L9 | core `modrinth/pack/MrpackInstaller.java:246-297` | prepareLoader 在事务外写 versions 目录，回滚不清理孤儿 loader profile | loader 安装纳入事务 |
| L10 | core `pack/`+`api/`+`transaction/` | Gson 与 Jackson 两套 JSON 栈混用 | 统一到 Jackson |
| L11 | core `launch/ProcessOutputPump.java:122-129` | 持锁同步回调，慢监听器反压游戏进程；重放逻辑重复回放 | 有界队列异步消费；修重放 |
| L12 | core `launch/NativeLibraryExtractor.java:82-199` | 每次启动全量 SHA-1 指纹（几十 MB 哈希 IO） | 默认 (size, mtime) 指纹，疑似不一致才降级 SHA-1 |
| L13 | gui `LauncherUI.java`（2810 行） | God class：7+ 职责、约 40 个字段被友元类直改、createHeader/createTrafficDot/getOrCreateHomePage 等死代码 | 删死代码；下载对话框（约 650 行）抽独立类 |
| L14 | gui `modrinth/ui/ModBrowserView.java:277-285`、`server/ServerStatusProbeController.java:48` | 更新计数触发 3 列表全量刷新；每次探测完成一次全量 refreshView（最多 32 次） | 防抖合并刷新 |
| L15 | cli 多处 | 退出码语义混乱（业务"未找到"与用法错误同码 2）；非 JSON 模式打印 Map.toString()；`--memory 0/-1` 静默忽略 | 定义语义化退出码；人类可读格式化；非正数报参数错误 |

---

## 四、五维度检查结论

| 维度 | 结论 |
|---|---|
| 1) 逻辑错误/潜在 bug | **有发现**（高 12 项）：natives 目录错位、1.17→Java 8、限速覆盖、误中断线程、并发写、autoSave 竞态、Java 选择策略三处不一致等 |
| 2) 边界条件/异常处理 | **有发现**（高 4 项、中 12 项）：损坏 JSON 覆盖、账户库锁死、取消被吞、fail-slow、不可信 JSON 裸 NPE 等；整体 null/空列表防护尚可 |
| 3) 安全隐患 | **有发现**（高 4 项、中 2 项）：DownloadAssetsTask 路径穿越、mrpack URL scheme 未校验、mergeDirectory 校验短路、HTTP 响应无上限、明文密钥后门、翻译静默外发；zip-slip 主防线/XXE/AES-GCM+DPAPI/命令注入防护（ProcessBuilder 列表参数）实现良好 |
| 4) 性能瓶颈 | **有发现**（中 1 项、低 6 项）：Java 探测无缓存串行 spawn（全局影响最大）、mrpack 串行下载、依赖解析串行 N+1、每次启动全量 SHA-1、GUI 全量刷新；网络/文件 IO 基本已异步化，连接复用无问题 |
| 5) 可维护性/规范 | **有发现**（高 1 项、低多项）：GameDownloader 与 install 包大段重复且已分叉（最突出）、LauncherUI God class、双 JSON 栈、两套 versionId 校验并存、死代码若干；整体分层清晰、命名规范 |

---

## 五、修复优先级路线（建议）

| 阶段 | 内容 | 条目 |
|---|---|---|
| P0（本周，安全+硬故障） | 下载链路安全校验 + 启动硬故障 | H1、H2、H3、H4、H12、H13、H16 |
| P1（近期，数据完整性） | 并发/竞态/原子性 | H5-H11、H14、H17、M18 |
| P2（随下载模块改造一并收敛） | 双份实现合并 | H18、M1（Java 策略统一）、M5 |
| P3（迭代内） | 中危异常处理与行为一致性 | M2-M4、M6-M24 中按模块批量修 |
| P4（择机重构） | 可维护性 | L10、L13、M25/M26（安全项优先提至 P1） |

### 验收口径
- P0 修复后补单元测试：恶意 asset index（hash 含 `../`）、恶意 mrpack（file:// URL）、1.17 版本 JSON（无 javaVersion 字段）、CLI `--json` 启动脚本化回归。
- H18 合并后跑全量下载回归（版本安装 + mod 安装 + 整合包导入），确认 hash 校验、verified-marker 行为不丢失。
