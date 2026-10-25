# 架构说明

本文描述 ECL 的模块依赖、`ecl-core` 各包职责、`ecl-gui` 分层，以及五条关键流程的调用顺序。所有类名均来自仓库源码；包为空目录时不再列出。

## 模块与依赖

```text
             ┌───────────────────────────┐
             │ ecl-boot                  │
             │  com.ecl.ECL（入口）      │
             │  检查 JavaFX → ECLauncher │
             └─────────────┬─────────────┘
                           │ implementation
             ┌─────────────▼─────────────┐        ┌──────────────────────────┐
             │ ecl-gui                   │        │ ecl-dist                 │
             │  JavaFX 页面/控制器/工作流 │        │  jpackage 任务           │
             │  /CSS/图标/i18n 资源      │        │  （仅 Exec，无 Java 源码）│
             └─────────────┬─────────────┘        └────────────▲─────────────┘
                           │ api(project(":ecl-core"))           │ dependsOn :ecl-boot:installDist
             ┌─────────────▼─────────────┐                        │
             │ ecl-core                  │                        │
             │  认证/下载/启动/实例/整合包│────────────────────────┘
             │  平台无关服务              │
             └───────────────────────────┘
```

- 依赖方向只有 `ecl-boot` → `ecl-gui` → `ecl-core`；`ecl-boot` 同时直接依赖 `ecl-core`（`ecl-boot/build.gradle.kts`）。
- `ecl-dist` 不依赖任何模块的类，只通过 `:ecl-boot:installDist` 的产物调用 `jpackage.exe`。
- `config/` 与根 `build.gradle.kts` 为所有 Java 模块统一应用 Checkstyle、SpotBugs、JaCoCo（覆盖率门槛见 README“构建与验证”）。
- `ecl-core` 与 `ecl-gui` 共四个模块中，**三个 Java 模块**（`ecl-boot`、`ecl-core`、`ecl-gui`）各自持有 `gradle.lockfile`；`ecl-dist` 没有锁文件。

## ecl-core 包职责

包路径前缀 `ecl-core/src/main/java/com/ecl/`。

