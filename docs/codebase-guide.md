# 源码阅读指南

> 最后同步：2026-10-25 · 对应版本 `1.0.2beta`（包含同职责专用实现的文件合并）。
> 新增或调整功能时，请同步更新本文件、“数据格式说明”（`docs/data-formats.md`）与 README 对应章节。

## 从入口开始

启动链路是 `ECL.main` → `ECLauncher.main` → `LauncherUI`。`LauncherUI` 继承
`LauncherUIView`，后者按服务初始化、启动配置加载、窗口展示和初始状态刷新组织启动过程。
`LauncherWindowLayout` 装配窗口框架、顶栏与页脚；应用服务与后台执行器由 `MainController` 管理。

| 模块 | 职责 | 建议阅读入口 |
| --- | --- | --- |
| `ecl-boot` | 检查 JavaFX 并启动应用 | `com.ecl.ECL` |
| `ecl-gui` | 页面、交互、后台任务与界面的衔接 | `com.ecl.ui.LauncherUIView`、`MainController` |
| `ecl-core` | 认证、下载、启动、实例与内容管理 | 按下表选择对应业务包 |
| `ecl-dist` | Windows 应用打包 | `build.gradle.kts` |

Gradle 依赖方向为 `ecl-boot` → `ecl-gui` → `ecl-core`（`ecl-boot` 同时直接依赖 `ecl-core`；
`ecl-dist` 只定义 `packageWindowsApp` 任务）。定位业务规则时先看核心模块，
定位按钮事件与界面状态时再看 GUI 模块中对应的控制器或工作流。
更完整的模块依赖、包职责与流程时序见[架构说明](architecture.md)。

## 按业务查找

以下路径均相对于 `ecl-core/src/main/java/com/ecl/`。