| 包 | 职责 | 代表类 |
| --- | --- | --- |
| （根） | 全局常量、目录解析与内存策略 | `ECLConfig`（`KEY_*` `SettingKey`、`getBaseDir`、`calculateAutoMemoryMb`） |
| `auth` | 账户模型、离线/微软认证、皮肤 | `DefaultAccountService`、`MicrosoftAuth`、`MicrosoftOAuthClient`、`XboxLiveAuthClient`、`MinecraftServicesClient`、`MinecraftSkinService`、`MinecraftProfileSkinService`、`OfflineSkinStore`、`AuthAccount`、`AuthType` |
| `auth.offline` | 离线皮肤注入（本地 Yggdrasil 兼容服务） | `AuthlibInjectorManager`、`OfflineSkinServer`、`OfflineSkinHttpHandler`、`OfflineSkinInjector`、`OfflineSkinTextureSigner`、`OfflineSkinCharacterRegistry` |
| `backup` | 世界备份创建/恢复/清理 | `WorldBackupService`、`WorldBackupRestorer`、`WorldBackupMetadata`、`BackupEntry` |
| `config` | 设置持久化与迁移 | `SettingsManager`（含旧明文凭据迁移）、`SettingKey`、`EncryptedSettingsStore`、`SettingsAutoSaveScheduler` |
| `desktop` | Windows 快捷方式 | `DesktopShortcutService` |
| `download` | 下载调度、游戏下载、校验与修复 | `DownloadTaskCenter`、`DownloadTaskExecutor`、`DownloadService`、`GameDownloader`、`GameDownloadBatchExecutor`、`InstallHelpers`、`GameInstallationVerifier`、`GameInstallationRepairService`、`LegacyGameInstallationVerifier`、`ServerJarDownloader`、`GameDownloadErrorClassifier` |
| `download.provider` | 官方源/镜像候选与选择 | `DownloadProvider`、`DownloadProviderRegistry`、`OfficialDownloadProvider`、`MirrorDownloadProvider` |
| `exception` | 业务异常 | `AuthException` |
| `game` | 版本元数据、实例路径策略与实例配置 | `DefaultGameRepository`、`VersionRepository`、`InstanceLaunchProfile(Store)`、`InstanceGameSettings(Store)`、`InstanceDisplayMetadata(Store)`、`DefaultIsolationType`、`VersionMetadata`、`Library`、`LibraryAnalyzer`、`WorldSaveService`、`MavenCoordinates` |
| `i18n` | 本地化接口与实现 | `I18n`、`ResourceBundleI18n` |
| `launch` | 启动参数构建、进程与运行标记 | `DefaultLauncher`、`Launcher`、`LaunchCommandBuilder`、`LaunchOptions`、`LaunchEnvironment`、`NativeLibraryExtractor`、`GameProcess`、`GameProcessMarker`、`ProcessOutputPump`、`BoundedLogBuffer`、`ServerAddress` |
| `launcher` | 版本管理、加载器安装、服务端安装、崩溃分析 | `VersionManager`、`VersionManifestStore`、`VersionCatalogService`、`VersionProfileResolver`、`LocalVersionProfileScanner`、`ModLoaderInstaller`、`LoaderMetadataClient`、`LoaderUpdateService`、`LoaderProfileWriter`、`ServerInstaller`、`CrashAnalyzer`、`InstallerProcessRunner`、`VersionDownloadTargetResolver` |
| `modrinth.api` | Modrinth HTTP 客户端与缓存 | `DefaultModrinthApiClient`、`ModrinthApiClient`、`ModrinthApiConfiguration`、`ModrinthResponseMapper`、`ModSearchIndex`；DTO 在 `api/dto/` |
| `modrinth.download` | 内容模型适配、依赖环检测、文件选择、哈希校验、下载 | `ModrinthDownloader`、`ModFileDownloadService`、`ModrinthFileSelector`、`HashVerifier`、`DownloadUriResolver` |
| `modrinth.instance` | 实例上下文抽象 | `ModInstanceContext`、`VersionProfileModInstanceContext`、`ModLoader` |
| `modrinth.model` | 内容与模组模型 | `ModProject`、`ModVersion`、`ModFile`、`InstalledMod`、`ModUpdate`、`ContentProject`、`ContentVersion`、`ReleaseChannel` |
| `modrinth.pack` | `.mrpack` 安装与更新事务 | `MrpackInstaller`、`MrpackFileInstaller`、`MrpackIndexReader`、`MrpackOverrideExtractor`、`PackManifest`、`PackUpdateTransaction`、`PackTransactionPaths`、`DefaultModpackUpdateService`、`ModpackUpdateService`、`ModpackInstance`、`ModpackUpdate` |
| `modrinth.provider` | 内容元数据提供者 | `ModMetadataProvider`、`ModrinthMetadataProvider` |
| `modrinth.repository` | 已安装模组索引 | `InstalledModRepository`、`FileInstalledModRepository` |
| `modrinth.service` | 扫描、依赖解析、安装/更新/回退编排 | `InstanceUpdateService`、`InstanceUpgradeRecoveryService`、`DefaultLocalModScanner`、`DefaultModDependencyResolver`、`DefaultModManagementService`、`DefaultModUpdateService`、`DefaultModVersionSelector`、`LocalModScanCache`、`LocalModMetadataReader`、`InstanceOperationLock`、`SequentialBatchRunner` |
| `modrinth.transaction` | Mod 文件安装计划与事务 | `FileModInstallationTransaction`、`InstallationPlanBuilder`、`ModInstallationPlan`、`PlannedModFile` |
| `operation` | 实例级互斥 | `InstanceOperationCoordinator`（`acquire` / `tryAcquire` / `acquireInterruptibly`） |
| `server` | 本地服务端管理 | `LocalServerManager`、`LocalServerProfile`、`LocalServerFiles` |
| `util` | 通用基础设施 | `HttpUtil`、`FileUtil`、`JsonUtil`、`GsonProvider`、`TextUtil`、`CryptoUtil`、`CryptoKeyStore`、`FileLockLease`、`InstanceOperationLease`、`ManagedLockPaths`、`NetworkUriPolicy`、`DownloadRateLimiter`、`DownloadConcurrencyGate`、`ResumableFileDownloader`、`JavaRuntimeUtil`、`JavaRuntimeDownloader`、`ZipUtil`、`BoundedCache`、`ThreadFactories`、`JvmArgumentPolicy`、`OperatingSystem`、`Messages` |

除实例操作协调器外，跨进程互斥都基于文件锁：`FileLockLease`（`FileChannel.tryLock`，拿不到返回 `null`）；服务端目录锁在 `LocalServerManager` 内用 `tryLock` 重试最多 5 秒，超时转 `IOException`。这些锁都不会无限阻塞等待。OS 判定统一走 `util/OperatingSystem`（`isWindows`/`minecraftName`/`adoptiumName`/`javaExecutableName`），不再各自读取 `os.name`。

## ecl-gui 分层

包路径前缀 `ecl-gui/src/main/java/com/ecl/`，共四个包目录：`com.ecl`（入口）、`com.ecl.ui`（外壳与页面）、`com.ecl.modrinth.ui`（含 `viewmodel` 子包，内容库界面）、`com.ecl.server`（服务器目录界面）。

| 层 | 代表类 | 说明 |
| --- | --- | --- |
| 入口 | `ECLauncher` | `Application.launch(LauncherUI.class)`；`ecl-boot` 的 `ECL` 只做 JavaFX 存在性检查后转调 |
| 应用外壳 | `LauncherUI`（继承 `LauncherUIView`）、`LauncherWindowLayout`、`LauncherWindowChrome`、`LauncherThemeManager`、`LauncherNavigationRail`、`LauncherInstanceBar`、`LauncherStatusBar`、`LauncherPageRouter` | 固定五区窗口、页面切换动画、语言与主题 |
| 服务与后台任务 | `MainController` | 创建并持有全部 `ecl-core` 服务、线程池、任务名 MDC；`close()` 按序释放 |
| UI 外观工具 | `LauncherUiFactory`、`ForestIcons`、`LauncherProgressController`、`RuntimeSummaryPresenter` | 控件构造、图标、进度脉冲 |
| 页面工厂 | `LauncherPageFactory`、`HomePageFactory`、`ContentLibraryPageFactory`、`LauncherContentBrowser` | 按导航构建页面；`ContentTarget.create` 目前生成 **4 个**内容分类（模组/光影包/材质包/整合包），服务端下载从“游戏实例 → 服务端”进入 |
| 功能页 | `InstalledInstancesPage`、`InstanceDetailsPane`、`InstanceInstallPage`、`WorldSavesPage`、`WorldSaveExplorer`、`ServerManagementPage`、`ServerBrowserView`、`AccountManagementPage`、`SettingsDialog`、`GlobalGameSettingsPane`、`BackupManagerDialog`、`LoaderInstallDialog`、`CrashDiagnosticDialog`、`FirstRunWizard`、`InstanceLaunchProfileDialog` | 具体页面与弹窗 |
| 工作流 | `GameLaunchCoordinator` + `GameLaunchPreparation` + `GameProcessMonitor`、`InstanceInstallWorkflow`、`ServerInstallWorkflow`、`InstanceUpdateWorkflow`、`InstanceCheckRepairWorkflow`、`InstanceUpgradeRecoveryWorkflow`、`LauncherLoaderWorkflow`、`LauncherAuthController`、`MicrosoftAccountCoordinator`、`SkinCoordinator`、`LauncherLaunchForm`、`ContentDownloadWorkflow`、`ContentSearchController`、`com.ecl.modrinth.ui.ModInstallWorkflow`、`com.ecl.modrinth.ui.viewmodel.ModBrowserUpdateCoordinator` | 把 UI 事件翻译成服务调用，负责忙状态与完成回调 |
| 状态与适配 | `InstanceSelectionState`、`InstanceDisplayMetadataCache`、`LauncherPathService`、`VersionActions`、`InstanceVersionCatalog`、`LaunchUiFacade` / `LauncherUiFacadeAdapter`、`SettingsPageGuard`、`DownloadSection`、`ContentTarget`、`ModDropImportHandler`、`LauncherExecutableResolver`、`LauncherDesktopIntegration` | 启动目标状态、路径解析、未保存修改守卫 |
| 内容库 UI | `com.ecl.modrinth.ui`: `ModBrowserView`、`ModDetailsPane`、`InstalledModsPane`、`ModUpdatesPane`、`MinimalMarkdown`、`RemoteImageLoader`、`ChineseDescriptionService`；`viewmodel`: `ModBrowserViewModel`、`ModBrowserSearchController`、`ModBrowserOperationState`、`InstalledModController`、`ModDependencyBrowserLoader` | 模组浏览、安装、更新与描述渲染 |
| 服务器 UI | `com.ecl.server`: `ServerStatusService`、`ServerDirectoryService`、`ServerCatalog(Filter)`、`ServerBrowserActions`、`ServerListCell`、`ServerStatusProbeController`、`ServerCategory` | 公开目录与在线状态探测 |