| 场景 | 入口 | 职责划分 |
| --- | --- | --- |
| 账户与登录 | `auth/DefaultAccountService.java` | 账户服务协调具体认证提供者 |
| Microsoft 登录 | `auth/MicrosoftAuth.java`、`MicrosoftOAuthClient.java`、`XboxLiveAuthClient.java`、`MinecraftServicesClient.java` | 设备码授权 → Xbox → Minecraft 令牌 → 档案 |
| 离线皮肤 | `auth/offline/AuthlibInjectorManager.java`、`OfflineSkinServer.java`、`OfflineSkinInjector.java` | 下载 authlib-injector，启动本地 Yggdrasil 兼容服务并注入 |
| 游戏启动 | `launch/DefaultLauncher.java` | 启动流程调用命令构建、原生库解压与进程管理 |
| 进程标记 | `launch/GameProcessMarker.java` | 写 `.ecl/game-process.json`，启动器重启后仍能判定游戏在运行 |
| 游戏版本 | `launcher/VersionManager.java` | 配合版本清单、目录扫描与 profile 解析类；清单缓存见 `VersionManifestStore` |
| 加载器安装与查询 | `launcher/ModLoaderInstaller.java`、`LoaderMetadataClient.java`、`LoaderProfileWriter.java` | Fabric/Quilt 用 profile JSON，Forge/NeoForge 运行官方安装器 |
| 加载器升级 | `launcher/LoaderUpdateService.java` | “一键升级”的加载器部分 |
| 服务端安装 | `launcher/ServerInstaller.java` | 在独立目录安装原版/模组服务端，并生成启动脚本 |
| 下载调度 | `download/DownloadTaskCenter.java` | 排队、并发名额、取消、重试与历史保留 |
| 下载执行 | `download/DownloadTaskExecutor.java` | 执行具体下载操作，把结果交回调度器 |
| 游戏版本下载 | `download/GameDownloader.java` | 编排元数据、客户端、依赖库和资源下载，保留取消检查与版本锁 |
| 安装校验 | `download/GameInstallationVerifier.java` | 逐项检查元数据/客户端/依赖库/原生库/资源索引/资源对象/加载器依赖，输出 `VERIFIED`/`MISSING`/`CORRUPT`/`UNVERIFIABLE` |
| 安装修复 | `download/GameInstallationRepairService.java` | 只修可认证的来源；校验指纹、实例身份与实例锁后再下载 |
| 下载源选择 | `download/provider/`、`util/DownloadSourceUtil.java` | 官方源优先，失败后尝试 BMCLAPI / FastMinecraftMirror |
| 世界备份 | `backup/WorldBackupService.java`、`WorldBackupRestorer.java` | 创建/列出/恢复/裁剪备份，压缩包 + 同名 JSON 元数据 |
| 实例配置 | `game/InstanceLaunchProfileStore.java`、`InstanceGameSettingsStore.java` | 实例级启动参数与运行目录策略，均为原子 JSON 写入 |
| 实例显示 | `game/InstanceDisplayMetadataStore.java` | `<数据目录>/instances/display.json` |
| 实例路径 | `game/DefaultGameRepository.java` | 实例根目录、运行目录与隔离策略 |
| 实例互斥 | `operation/InstanceOperationCoordinator.java` | 进程内串行；跨进程锁见 `util/InstanceOperationLease`、`ManagedLockPaths` |
| 异步升级所有权 | `operation/InstanceOperationSession.java` | owner、引用借用与互斥写借用；最后一个借用归还后释放生命周期锁 |
| 托管路径范围 | `util/ManagedPackPathPolicy.java` | 纯词法规范化，拒绝路径别名与用户数据目录，不以 CWD 做根目录 |
| 模组依赖解析 | `modrinth/service/DefaultModDependencyResolver.java` | 区分必需、可选、冲突与内嵌依赖，按依赖先于引用者的顺序生成安装计划 |
| 本地 Mod 扫描 | `modrinth/service/DefaultLocalModScanner.java` | 锁定实例、扫描文件、在线识别、整理记录并保存 |
| 扫描缓存 | `modrinth/service/LocalModScanCache.java` | 读取缓存 JSON，原子替换缓存文件 |
| Mod 安装/启停 | `modrinth/service/ModInstallationService.java`、`DefaultModManagementService.java`、`modrinth/transaction/` | 事务式文件替换、索引更新、依赖关系校验 |
| 升级编排 | `modrinth/service/InstanceUpdateService.java` | `preview` → `prepare` → `execute` 三段式；“一键升级”入口 |
| 升级恢复点 | `modrinth/service/InstanceUpgradeRecoveryService.java` | `upgrade-recovery/<id>/` 的 begin/complete/inspect/restore 与中断恢复 |
| 整合包安装与更新 | `modrinth/pack/MrpackInstaller.java` | 读取、预览（`prepareUpdate`）、安装与更新（`applyPreparedUpdate`） |
| 整合包事务 | `modrinth/pack/PackUpdateTransaction.java` | 暂存、日志、提交、回滚与崩溃恢复（实例/profile/versions/libraries 四类作用域） |
| 事务目标路径 | `modrinth/pack/PackTransactionPaths.java` | 在实例、profile、版本和依赖库范围内编码、解析目标路径 |
| 整合包清单 | `modrinth/pack/PackManifest.java` | `.ecl-pack-manifest.json` 的读写与 SHA-512 校验 |
| 内容 API | `modrinth/api/DefaultModrinthApiClient.java` | HTTP、重试、2 分钟缓存与响应映射 |
| 内容下载 | `modrinth/download/` | 文件选择、哈希校验、下载与依赖守卫 |
| 本地服务端 | `server/LocalServerManager.java`、`LocalServerProfile.java`、`LocalServerFiles.java` | `servers/<id>/` 的导入、设置、进程控制与控制台日志 |
| 崩溃分析 | `launcher/CrashAnalyzer.java` | 读取 `crash-reports` 并生成中文诊断 |

`ecl-core/src/main/java/com/ecl/` 下还有 `config/`（设置键与持久化）、`desktop/`（快捷方式）、
`i18n/`（本地化）、`util/`（HTTP、文件、锁、限速、Java 运行时等基础设施）、`exception/`（业务异常）。

`SettingsManager.migrateToEncrypted` 在同一同步方法内完成旧明文凭据迁移；`GameDownloader` 直接调用
`InstallHelpers.needsDownload` 判断文件是否需要下载。`ModBrowserView` 的分类选项为私有 record，
批量更新直接使用 `SequentialBatchRunner`，由执行器保存选择列表的不可变副本。

## 同一职责的文件合并