同一职责的专用辅助实现归属其类型：`DownloadTaskEntry` 同时生成任务快照、判断终态和提取错误消息；下载直接复用 `HttpUtil.ProgressCallback` / `SourceCallback`，`ResumableFileDownloader` 负责空值安全通知；`ResumableFileDownloader.PartialDownloadMetadata` 只服务于断点传输；`ContentTarget.create` 与分类描述共同维护。`OfflineSkinServer.SharedLeases` 保留独立的类监视器，避免把共享租约锁混入实例注册锁；`HttpRequestExecutor.ResponseBodyInputStream` 保留正文超时调度器；`DefaultModrinthApiClient.ModrinthHttpTransport` 保留可注入传输契约；`InstalledModsPane.InstalledModCell` 和 `ModUpdatesPane.ModUpdateCell` 只渲染各自列表。合并不跨越调度、执行、传输、存储或 UI 工作流边界。

## 关键流程

模组安装与管理从中断恢复、索引读取到文件及索引提交持续持有同一 `InstanceOperationSession` 的物理锁；恢复与事务显式借用该 session。`InstanceOperationLock.acquireSession` 是这条连续区间的唯一入口；`acquire` 只持有进程内锁，无法阻止另一个启动器在读取索引与提交之间改写索引，因此不再用于读改写序列。本地 JAR 导入也先恢复旧事务。Mod 日志在任何回滚或清理之前验证完整结构与路径，无效日志保留现场；`oldBackup` 存在而日志未记录 `oldFile` 时属于无法解释的备份，拒绝恢复以免唯一副本随事务目录被清理。

设置保存和账户修改分别通过稳定的 `settings.json.lock` / `accounts.json.lock` 保护读改写。设置仅合并相对内存基线变化的键，避免整个旧缓存覆盖其他进程的修改；损坏设置归档前锁内复读。正常设置读取继续使用原子文件快照。

游戏启动在取得实例物理锁后复查持久化进程标记；服务器目录结果在 FX 回调执行时复查页面生命周期，手动刷新先使旧状态失效再发起探测。

内容库的模型适配与依赖环检测由 `ModrinthDownloader` 自身维护。已有文件复用统一走
`ModrinthFileSelector.existingFileSatisfies(File, ModFile)`，同时检查声明大小与哈希；不再保留仅供测试使用的 SHA-1 重载。
启动器可执行文件定位由 `LauncherExecutableResolver` 独立负责，测试直接调用该实现。

### 1. 启动准备

```text
首页“启动游戏”
  └─ GameLaunchCoordinator.launchGame
       ├─ 已在运行？→ 状态提示并返回
       └─ GameLaunchPreparation.prepareAndLaunch
            ├─ 有中断的升级/回退？→ 阻止启动（InstanceUpdateService.hasUnfinishedRecovery）
            ├─ 加载器与所选加载器不一致？→ 先安装加载器
            ├─ 保存设置（settings.json）
            ├─ VersionManager.ensureVersionDownloadedAsync
            │     ├─ 已就绪 → startGame
            │     └─ 缺失 → DownloadTaskCenter 提交 GameDownloader 补齐客户端/依赖库/资源
            └─ GameLaunchCoordinator.startGame（后台线程）
                 ├─ WorldBackupService.recoverInterruptedRestore（检查运行标记并恢复中断的世界还原）
                 ├─ 创建启动前自动备份（WorldBackupService）
                 ├─ GameProcessMarker.isRunning → 防止重复启动
                 ├─ InstanceOperationLease.tryAcquire（生命周期锁 + 实例内 operation.lock）
                 ├─ InstanceLaunchProfileStore.load（Java/内存/JVM 参数）
                 ├─ LaunchOptions.builder … → DefaultLauncher.launch
                 │     └─ prepare：解压原生库 → LegacyAssetMaterializer.prepare（实际运行目录）
                 │                  → ClientLoggingConfig.ensureDownloaded → 构建命令
                 ├─ GameProcessMarker.record（写 .ecl/game-process.json）
                 └─ GameProcessMonitor.monitor（守护线程；退出时 CrashAnalyzer 分析）
```