- `download/DownloadTaskEntry.java`：任务可变状态、只读快照、终态判断与异步错误消息；替代原 `DownloadTaskSnapshots`。调度和执行仍分别由 `DownloadTaskCenter`、`DownloadTaskExecutor` 负责。
- `util/HttpUtil.java`：保留公开 `ProgressCallback` / `SourceCallback`，断点下载器直接使用；删除重复的内部回调接口和纯转发适配器。源通知辅助与传输实现一起维护。
- `util/ResumableFileDownloader.java`：断点传输及专用嵌套 record `PartialDownloadMetadata`；磁盘 JSON 键不变。
- GUI `ui/ContentTarget.java`：内容分类描述与 `create` 工厂；替代原 `ContentTargetFactory`，四类内容及实例目录解析规则不变。
- `auth/offline/OfflineSkinServer.java`：共享服务生命周期和私有嵌套 `SharedLeases`，协议、签名及角色注册仍独立。
- `util/HttpRequestExecutor.java`：请求执行和专用嵌套 `ResponseBodyInputStream`，维持正文空闲超时与关闭语义。
- `modrinth/api/DefaultModrinthApiClient.java`：嵌套 `ModrinthHttpTransport` 只作为该客户端的传输注入点。
- GUI `modrinth/ui/InstalledModsPane.java` / `ModUpdatesPane.java`：各自包含专用行渲染器，不合并两个列表或后台操作。
- `modrinth/download/ModrinthDownloader.java`：内联内容模型适配并直接执行依赖环检测，替代 `ModrinthContentMapper`、`ModrinthDependencyGuard`；校验与异常信息保持原样。
- `modrinth/download/ModrinthFileSelector.java`：已有文件复用只保留生产使用的大小与哈希联合校验；测试不再经过下载器转发或使用旧的 SHA-1 重载。
- GUI `ui/LauncherExecutableResolver.java`：生产与测试共用 EXE 定位实现，移除 `LauncherUIView` 中仅供测试使用的转发及未调用的加载器名称转发。

单一调用者不自动代表可以合并：加密存储与设置自动保存、微软会话状态与认证流程、整合包路径校验与文件事务、元数据解析与扫描缓存继续独立，分别承担不同规则。

## 阅读关键流程

### 界面启动与窗口装配

从 `LauncherUIView.start` 阅读服务初始化、启动配置加载和窗口展示的顺序，再到
`LauncherWindowLayout` 查找窗口框架、顶栏账户入口、导航和页脚。窗口按钮与拖动行为继续由
`LauncherWindowChrome` 处理；页面切换由 `LauncherPageRouter` 负责（含 `ecl.reduceMotion` 判断），
Mod 拖入事件在窗口框架建成后安装。

### 游戏启动准备

`GameLaunchCoordinator.launchGame` → `GameLaunchPreparation.prepareAndLaunch`：先拦截未完成的升级恢复点，
再检查加载器一致性、保存设置、调用 `VersionManager.ensureVersionDownloadedAsync` 补齐文件，最后交给
`startGame`。`startGame` 里的关键顺序是：恢复中断的世界还原 → 自动备份 → `GameProcessMarker.isRunning` → 实例锁 →
`InstanceLaunchProfileStore.load` → `DefaultLauncher.launch` → `GameProcessMarker.record` →
`GameProcessMonitor.monitor`。实例详情“启动设置”弹窗是 `InstanceLaunchProfileDialog`。

### 游戏版本下载

`downloadVersionInternal` 在同一版本锁中依次保存元数据、调用 `downloadClient`、下载依赖库和资源，
各阶段之间检查取消，最后写入下载完成标记。`downloadClient` 处理客户端下载、校验和失败清理，
没有客户端下载信息时检查可用的继承版本；`addLibraryArtifact` 与 `addNativeArtifact` 分别整理普通库和原生库。

### 检查与修复

`InstanceCheckRepairWorkflow.start` 在 `DownloadTaskCenter` 任务里调用 `GameInstallationRepairService.inspect`
（内部新建 `GameInstallationVerifier`），展示报告后由 `InstanceCheckRepairWorkflow.confirmRepair` 触发
`GameInstallationRepairService.repair`：重新 inspect 比对 `Report.fingerprint`，再执行 `runBaseRepair`
与 `downloadLibrariesForVersion`，最后再 inspect 生成 `Result(before, after)`。不可验证的项不会被“猜”出来。

### 升级事务（preview / prepare / execute）

`InstanceUpdateService` 拆成三段：

- `preview(...)`：读 profile 与本地模组、查加载器与模组更新，产出 `Preview`（含 `fingerprint`、`protectedProjects`、`skippedMods`）。
- `prepare(preview, selectedProjects)`：`requireFresh` 后解析依赖并固定文件计划，产出 `PreparedExecution`。
- `execute(execution, listener)`：再次 `requireFresh`，调用 `InstanceUpgradeRecoveryService.begin` 建立恢复点，
  逐个调用 `ModUpdateService.applyPreparedUpdate`，成功后 `complete` 只发布真正变化的条目。