### 2. 下载修复

```text
实例详情“检查与修复”(#instance-check-repair)
  └─ InstanceCheckRepairWorkflow.start
       ├─ 启动准备中？→ 提示并返回
       ├─ DownloadTaskCenter 任务内 GameInstallationRepairService.inspect
       │     └─ GameInstallationVerifier.inspect(profileId)
       │           ├─ METADATA / CLIENT / LIBRARY / NATIVE / ASSET_INDEX / ASSET_OBJECT / LOADER_DEPENDENCY / LOGGING
       │           └─ 每个 Check = VERIFIED / MISSING / CORRUPT / UNVERIFIABLE + repairable
       ├─ 展示报告（可修复项才启用“修复”）
       └─ 用户确认 → GameInstallationRepairService.repair
             ├─ 校验报告与下载目标属于同一实例
             ├─ 实例操作锁 + InstanceOperationLease（拿不到即“实例忙”）
             ├─ 重新 inspect，指纹变化 → 报“结果已过期”
             ├─ runBaseRepair：下载元数据（只允许官方 host）→ GameDownloader
             ├─ downloadLibrariesForVersion：补齐加载器依赖库
             ├─ ClientLoggingConfig.ensureDownloaded：补齐当前有效 profile 的日志配置
             ├─ LegacyAssetMaterializer.prepare：按实际运行目录生成旧版资源视图
             └─ 再次 inspect 生成 Result（before/after/repaired/failed/unverifiable）
```

修复只写经官方摘要认证的 Minecraft 与加载器依赖文件；`UNVERIFIABLE` 项不会被推测补全，用户模组也不会被改动。

`GameDownloader` 在完成标记写入前补齐版本声明的日志配置。旧版布局标记来自下载后的资源索引，
与版本 JSON 的 `AssetIndex` 描述分开；共享 `virtual/` 可在下载时生成，实例专属 `resources/` 在启动或修复时生成。
`preview` 只解析路径，不生成资源或发起日志配置下载。物化文件按内容比较，通过临时链接/副本原子替换。

继承版本更换完整 Maven 坐标时，父 artifact 不会沿用；父平台原生库可独立保留，子 artifact 由子声明或 Maven 仓库解析。
模组必需依赖只复用启用记录；版本级冲突只匹配该版本，已禁用或将被计划替换的旧记录不参与冲突。
Modrinth GET 的请求登记先于完成回调，缓存发布和移除请求按键原子执行，避免立即完成响应永久留在请求表。

### 3. 升级事务（普通实例）

```text
首页“一键升级”
  └─ InstanceUpdateWorkflow → InstanceUpdateService
       ├─ preview(instance, channel, listener, metadataRoot, gameRoot)
       │     ├─ 若存在未完成整合包事务 → 拒绝预览（只读检查 hasIncompleteTransactions）
       │     ├─ LoaderUpdateService 检查加载器更新
       │     ├─ DefaultLocalModScanner 扫描本地模组
       │     ├─ DefaultModUpdateService.checkUpdates
       │     └─ 产出 Preview（含指纹 fingerprint、可跳过的模组、整合包分支）
       ├─ prepare(preview, selectedProjects)
       │     ├─ requireFresh：重新计算指纹，变化即失败
       │     ├─ ModUpdateService.prepareUpdate：解析依赖并固定文件计划
       │     └─ 产出 PreparedExecution（持有整合包临时目录，需 close）
       └─ execute(execution, listener)
             ├─ 再次 requireFresh（版本与文件指纹）
             ├─ InstanceUpgradeRecoveryService.begin → 在 upgrade-recovery/<id>/pending 记录旧文件快照
             ├─ 逐个 ModUpdateService.applyPreparedUpdate（FileModInstallationTransaction 提交）
             ├─ InstanceUpgradeRecoveryService.complete → 只把真正变化的条目发布为 active
             └─ 任何失败保留旧恢复点，由 restore 流程处理
```

### 4. 整合包事务