`Preview.packProfile()` 为真时走整合包分支：`ModpackUpdateService.prepareUpdate` → `applyPreparedUpdate`，
对应 `MrpackInstaller.prepareUpdate` 与 `applyPreparedUpdate`。

### 升级恢复点

`InstanceUpgradeRecoveryService` 的状态机：`begin` 写 `pending/recovery.json`（`PREPARING`）→ 升级 →
`complete`/`inspect` 收尾为 `active`（`ACTIVE`）；`restore` 先转 `RESTORING`（写回清单以支持中断续做），
逐条回退并标记 `restored=true`，最终 `RESTORED`。`inspect` 前会先修复中断的 Mod/整合包事务；
`hasUnfinishedOperation(profileId)` 被启动流程用来阻止实例启动。字段见 `docs/data-formats.md`。

### 模组依赖解析

从 `resolve` 的兼容性检查进入 `visit`：先检查深度、循环、重复版本和数量限制，再顺序解析各个依赖，
最后将当前版本追加到安装顺序。`handleDependency` 按类型分派，`resolveRequiredDependency` 处理必需依赖，
`resolveOptionalDependency` 区分已选与未选的可选依赖，并保留对应的失败或警告行为。
`ResolutionContext` 保存一次解析的选择、路径校验缓存与结果集合，各次解析互不共享这些状态。
必需依赖仅复用启用的记录；禁用依赖会进入安装计划。`detectIncompatible` 有 `versionId` 时按具体版本匹配，否则按项目匹配，并排除禁用及已被计划替换的旧记录。

### 旧版资源与日志配置

`AssetIndex` 只解析版本 JSON 的 `id/url/sha1/totalSize` 描述；`virtual` 与 `map_to_resources` 由
`download/LegacyAssetMaterializer` 读取并校验磁盘上的 `assets/indexes/<id>.json`。下载时生成共享虚拟资源，
`DefaultLauncher.prepare` 在启动前按实际运行目录补齐虚拟资源或 `resources/`，已有安装也走此流程。
`gameAssetsDirectory` 只解析路径供命令构建使用，`preview` 不生成文件；同大小的已有资源仍比较内容，替换经临时文件发布。

`game/ClientLoggingConfig.parse` 校验 `logging.client` 的参数、文件 ID、URL、SHA-1 和大小；
`ensureDownloaded` 在安装、修复和启动准备阶段复用有效配置或限量下载、校验并原子发布。
`GameInstallationVerifier` 以 `LOGGING` 类别报告结果，非法配置按无法验证的元数据处理。
`VersionRepository.mergeLibrary` 在完整 Maven 坐标变化时移除父 artifact，保留平台分类信息；
`Library`、`GameDownloader` 与校验器的 Maven 回退均支持仅继承了 classifiers 的条目。

`DefaultModrinthApiClient.get` 先登记请求，再注册完成处理；缓存写入和移除在同一键的原子更新中执行，立即完成的响应也能过期或重试。

### 下载任务结束

`GameDownloadBatchExecutor.downloadSingleVerified` 在文件发布锁内使用目标根目录下 `.ecl-downloads/` 中的稳定暂存文件，
名称取自规范化目标路径对应的发布锁文件名，附加 `.download`。该目录保留给断点状态，不能作为正式文件目标，
与目标在同一文件系统以保持原子发布，也避免暂存文件与其他正常目标的文件名冲突。
`HttpUtil` / `ResumableFileDownloader` 将传输断点与来源校验信息保存在其 `.part` / `.part.meta` 文件中；
传输失败保留断点，重试复用同源的 ETag / Last-Modified，完成后仍检查元数据大小与 SHA-1 才发布最终文件。
已传完但校验失败的暂存文件会删除，原目标保持不变。

`finishSuccess`、`finishFailure`、`finishCancelled` 都进入 `finish`。该方法在调度器锁内
确定最终状态、释放并发名额并裁剪历史；锁外完成 future、通知监听器并调度后续任务。
取消请求优先于操作返回的成功或失败。任务仍在取消中时继续占用并发名额，直到执行器报告结束。

### 扫描本地 Mod

`scanBlocking` 保留流程编排：获取实例锁 → 准备目录 → 读取旧记录与缓存 → 扫描 → 在线识别
→ 整理记录 → 保存索引与缓存。进一步追踪时：

- `scanFiles` 枚举启用和禁用目录，`scanFile` 处理单个文件的哈希与 JAR 元数据。
- `recognizeFiles` 封装在线查询及失败时的本地回退。
- `reconcileRecords` 整理已识别、未知、损坏、缺失文件，并生成重复项目警告。
- `LocalModScanCache` 保持 `launcher-mod-scan.json` 的读写格式，损坏缓存按空缓存处理。

模组管理服务检查内存状态和 `GameProcessMarker`，取得进程内操作锁后再次检查；安装服务也在锁后检查两者。
扫描写入在取得 `InstanceOperationLease` 后检查两者；`FileModInstallationTransaction` 的构造与中断恢复
在取得跨进程操作锁后检查持久化标记，运行中或标记不可读时拒绝写入。只读 `inspect` 不保存索引和缓存。
`DefaultAccountService.read` 在账户文件缺失时返回空列表；已存在文件的 JSON 根结构异常会抛出 `AuthException`，
所有账户修改共用此读取路径，避免将异常结构覆盖成新账户数组。

模组安装、启停、卸载与本地导入用同一 session 连续保护恢复、索引读取和提交；事务借用 session 而不重新获取物理锁。session 统一由 `InstanceOperationLock.acquireSession` 取得（`acquire` 只保护进程内互斥，不足以覆盖读改写）。`FileModInstallationTransaction` 先校验日志结构和全部目标／备份路径再恢复，损坏日志保留现场；`oldBackup` 存在但日志没有 `oldFile` 时拒绝恢复。
账户修改入口 `DefaultAccountService.mutate` 在稳定侧车锁内重读和发布；`SettingsManager.save` 在自己的侧车锁内将 `dirtyKeys` 合并到磁盘最新对象，成功后更新 `savedSettings`。两者锁忙均拒绝写入，可重试；相关内存状态不是持久化字段。
`GameLaunchCoordinator.acquireGameLock` 将物理锁获取与标记复查作为同一边界；`ServerBrowserView` 在 FX 线程执行目录结果时检查 `closed`，手动刷新清除旧状态后以 `reprobe` 重新探测，目录请求失败也走同一路径。

### 整合包事务

先从 `stageReplacement`、`stageExternalDirectory`、`stageDeletion` 和 `commit` 阅读正常路径，再看
`rollbackEntries` 和 `recoverIncompleteTransactions`。日志继续使用 `PREPARED`、`APPLYING`、`APPLIED` 状态；
目标路径限定在实例目录、profile JSON、`versions/` 与 `libraries/` 四类作用域内。
`PackTransactionPaths` 集中处理目标范围与日志路径解析；文件移动、锁和回滚仍由事务类负责。
`MrpackInstaller.prepareUpdate` 会先要求旧的 `.ecl-pack-manifest.json` 存在，否则拒绝预览（保证有回滚依据）。

### 本地服务端

`LocalServerManager.list/importJar/saveSettings/setEulaAccepted/start/stop/sendCommand/isRunning/canControl/readLogTail`
覆盖完整生命周期；`profile()` 与 `locked()` 保证目录与锁一致。`locked()` 用 `tryLock` 重试（最长 5 秒）而不是阻塞式
`lock()`，避免另一个 ECL 进程持锁时永久卡住 UI 线程。`stop()` 发送 `stop` 后最多等待 60 秒，返回 `"stopped"` 或
`"stop-timeout"`（超时不强杀，避免在存档写入中途损坏世界）。`isRunning` 同时比对 PID 与进程启动时间，
因此启动器重启后仍能识别服务端状态，但 `sendCommand` 会因为拿不到旧进程的标准输入而报“无法接管控制台”。

### 世界备份