```text
整合包升级（预览 → 确认 → 应用）
  ├─ DefaultModpackUpdateService.checkUpdate → ModpackUpdate（版本 + 选中文件）
  ├─ prepareUpdate(update, gameRoot, listener)
  │     └─ MrpackInstaller.prepareUpdate
  │           ├─ 旧的 .ecl-pack-manifest.json 必须存在且可读（否则拒绝，保证能回滚）
  │           ├─ 在 <数据目录>/ecl-modpack-preview-<uuid>/ 解包并校验
  │           ├─ 生成新 PackManifest（SHA-512）、差异列表 FileChange
  │           └─ 产出 UpdatePreview（临时目录，需 close）
  └─ applyPreparedUpdate(prepared, gameRoot, listener)
        └─ MrpackInstaller.applyPreparedUpdate
              ├─ 再次校验实例身份与中断事务
              ├─ PackUpdateTransaction：实例 / profile JSON / versions / libraries 四类作用域
              │     ├─ 写 journal（PREPARED → APPLYING → APPLIED）
              │     ├─ REPLACE / DELETE 逐条应用，覆盖前先移到 backups/
              │     └─ 失败按逆序回滚
              └─ 提交后清理事务目录；未提交的共享 loader 文件仅作为缓存保留
```

### 5. 升级恢复点

```text
实例详情“恢复上次升级”(#instance-restore-last-update)
  └─ InstanceUpgradeRecoveryWorkflow.open
       └─ InstanceUpgradeRecoveryService.inspect(profileId, instance, metadataRoot, gameRoot)
             ├─ 先恢复中断的 Mod/整合包事务，再执行 pending → active 的收尾（finalizePending）
             ├─ 需要实例操作锁 + 实例生命周期锁，且实例不能正在运行
             ├─ 校验 payload SHA-256 与清单身份，对比升级后文件生成冲突列表
             └─ 返回 RestorePoint（state / files / conflicts / pack / restorationPending）

用户确认（有冲突需逐项确认）→ restore(profileId, …, confirmedConflicts)
  ├─ ACTIVE → RESTORING（写清单，支持中断续做）
  ├─ 逐条还原：payload → 临时文件 → 校验 SHA-256 → 原子替换；新增文件直接删除
  ├─ 每条写回 restored=true
  └─ 全部完成 → RESTORED
```

`hasUnfinishedOperation(profileId)` 会在启动准备阶段拦截实例：`pending/` 存在或 `active` 处于 `RESTORING` 时不允许启动。

## 并发与锁

| 锁 | 位置 | 作用 |
| --- | --- | --- |
| 实例操作协调器 | 进程内 `InstanceOperationCoordinator`（`ReentrantLock`） | 同一实例的变更串行；`tryAcquire` 用于快速失败 |
| 实例生命周期锁 | `<运行目录的父目录>/.ecl-instance-locks/<sha256>.lifecycle.lock` | 跨进程；`FileLockLease.tryAcquire` |
| 实例内操作锁 | `<运行目录>/.ecl/operation.lock` | 兼容旧版；与生命周期锁一起构成 `InstanceOperationLease` |
| 升级恢复锁 | `<数据目录>/upgrade-recovery/<profile ID>/operation.lock` | `begin` 至 `complete` 持有；检查/回退快速拒绝正在升级的实例，崩溃后由 OS 释放 |
| 版本下载锁 | `<版本目录>/.ecl-version-locks/<版本 ID>.lock` | 避免同一版本并发下载 |
| 文件发布锁 | `<数据目录>/.ecl-download-locks/<sha256>.lock` | 同一目标文件（libraries/assets）一次只发布一次 |
| 整合包事务锁 | `<实例目录的父目录>/<实例 ID>.pack.lock` + 事务目录 lock | 事务级互斥与恢复标记 |
| 服务端目录锁 | `<数据目录>/servers/<id>/manager.lock` | 服务端设置/进程操作互斥（`tryLock` 重试最长 5 秒，超时转 `IOException`） |

## 网络与信任边界

模组文件及索引写入同时受内存状态、跨进程实例锁与 `GameProcessMarker` 保护。服务取得进程内操作锁后复查状态；
事务构造、事务恢复及扫描持久化在取得跨进程实例锁后复查持久化运行标记，启动器重开也不能改写仍在运行的实例。
标记损坏或不可读时阻止变更并保留文件。

游戏下载在文件发布锁内复用目标根目录下专用 `.ecl-downloads/` 内的稳定暂存路径，该目录不接受正式文件目标。
暂存文件与目标位于同一文件系统，名称按目标路径摘要区分，避免文件名后缀碰撞。网络失败保留 `.part` 和 `.part.meta`，由现有 HTTP 续传器校验来源及
ETag / Last-Modified 后发送 Range；响应完整后再由批处理执行器校验声明大小和 SHA-1，最后原子发布。
校验失败删除已完成的暂存文件，保留原目标。