`WorldBackupService.createBackup`（实例锁 + profile 锁）→ `ZipUtil.zipDirectories` → 写同名 `.json` 元数据 →
原子发布；`restore` 委托 `WorldBackupRestorer`，`prune` 按创建时间倒序保留 `keepCount` 份。
备份内容枚举见 `BackupEntry.Content`（`saves`、`mods`、`shaderpacks`、`resourcepacks`、`config`）。
`withInstanceLock` 获得生命周期锁后检查 `GameProcessMarker`，再调用 `WorldBackupRestorer.recover`。
还原使用 `<运行目录>/.ecl/world-restore/journal.json` 的 `APPLYING` / `COMMITTED` 两阶段日志；异常退出后逆序回滚旧目录，成功提交则只清理工作区。GUI 启动游戏前调用 `recoverInterruptedRestore`，不依赖是否启用自动备份。
升级恢复的 `Draft` 从 `begin` 至 `complete` 持有 `<数据目录>/upgrade-recovery/<profile ID>/operation.lock`；检查与回退也必须取得该锁，避免将正在升级的 `pending` 当成中断现场。
`InstanceUpdateService.execute` 在实例会话内核对指纹、创建快照、顺序更新并完成 after 快照，内部服务通过 session 重载借用；`Draft.session` 和 `Draft.ownership` 为内存所有权，不写入磁盘。完成处理不再次调度到可关闭执行器，观察者取消不取消内部收尾。
`MrpackInstaller.UpdatePreview.preparedSha512` 固定暂存树及归档的 SHA-512，应用前再次校验；`PackUpdateTransaction.hasIncompleteTransactions` 精确匹配实例与 UUID，完整 APPLIED 日志不阻塞预览。直接 restore 同样先处理 pending/previous。
`BackupManagerDialog` 接受固定目标 profileId，列表与操作离开 FX；`InstanceLaunchProfileDialog` 的读取、Java 探测、加锁和保存也在后台执行。`ServerManagementPage` 展示真实 `StopOutcome`，停止等待不占共享页面执行器。`MainController` 的通用后台任务使用受管理虚拟线程，下载并行数仍由下载器限制。

实例删除由 `InstanceDetailsPane.deleteInstanceFiles` 以配置游戏根目录的 `versions/` 为边界；保留共享数据与 ECL 元数据缓存。
实例详情的 `refresh` 只更新概览和按钮，显示表单仅在 `setInstance` 时载入，维护完成不会覆盖草稿。
备份管理与模组浏览主页文案使用三语 `Messages`；旧的 `ServerJarDownloadPage` 和未接线的 `ReduceMotion` 已删除，服务端安装保留 `ServerInstallWorkflow`。
运行时版本由 `ECLConfig.readLauncherVersion` 读取 Gradle 生成的 `ecl-build.properties`，发布入口用 `validateReleaseVersion` 检查 tag。

## 修改后的验证

需要 JDK 21。在仓库根目录运行：

```powershell
# 核心模块测试
.\gradlew.bat :ecl-core:test

# 单个测试类
.\gradlew.bat :ecl-core:test --tests com.ecl.util.FileUtilTest
.\gradlew.bat :ecl-core:test --tests com.ecl.download.GameDownloaderTest

# 全项目测试、Checkstyle、SpotBugs 与覆盖率门槛
.\gradlew.bat check

# 生成包含各模块 JAR 的分发目录
.\gradlew.bat installDist

# 界面改动后生成快照
.\gradlew.bat captureLauncherUi
```

核心测试位于 `ecl-core/src/test/java/`，GUI 测试位于 `ecl-gui/src/test/java/`。
新增职责默认使用包级可见的辅助类；只有外部调用方需要时才扩展公共 API。
拆分方法时保留异常传播、锁范围、回调时机和持久化格式，并用对应行为测试验证。
本次相关的新增测试可参考 `GameInstallationVerifierTest`、`GameInstallationRepairServiceTest`、
`InstanceUpgradeRecoveryServiceTest`、`InstanceLaunchProfileStoreTest`。

启动前补齐下载由 `GameLaunchPreparation` 限时等待 10 分钟，超时或中断取消对应的下载 Future，并通过任务完成回调解除界面忙状态。
`InstanceCheckRepairWorkflow` 和 `InstanceUpgradeRecoveryWorkflow` 在任务中心拒绝提交时也会清除忙状态并刷新操作按钮。
GUI 文案由 `GuiMessages` 查询，缺键时尝试英文，仍缺失则显示 key，避免构建页面时抛异常。
`util/LogDirectoryProperty.java` 是 Logback 的目录解析入口，直接复用 `ECLConfig.getBaseDir()`；设置页打开同一数据目录下的 `logs/`。
快捷方式由 `LauncherDesktopIntegration.createInstanceShortcut` 在后台调用 `DesktopShortcutService`，结果通过 JavaFX 线程反馈；创建期间保留点击时捕获的实例 ID。

FileUtil.requireUnstartedTransaction / requireNoTransactionBackups 是模组和整合包缺失日志时的保留边界；不得仅以 journal.json 不存在推断事务从未改动目标。校验器的 instancesRoot 指实际游戏根目录的 versions/，与版本元数据根目录分别注入，hint:local 按真实实例目录查找。