统一账户库要求 JSON 根节点为数组；非法根结构通过 `AuthException` 向调用方报错，禁止后续保存覆盖原内容。
GUI 账户读取边界捕获该错误，展示保留原文件的提示并允许首页继续构造；写入与旧库迁移仍不能覆盖损坏账户库。

升级从最终指纹检查、before 快照到 after 快照全程持有 `InstanceOperationSession`：协调器的专用虚拟线程持有原有线程绑定锁与跨进程 lease，异步调用显式 `borrow`，文件事务使用互斥的 `borrowMutation`。owner 关闭后禁止新借用，已有借用全部归还才释放锁；取消观察 Future 不会跳过内部任务收尾。加载器、模组和整合包按顺序借用，不能用全局文件锁重入或线程本地状态绕过竞争。

恢复与整合包清单共用 `ManagedPackPathPolicy` 的纯词法校验，拒绝任意 `..` 段并在规范化后排除存档等用户目录；祖先重解析点检查独立执行。Prepared 整合包的文件、清单、版本元数据、依赖与归档摘要在内存中固定，提交前重新验证。`inspect` 与 `restore` 共用 pending/previous 收尾，先验证 payload，再选最新可恢复点。payload 与 manifest 强制落盘，新 active 读回校验后才删除 previous；Windows 目录 fsync 仅尽力执行，不承诺完整的断电原子性。

自动修复复用 `GameInstallationRepairService.canRepair`，逐项下载有声明摘要的文件，读取与写入采用同一组注入根目录。裸 Maven 和 `hint:local` 依赖不由共享库修复流程推测下载。无效日志声明单独报告，其余检查继续；正式解析与启动仍拒绝不安全日志配置。发布锁可中断并有界等待，拿到锁后重新检查竞争者是否已发布正确文件。

世界还原：获得实例生命周期锁并检查持久化游戏运行标记后，将压缩包解到 `.ecl/world-restore/content/`；写入并强制落盘 `APPLYING` 日志后才移动原目录至 `rollback/`。替换完成后写 `COMMITTED`，最后清理工作区；中断后回滚 `APPLYING`，`COMMITTED` 仅清理。启动游戏、再次备份或还原前执行恢复；日志损坏或缺失且仍有原始文件时拒绝继续，保留现场。
版本来源为 `gradle.properties:version` → Gradle 资源展开 → `ecl-build.properties:launcher.version` → `ECLConfig.LAUNCHER_VERSION` → GUI / User-Agent / 打包。Release 工作流在构建前校验 tag 与该版本一致。

启动准备下载与检查修复下载均有 10 分钟等待上限；超时会取消下载并结束任务，启动准备中断也会取消对应 Future。
检查修复、恢复升级的任务提交失败时，GUI 工作流会清除忙状态并通知实例详情刷新，避免按钮永久禁用。
日志目录由 Logback 的 `LogDirectoryProperty` 调用 `ECLConfig.getBaseDir()` 解析，与设置页日志入口一致；历史日志不自动迁移。
快捷方式的 PowerShell 进程在后台线程执行，不占用 JavaFX 事件线程；实例 ID 与目标可执行文件路径在提交后台工作前捕获。

- 版本清单与启动元数据只从 Mojang 权威地址读取（`VersionManifestStore`），镜像只用于下载带官方摘要的二进制文件（`MirrorDownloadProvider`：BMCLAPI / FastMinecraftMirror）。
- 所有下载地址经 `NetworkUriPolicy`：强制 HTTPS、拒绝私网/环回解析结果、拒绝带凭据的 URL；HTTP 仅允许显式环回地址。
- 运行时与 Java 运行时下载校验 SHA-256；游戏文件校验大小与 SHA-1；内容库只信任 `cdn.modrinth.com`、`cdn-raw.modrinth.com`。
- 外发域名与用途见 README“网络请求与隐私”章节。

模组及整合包事务缺失 journal 时，只清理已知暂存目录和空备份目录；存在备份或未知内容时拒绝恢复并保留现场。活动事务关闭也不会删除缺失日志的回滚备份（FileUtil.requireUnstartedTransaction / requireNoTransactionBackups）。
