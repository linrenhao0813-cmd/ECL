# ECL Java 源码职责拆分计划

## 1. 目标

对项目主源码中的多职责 Java 文件进行拆分，使每个类拥有清晰、可测试的职责，同时保持现有功能、用户界面、配置格式、公共入口和数据目录兼容。

本计划覆盖 `ecl-core`、`ecl-gui`、`ecl-cli` 和 `ecl-boot` 的主源码。不是所有文件都需要机械拆分：模型、接口、枚举、异常和职责单一的服务保留原文件；职责混杂的文件按下面的目标结构拆分。

## 2. 约束和原则

- 保留 `LauncherUI`、`ModBrowserView`、`HttpUtil`、`EclCli` 等现有公共入口。
- 新增的实现类优先使用包级可见性，避免扩大公共 API。
- 拆分过程中不修改 CSS、用户可见文案、配置键、持久化格式和默认目录。
- JavaFX 控件只能在 JavaFX Application Thread 操作；异步下载、认证和扫描不能回到 UI 线程执行阻塞操作。
- 下载取消、进度回调、失败/重试语义必须保持不变。
- MRPACK、备份和 Mod 安装的路径安全、哈希校验、事务提交和回滚行为必须保持不变。
- 每个阶段都先保留旧 Facade，再迁移调用方，最后删除重复实现。

## 3. 目标包结构

### 3.1 GUI

```text
com.ecl.ui
├── LauncherUI                      # Application 生命周期和依赖组装
├── LauncherWindowController         # Stage、标题栏、关闭流程
├── LauncherNavigationController     # 导航、页面切换、过渡动画
├── LauncherProgressController       # 状态、进度条、进度动画
├── LauncherPathService              # 游戏目录、实例目录、mods 目录
├── LauncherUiSupport                # 文件夹、URL、格式化、异常消息
├── HomePageView                     # 首页和启动摘要
├── VersionPageView                  # 版本列表和版本页面
├── ContentLibraryView               # Mod、光影、资源包、整合包页面
├── ContentDownloadController        # 内容搜索、下载、安装
├── ServerPageView                   # 服务器页面
├── SettingsPageView                 # 设置页面
└── LoaderPageView                   # Loader 页面
```

### 3.2 Modrinth GUI

```text
com.ecl.modrinth.ui
├── ModBrowserView                   # Tab 组装和生命周期
├── ModSearchPane                   # 搜索控件和搜索结果
├── ModDetailsPane                  # 项目详情、版本、依赖
├── InstalledModsPane               # 已安装 Mod
├── ModUpdatesPane                  # Mod 更新
└── ModBrowserOperationController   # 安装、卸载、启用、禁用

com.ecl.modrinth.ui.viewmodel
├── ModBrowserViewModel              # 兼容 Facade
├── ModSearchViewModel
├── ModDetailsViewModel
├── InstalledModsViewModel
├── ModOperationViewModel
├── ModUpdateViewModel
└── ModBrowserOperationState
```

### 3.3 Core

```text
com.ecl.auth
├── MicrosoftAuth                   # 兼容 Facade
├── MicrosoftDeviceAuthClient
├── XboxLiveAuthClient
├── MinecraftServicesClient
└── MicrosoftSessionState

com.ecl.launcher
├── VersionManager                  # 兼容 Facade
├── VersionCatalogService
├── LocalVersionProfileScanner
├── VersionProfileResolver
├── VersionDownloadTargetResolver
├── ModLoaderInstaller               # 兼容 Facade
├── LoaderMetadataClient
├── ProfileLoaderInstaller
├── InstallerJarRunner
├── LoaderProfileWriter
└── LoaderArtifactVerifier

com.ecl.modrinth.api
├── DefaultModrinthApiClient         # 兼容 Facade
├── ModrinthRequestBuilder
├── ModrinthRetryPolicy
├── ModrinthResponseMapper
└── ModrinthModelMapper

com.ecl.modrinth.pack
├── MrpackInstaller                  # 兼容 Facade
├── MrpackIndexReader
├── MrpackFileInstaller
├── MrpackOverrideExtractor
├── MrpackProfileWriter
└── MrpackPathPolicy
```

## 4. 必须优先拆分的文件

### 4.1 `ecl-gui/.../LauncherUI.java`

当前文件同时包含窗口生命周期、导航、首页、版本、Modrinth、服务器、设置、Loader、认证、皮肤、下载、日志、备份、诊断和路径工具。

迁移范围：

| 当前范围 | 目标类 |
|---|---|
| `start`、`stop`、Stage 和窗口初始化 | `LauncherWindowController` |
| 根布局、导航按钮、页面切换和动画 | `LauncherNavigationController` |
| 首页、启动卡片、运行摘要 | `HomePageView` |
| 版本下拉框、版本选择、版本页面 | `VersionPageView` |
| Modrinth、光影、资源包、整合包页面 | `ContentLibraryView` |
| 内容搜索、版本选择、下载、安装、整合包导入 | `ContentDownloadController` |
| 服务器浏览和服务端 JAR 下载 | `ServerPageView` |
| 设置控件和设置页面 | `SettingsPageView` |
| Loader 选择和安装页面 | `LoaderPageView` |
| 进度条、状态文字、进度动画 | `LauncherProgressController` |
| 游戏目录、实例目录、mods 目录解析 | `LauncherPathService` |
| 文件夹、URL、字节格式化、异常消息 | `LauncherUiSupport` |

最终 `LauncherUI` 只保留：

- JavaFX `Application` 生命周期；
- `MainController` 和各业务服务的组装；
- 页面注册和顶层路由；
- 全局停止流程。

目标：从约 4,200 行降至约 300～500 行。

### 4.2 `ecl-gui/.../ModBrowserView.java`

拆分为：

- `ModSearchPane`：搜索框、分类、排序、搜索结果列表；
- `ModDetailsPane`：项目描述、版本列表、依赖列表；
- 复用现有 `InstalledModsPane`：已安装 Mod；
- 复用现有 `ModUpdatesPane`：更新列表和批量更新；
- `ModBrowserOperationController`：安装、卸载、启用、禁用、本地 JAR 导入；
- `ModBrowserView`：Tab 组装、生命周期和公共刷新入口。

### 4.3 `ecl-gui/.../ModBrowserViewModel.java`

拆分为：

- `ModSearchViewModel`：搜索、分页、排序和搜索结果；
- `ModDetailsViewModel`：项目详情、版本加载和依赖解析；
- `InstalledModsViewModel`：本地扫描和已安装 Mod 状态；
- `ModOperationViewModel`：安装、启用、禁用、卸载和取消；
- `ModUpdateViewModel`：更新检查、更新选择和更新应用；
- `ModBrowserOperationState`：loading、progress、error、currentOperation 和 cancel 状态；
- 原 `ModBrowserViewModel` 保留为兼容 Facade。

## 5. Core 第二批拆分

### 5.1 `MrpackInstaller.java`

当前混合 MRPACK 索引读取、安装、更新、下载、哈希校验、overrides 解压、Loader Profile、路径安全和删除操作。

拆分为：

- `MrpackIndexReader`：读取和校验 `modrinth.index.json`；
- `MrpackFileInstaller`：下载并安装客户端文件；
- `MrpackOverrideExtractor`：安全解压 overrides；
- `MrpackProfileWriter`：写入 Profile 和 Loader 信息；
- `MrpackPathPolicy`：路径越界、Windows 保留名和安全解析；
- `MrpackInstallService`：安装、更新和事务流程协调；
- `MrpackInstaller`：保留原公共入口；
- 复用现有 `PackUpdateTransaction` 和 `PackManifest`。

### 5.2 `VersionManager.java`

拆分为：

- `VersionCatalogService`：官方版本清单、分类和远程缓存；
- `LocalVersionProfileScanner`：本地 Loader Profile 扫描和显示名称；
- `VersionProfileResolver`：`inheritsFrom`、基础 Minecraft 版本和 Client JAR；
- `VersionDownloadTargetResolver`：Profile、下载版本 ID 和下载 URL 的解析；
- `VersionManager`：兼容 Facade。

优先复用现有 `VersionRepository`，避免重复实现版本继承解析。

### 5.3 `ModLoaderInstaller.java`

拆分为：

- `LoaderMetadataClient`：Maven/XML 元数据和版本列表；
- `ProfileLoaderInstaller`：Fabric、Quilt Profile 安装；
- `InstallerJarRunner`：Forge、NeoForge Installer JAR 执行；
- `LoaderProfileWriter`：版本 JSON、Profile 目录和基础版本合并；
- `LoaderArtifactVerifier`：下载文件哈希校验；
- `ModLoaderInstaller`：根据 Loader 类型选择实现。

### 5.4 `MicrosoftAuth.java`

拆分为：

- `MicrosoftDeviceAuthClient`：Device Code、OAuth Token、轮询和取消；
- `XboxLiveAuthClient`：Xbox Live 和 XSTS Token；
- `MinecraftServicesClient`：Minecraft Token、Entitlements 和 Profile；
- `MicrosoftSessionState`：Access Token、Refresh Token、用户名、UUID、过期时间；
- `MicrosoftAuth`：保留整体登录、退出和回调流程。

## 6. Core 第三批拆分

### 6.1 Modrinth 和 CurseForge

| 当前文件 | 拆分目标 |
|---|---|
| `DefaultModrinthApiClient.java` | `ModrinthRequestBuilder`、`ModrinthRetryPolicy`、`ModrinthResponseMapper`、`ModrinthModelMapper` |
| `ModrinthDownloader.java` | `ModrinthContentSearchService`、`ModrinthVersionSelectionService`、`ModrinthDependencyDownloadService`，复用现有 `ModFileDownloadService` |
| `CurseForgeApiClient.java` | `CurseForgeRequestClient`、`CurseForgeResponseMapper`、`CurseForgeProjectService`、`CurseForgeFileService` |
| `CurseForgeDownloader.java` | `CurseForgeContentSearchService`、`CurseForgeFileDownloadService`、`CurseForgeModpackConverter` |
| `DefaultModDependencyResolver.java` | `DependencyGraphBuilder`、`ModVersionCompatibilityPolicy`、`DependencyConflictDetector` |

原有公共 Client/Downloader 类保留为 Facade，避免 GUI 和 CLI 同时大范围改动。

### 6.2 游戏下载

`GameDownloader.java` 应收缩为下载流程协调器，继续复用现有：

- `DownloadAssetsTask`；
- `DownloadClientJarTask`；
- `DownloadLibrariesTask`；
- `FetchVersionMetadataTask`；
- `InstallHelpers`。

新增或提取：

- `GameDownloadCoordinator`；
- `DownloadPlanBuilder`；
- `NativeLibraryResolver`；
- `AssetVerificationService`。

### 6.3 本地 Mod 扫描

`DefaultLocalModScanner.java` 拆分为：

- `ModJarScanner`：遍历实例目录和 JAR；
- `ModMetadataParser`：统一元数据入口；
- `FabricModMetadataParser`；
- `ForgeModMetadataParser`；
- `QuiltModMetadataParser`；
- `ModScanCacheStore`：缓存读写；
- `LocalModScanAggregator`：重复项、警告和最终结果聚合。

### 6.4 设置、备份和离线皮肤

| 当前文件 | 拆分目标 |
|---|---|
| `SettingsManager.java` | `SettingsStore`、`EncryptedSettingsStore`、`SettingsMigration`、`SettingsAutoSaveScheduler` |
| `WorldBackupService.java` | `BackupArchiveWriter`、`BackupArchiveReader`、`BackupValidator`、`BackupRestoreService`、`BackupRetentionService` |
| `OfflineSkinServer.java` | `OfflineSkinHttpHandler`、`OfflineSkinCharacterRegistry`、`OfflineSkinTextureStore`、`OfflineSkinTextureSigner` |

## 7. 评估后暂不拆分的文件

以下文件虽然代码量较大，但当前职责仍集中，暂不做机械拆分：

- `DownloadTaskCenter`：任务队列、取消、重试、限流和观察者属于同一任务中心；
- `VersionRepository`：版本 JSON、继承合并和类型转换是一个完整 Repository；
- `PackUpdateTransaction`：事务计划、提交和回滚共同保证原子性；
- `FileModInstallationTransaction`：Mod 文件事务边界应保持集中；
- `DefaultModpackUpdateService`：整合包更新检查和更新计划；
- `DefaultGameRepository`：游戏实例和目录解析；
- `DiagnosticBundleService`：诊断包生成；
- `JavaRuntimeDownloader`：Java 运行时下载；
- `ServerDirectoryService`、`ServerStatusService`、`ServerCatalog`：服务器领域职责已分开；
- `LauncherLogBuffer`、`LauncherThemeManager`、`LauncherUiFactory`：通用 GUI 支持类；
- 所有 DTO、Model、Enum、Exception、Interface 和单一职责的小型工具类。

如果后续这些文件继续增长，再分别提取内部的 `Scheduler`、`Validator` 或 `Journal`，不以行数作为唯一拆分依据。

## 8. CLI、HTTP 和入口类

以下文件保持 Facade 设计，不再继续拆分：

- `ecl-cli/.../EclCli.java`；
- `ecl-cli/.../*Command.java`；
- `ecl-core/.../HttpUtil.java`；
- `ecl-core/.../ModrinthApiClient.java`；
- `ecl-core/.../ContentDownloader.java`；
- `ecl-core/.../DownloadService.java`。

`HttpUtil` 已经是兼容门面，底层请求、JSON、下载、镜像和限速逻辑已经由其他类承担。

## 9. 实施顺序

### 阶段 0：建立基线

执行：

```powershell
.\gradlew.bat check
.\gradlew.bat build
.\gradlew.bat captureLauncherUi
```

保存测试结果、GUI 截图和公共构造器/方法清单。

### 阶段 1：Core 低风险拆分

顺序：

1. `SettingsManager`；
2. `MicrosoftAuth`；
3. `VersionManager`；
4. `ModLoaderInstaller`；
5. `DefaultModrinthApiClient`。

### 阶段 2：内容和整合包拆分

顺序：

1. `ModrinthDownloader`；
2. `CurseForgeDownloader`；
3. `DefaultLocalModScanner`；
4. `DefaultModDependencyResolver`；
5. `MrpackInstaller`；
6. `GameDownloader`。

### 阶段 3：Modrinth GUI 拆分

顺序：

1. `ModBrowserViewModel`；
2. `ModBrowserView`；
3. 接入现有 `InstalledModsPane`；
4. 接入现有 `ModUpdatesPane`；
5. 删除重复的旧 UI 和状态代码。

### 阶段 4：Launcher GUI 拆分

顺序：

1. `LauncherPathService`；
2. `LauncherProgressController`；
3. `LauncherNavigationController`；
4. `HomePageView`；
5. `VersionPageView`；
6. `ContentLibraryView`；
7. `ServerPageView`；
8. `SettingsPageView`；
9. 最后收缩 `LauncherUI`。

### 阶段 5：清理和文档

- 删除重复方法、无用字段和无用 import；
- 将新增实现类改为包级可见；
- 保留旧 Facade 的公共方法；
- 更新 README 和本计划中的实际类名；
- 检查源码、测试、构建脚本和发布任务中的旧引用。

## 10. 测试和验收

### Core

```powershell
.\gradlew.bat :ecl-core:test
```

### GUI

```powershell
.\gradlew.bat :ecl-gui:test
.\gradlew.bat captureLauncherUi
```

### 完整构建

```powershell
.\gradlew.bat check
.\gradlew.bat build
.\gradlew.bat installDist
.\gradlew.bat packageWindowsApp
```

### 必须回归的功能

- Minecraft 版本下载、安装和启动；
- Offline、Microsoft、Yggdrasil 三种登录方式；
- Fabric、Quilt、Forge、NeoForge 安装；
- Mod 搜索、项目详情、依赖、安装、启用、禁用、卸载和更新；
- 本地 JAR 拖拽导入；
- MRPACK 导入、更新、路径越界拦截和失败回滚；
- CurseForge 内容下载和整合包转换；
- 服务器搜索、在线状态、复制地址和直连；
- 设置迁移、加密账号、自动保存、主题和语言切换；
- 游戏日志、崩溃诊断、世界备份和恢复；
- 下载取消、重试、限流、进度显示和应用关闭。

## 11. 完成标准

- `LauncherUI.java` 约 300～500 行，仅负责生命周期和组装；
- `ModBrowserView.java` 约 200～300 行，仅负责界面组合；
- `ModBrowserViewModel.java` 约 200～300 行，主要作为兼容 Facade；
- Core 中的认证、版本、Loader、MRPACK、Modrinth 和设置逻辑均有独立实现类；
- 每个新实现类有对应单元测试或被现有集成测试覆盖；
- `check`、`build`、GUI 截图和 Windows 打包全部通过；
- 用户可见行为、配置格式、公共入口和数据目录保持兼容。

## 12. 当前实施记录

### 已完成：SettingsManager 迁移职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/config/SettingsMigration.java`；
- 修改 `ecl-core/src/main/java/com/ecl/config/SettingsManager.java`；
- 保留 `SettingsManager.migrateToEncrypted(String)` 公共方法；
- 保留 `SettingsManager.migrateSettingKey(String, String)` 包级调用入口；
- 将旧配置键迁移和明文配置迁移到加密配置的实现移入 `SettingsMigration`；
- 未修改配置键、settings.json 格式和加密数据格式。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.config.SettingsManagerTest --no-daemon
BUILD SUCCESSFUL
```

本项完成后继续进入阶段 1 的 `MicrosoftAuth` 会话状态提取。

### 已完成：MicrosoftAuth 会话状态提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/auth/MicrosoftSessionState.java`；
- 修改 `ecl-core/src/main/java/com/ecl/auth/MicrosoftAuth.java`；
- 将用户名、UUID、Access Token、Refresh Token、过期时间和登录状态集中到 `MicrosoftSessionState`；
- 保留 `MicrosoftAuth` 原有登录、退出、缓存会话和公共查询方法；
- 未修改 OAuth、Xbox Live、XSTS、Minecraft Services 的网络流程。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.auth.MicrosoftAuthTest --no-daemon
BUILD SUCCESSFUL
```

本项完成后继续进入阶段 1 的 `VersionManager` 职责提取。

### 已完成：VersionManager 本地 Profile 扫描提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/launcher/LocalVersionProfileScanner.java`；
- 修改 `ecl-core/src/main/java/com/ecl/launcher/VersionManager.java`；
- 将本地版本目录扫描、Loader 检测和 Loader 显示名称提取到 `LocalVersionProfileScanner`；
- 保留 `VersionManager.LocalVersionProfile`、版本列表合并和显示名称公共行为；
- 未修改远程版本清单、版本继承解析和下载目标解析。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.launcher.VersionManagerTest --no-daemon
BUILD SUCCESSFUL
```

本项完成后继续进入 `VersionManager` 远程刷新和缓存生命周期提取。

### 已完成：VersionManager 远程清单解析提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/launcher/VersionCatalogService.java`；
- 修改 `ecl-core/src/main/java/com/ecl/launcher/VersionManager.java`；
- 将版本分类、April Fools 判断和版本索引构建移入 `VersionCatalogService`；
- 保留 `VersionManager` 原有版本分类查询和远程清单行为；
- 暂未移动 HTTP 刷新、磁盘缓存和清单生命周期。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.launcher.VersionManagerTest --no-daemon
BUILD SUCCESSFUL
```

本次首次验证发现并修复了一个被移除的 `HashMap` import，修复后测试通过。下一步继续提取 `VersionManager` 的远程刷新和缓存职责。

### 已完成：VersionManager 清单刷新和缓存提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/launcher/VersionManifestStore.java`；
- 修改 `ecl-core/src/main/java/com/ecl/launcher/VersionManager.java`；
- 将官方版本清单的网络刷新、磁盘缓存读取和网络失败回退移入 `VersionManifestStore`；
- 保留 `VersionManager.refresh()`、缓存文件位置和远程失败时的回退行为；
- 未修改本地 Profile、版本分类和版本继承解析。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.launcher.VersionManagerTest --no-daemon
BUILD SUCCESSFUL
```

本项完成后继续进入 `VersionManager` 的下载目标解析职责。

### 已完成：VersionManager 版本继承解析提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/launcher/VersionProfileResolver.java`；
- 修改 `ecl-core/src/main/java/com/ecl/launcher/VersionManager.java`；
- 将 Minecraft 基础版本、Client JAR 和继承链循环检测移入 `VersionProfileResolver`；
- 保留 `VersionManager.resolveMinecraftVersionId(String)` 和 `isVersionDownloaded(String)` 的原有行为；
- 暂未移动下载目标 ID 的解析。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.launcher.VersionManagerTest --no-daemon
BUILD SUCCESSFUL
```

本次首次验证发现并修复了一个对已提取辅助方法的残留调用，修复后测试通过。该项完成后继续提取 `VersionManager` 的下载目标解析职责。

### 已完成：VersionManager 下载目标解析提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/launcher/VersionDownloadTargetResolver.java`；
- 修改 `ecl-core/src/main/java/com/ecl/launcher/VersionManager.java`；
- 将 `jar`、`inheritsFrom`、基础 Minecraft 版本和客户端下载信息的递归选择移入 `VersionDownloadTargetResolver`；
- 保留 `VersionManager.resolveDownloadTarget(String)` 的公共入口、URL 组装和错误行为；
- `VersionManager` 现在主要负责参数校验、清单查询和结果组装。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.launcher.VersionManagerTest --no-daemon
BUILD SUCCESSFUL
```

`VersionManager` 的本地 Profile、远程清单、缓存、继承解析和下载目标解析已经完成第一轮职责拆分。下一步进入阶段 1 的 `ModLoaderInstaller`。

### 已完成：ModLoaderInstaller 元数据查询提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/launcher/LoaderMetadataClient.java`；
- 修改 `ecl-core/src/main/java/com/ecl/launcher/ModLoaderInstaller.java`；
- 将 Fabric、Quilt、Forge、NeoForge 的版本元数据查询移入 `LoaderMetadataClient`；
- 将 Maven/XML 解析、版本排序、NeoForge 前缀计算和 Fabric/Quilt Profile URL 组装移入元数据客户端；
- 保留 `ModLoaderInstaller.listVersions(String, Loader)` 和版本排序兼容入口；
- 未移动 Loader 安装、Installer JAR 执行、Profile 写入和文件合并逻辑。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.launcher.ModLoaderInstallerTest --no-daemon
BUILD SUCCESSFUL
```

本次首次验证发现并修复了一个 Fabric/Quilt URL 常量的残留引用，修复后测试通过。该项完成后继续提取 `ModLoaderInstaller` 的安装器进程执行职责。

### 已完成：ModLoaderInstaller 和 SettingsManager 五个小切片

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/launcher/InstallerProcessRunner.java`；
- 新增 `ecl-core/src/main/java/com/ecl/launcher/LoaderArtifactVerifier.java`；
- 新增 `ecl-core/src/main/java/com/ecl/launcher/LoaderProfileWriter.java`；
- 新增 `ecl-core/src/main/java/com/ecl/config/EncryptedSettingsStore.java`；
- 新增 `ecl-core/src/main/java/com/ecl/config/SettingsAutoSaveScheduler.java`；
- 修改 `ecl-core/src/main/java/com/ecl/launcher/ModLoaderInstaller.java`；
- 修改 `ecl-core/src/main/java/com/ecl/config/SettingsManager.java`。

完成内容：

- 将 Forge/NeoForge Installer JAR 的进程启动、输出收集、超时和中断处理移入 `InstallerProcessRunner`；
- 将安装器 SHA 校验移入 `LoaderArtifactVerifier`；
- 将 Loader Profile 写入和元数据标注移入 `LoaderProfileWriter`；
- 将 AES-GCM 设置值的读写移入 `EncryptedSettingsStore`；
- 将设置自动保存的防抖调度、dirty 状态和线程池生命周期移入 `SettingsAutoSaveScheduler`；
- 保留原有公共 API、加密格式、自动保存行为和 Loader 安装流程。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.launcher.ModLoaderInstallerTest --tests com.ecl.config.SettingsManagerTest --no-daemon
BUILD SUCCESSFUL
```

下一步进入 `MicrosoftAuth` 的认证协议客户端提取，或继续完成 `ModLoaderInstaller` 的文件合并职责。

### 已完成：MicrosoftAuth Minecraft Services 客户端提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/auth/MinecraftServicesClient.java`；
- 修改 `ecl-core/src/main/java/com/ecl/auth/MicrosoftAuth.java`；
- 将 Minecraft Services 登录、Java 版授权检查和玩家档案读取移入 `MinecraftServicesClient`；
- 保留 Microsoft 登录代次、缓存 Token、取消和公共认证 API；
- 未修改 Microsoft OAuth、Xbox Live 和 XSTS 流程。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.auth.MicrosoftAuthTest --no-daemon
BUILD SUCCESSFUL
```

本项完成后继续进入 `MicrosoftAuth` 的 Xbox Live/XSTS 客户端职责提取。

### 已完成：MicrosoftAuth Xbox Live/XSTS 客户端提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/auth/XboxLiveAuthClient.java`；
- 修改 `ecl-core/src/main/java/com/ecl/auth/MicrosoftAuth.java`；
- 将 Xbox Live Token、XSTS Token、用户哈希和 XSTS 错误码处理移入 `XboxLiveAuthClient`；
- 保留 Microsoft 登录流程、代次检查、Token 缓存和公共认证 API；
- 未修改 Device Code、Refresh Token 和 Microsoft OAuth 流程。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.auth.MicrosoftAuthTest --no-daemon
BUILD SUCCESSFUL
```

本项完成后继续进入 `MicrosoftAuth` 的 OAuth/Device Code 客户端职责提取。

### 已完成：MicrosoftAuth OAuth/Device Code 客户端提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/auth/MicrosoftOAuthClient.java`；
- 修改 `ecl-core/src/main/java/com/ecl/auth/MicrosoftAuth.java`；
- 将 Refresh Token、Device Code、轮询、slow_down 间隔和 OAuth Token 解析移入 `MicrosoftOAuthClient`；
- 保留 `MicrosoftAuth` 的登录代次检查、状态通知、Device Code 回调和会话提交；
- 保留 `MicrosoftAuth.nextDevicePollInterval(int)` 的兼容入口。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.auth.MicrosoftAuthTest --no-daemon
BUILD SUCCESSFUL
```

`MicrosoftAuth` 的会话状态、Minecraft Services、Xbox Live/XSTS 和 OAuth/Device Code 四类职责已经完成第一轮拆分。下一步进入 `MrpackInstaller`。

### 已完成：MrpackInstaller 路径安全策略提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackPathPolicy.java`；
- 修改 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackInstaller.java`；
- 将 MRPACK Profile ID 生成、Windows 保留名处理、实例目录校验、相对路径校验和递归清理移入 `MrpackPathPolicy`；
- 保留安装、更新、回滚流程和路径越界错误行为；
- 未移动索引读取、文件下载、overrides 解压和事务提交逻辑。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.modrinth.pack.MrpackInstallerTest --no-daemon
BUILD SUCCESSFUL
```

本项完成后继续进入 `MrpackInstaller` 的索引读取和安全校验职责提取。

### 已完成：MrpackInstaller 索引读取提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackIndexReader.java`；
- 修改 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackInstaller.java`；
- 将 `modrinth.index.json` 的读取、大小限制和 JSON 格式校验移入 `MrpackIndexReader`；
- 统一安装、内容导入和更新流程的索引读取实现，同时保留原有错误信息；
- 未移动整合包格式版本、依赖和 Loader 业务校验。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.modrinth.pack.MrpackInstallerTest --no-daemon
BUILD SUCCESSFUL
```

本项完成后继续进入 MRPACK 的索引依赖和 Loader 选择校验职责提取。

### 已完成：MrpackInstaller 依赖和 Loader 校验提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackDependencyResolver.java`；
- 修改 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackInstaller.java`；
- 将 `dependencies` 对象校验、Minecraft 版本要求和多 Loader 冲突检查移入 `MrpackDependencyResolver`；
- 保留安装、更新和内容导入流程的原有错误行为；
- 保留 `LoaderDependency` 的包内使用方式和 Loader 安装协调逻辑。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.modrinth.pack.MrpackInstallerTest --no-daemon
BUILD SUCCESSFUL
```

本次首次验证发现并修复了迁移后 record 字段访问器引用问题，修复后测试通过。下一步继续提取 MRPACK 的索引文件下载和哈希校验职责。

### 已完成：MrpackInstaller 索引文件下载和哈希校验提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackFileInstaller.java`；
- 新增 `ecl-core/src/test/java/com/ecl/modrinth/pack/MrpackFileInstallerTest.java`；
- 修改 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackInstaller.java`；
- 将索引文件下载、SHA-512/SHA-1 哈希校验、客户端 env 过滤和下载大小限制移入 `MrpackFileInstaller`；
- 保留安装、更新和内容导入流程的原有下载、校验和错误行为；
- `MrpackInstaller` 现在只保留 overrides 解压、Profile 写入和事务协调职责。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.modrinth.pack.MrpackInstallerTest --tests com.ecl.modrinth.pack.MrpackFileInstallerTest --no-daemon
BUILD SUCCESSFUL
MrpackFileInstallerTest: 7 tests, 0 failures
MrpackInstallerTest: 6 tests, 0 failures
```

新增 `MrpackFileInstallerTest` 使用 JDK `HttpServer` 覆盖真实下载：sha512/sha1 校验通过、env=unsupported 跳过、哈希不匹配失败、大小不匹配失败、无下载地址失败、全部下载地址失败。下一步继续提取 MRPACK 的 overrides 安全解压职责（`MrpackOverrideExtractor`）。

### 已完成：Mrpack 两个行为回归修复

日期：2026-08-22

问题与修复：

- `installContents` 缺少 dependencies 时的错误文案在拆分时被改为英文 `MRPACK index is missing dependencies`，已改回拆分前的中文 `整合包索引缺少 dependencies`；
- `MrpackDependencyResolver.findLoader` 的错误优先级被拆分反转为先校验值再查冲突，已改回拆分前的顺序：先抛 `整合包同时声明了多个模组加载器`，再抛 `整合包声明了无效的加载器版本`。

验证结果：

```text
.\gradlew.bat :ecl-core:test --tests com.ecl.modrinth.pack.MrpackInstallerTest --tests com.ecl.modrinth.pack.MrpackFileInstallerTest --no-daemon
BUILD SUCCESSFUL
MrpackInstallerTest: 8 tests, 0 failures（新增 2 个行为回归测试）
MrpackFileInstallerTest: 7 tests, 0 failures
```

新增回归测试锁定：`installContentsKeepsChineseMessageWhenDependenciesMissing`、`findLoaderPrefersMultiLoaderConflictOverInvalidValue`。

### 已完成：Mrpack overrides 和 Profile 写入职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackOverrideExtractor.java`；
- 新增 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackProfileWriter.java`；
- 新增 `ecl-core/src/test/java/com/ecl/modrinth/pack/MrpackOverrideExtractorTest.java`；
- 修改 `ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackInstaller.java`；
- 将 overrides/client-overrides 安全解压、条目数、单文件大小、总大小和压缩比限制移入 `MrpackOverrideExtractor`；
- 将 MRPACK 初次安装和更新时的 Profile JSON 写入移入 `MrpackProfileWriter`；
- 保留原有路径安全、事务提交、Profile 字段和用户可见行为。

验证结果：

```text
./gradlew.bat :ecl-core:test --tests com.ecl.modrinth.pack.MrpackOverrideExtractorTest --tests com.ecl.modrinth.pack.MrpackInstallerTest --no-daemon
BUILD SUCCESSFUL
```

### 已完成：Launcher GUI 第一批职责拆分

日期：2026-08-22

已完成文件：

- 新增 `ecl-gui/src/main/java/com/ecl/ui/LauncherPageFactory.java`；
- 新增 `ecl-gui/src/main/java/com/ecl/ui/HomePageFactory.java`；
- 新增 `ecl-gui/src/main/java/com/ecl/ui/ContentLibraryPageFactory.java`；
- 新增 `ecl-gui/src/main/java/com/ecl/ui/ServerJarDownloadPage.java`；
- 新增 `ecl-gui/src/main/java/com/ecl/ui/LauncherPathService.java`；
- 新增 `ecl-gui/src/main/java/com/ecl/ui/LauncherProgressController.java`；
- 新增 `ecl-gui/src/main/java/com/ecl/ui/LaunchAuthFactory.java`；
- 新增 `ecl-gui/src/main/java/com/ecl/ui/GameProcessMonitor.java`；
- 修改 `ecl-gui/src/main/java/com/ecl/ui/LauncherUI.java` 和 `GameLaunchCoordinator.java`。

完成内容：

- 将首页、版本页、下载页、设置页、日志页和服务器 JAR 页面移出 `LauncherUI`；
- 将内容库导航和整合包更新页面移出 `LauncherUI`；
- 将游戏目录解析、进度动画、认证创建和游戏进程监控移出对应大类；
- `LauncherUI.java` 从约 4,200 行降至约 3,100 行；
- `GameLaunchCoordinator.java` 从约 526 行降至约 419 行；
- 保留原有页面路由、控件行为、认证配置、下载取消和进程监控行为。

验证结果：

```text
./gradlew.bat :ecl-gui:test :ecl-core:test :ecl-gui:checkstyleMain :ecl-core:checkstyleMain --no-daemon
BUILD SUCCESSFUL
```

### 已完成：Modrinth API 响应映射职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/modrinth/api/ModrinthResponseMapper.java`；
- 修改 `ecl-core/src/main/java/com/ecl/modrinth/api/DefaultModrinthApiClient.java`；
- 将 Project、Version、File、Dependency DTO 到领域模型的转换、License 解析、URI/时间解析和 hash 映射移入 `ModrinthResponseMapper`；
- 保留 API Client 的请求、缓存、重试和公共接口行为。

验证结果：

```text
./gradlew.bat :ecl-core:checkstyleMain :ecl-core:test --tests com.ecl.modrinth.api.DefaultModrinthApiClientTest --no-daemon
BUILD SUCCESSFUL
```

### 已完成：GameDownloader 下载批处理职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/download/GameDownloadBatchExecutor.java`；
- 修改 `ecl-core/src/main/java/com/ecl/download/GameDownloader.java`；
- 将依赖库/资源文件的并发下载、完成聚合、错误转换、取消处理、哈希校验和下载源回调移入批处理执行器；
- 保留 `GameDownloader` 的版本流程、监听器、取消和公共 API 行为。

验证结果：

```text
./gradlew.bat :ecl-core:compileJava :ecl-core:checkstyleMain :ecl-core:test --tests com.ecl.download.GameDownloaderTest --no-daemon
BUILD SUCCESSFUL
```

### 已完成：本地 Mod JAR 元数据读取职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-core/src/main/java/com/ecl/modrinth/service/LocalModMetadataReader.java`；
- 修改 `ecl-core/src/main/java/com/ecl/modrinth/service/DefaultLocalModScanner.java`；
- 将 Fabric、Quilt、Forge、NeoForge 和旧版 Forge 元数据解析、大小限制和损坏 JAR 检测移入元数据读取器；
- 保留本地扫描、缓存、在线识别、索引写回和错误行为。

验证结果：

```text
./gradlew.bat :ecl-core:checkstyleMain :ecl-core:test --tests com.ecl.modrinth.service.LocalModScannerTest --no-daemon
BUILD SUCCESSFUL
```

### 已完成：ModBrowser 详情面板职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-gui/src/main/java/com/ecl/modrinth/ui/ModDetailsPane.java`；
- 修改 `ecl-gui/src/main/java/com/ecl/modrinth/ui/ModBrowserView.java`；
- 将详情控件、版本选择、项目链接、中文简介、更新日志、依赖展示和异步详情刷新移入 `ModDetailsPane`；
- 保留模组搜索列表、已安装列表、更新操作、安装工作流和页面路由行为。

代码规模：

```text
ModBrowserView.java
723 行 → 461 行
```

验证结果：

```text
./gradlew.bat :ecl-gui:checkstyleMain :ecl-gui:test --no-daemon
BUILD SUCCESSFUL
```

### 已完成：ModBrowser 依赖元数据职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-gui/src/main/java/com/ecl/modrinth/ui/viewmodel/ModDependencyBrowserLoader.java`；
- 修改 `ecl-gui/src/main/java/com/ecl/modrinth/ui/viewmodel/ModBrowserViewModel.java`；
- 将依赖项目加载、项目请求去重、依赖分组和数据源切换缓存清理移入独立加载器；
- 保留搜索、安装、更新、错误处理和 ViewModel 公共 API 行为。

代码规模：

```text
ModBrowserViewModel.java
655 行 → 607 行
```

验证结果：

```text
./gradlew.bat :ecl-gui:checkstyleMain :ecl-gui:test --no-daemon
BUILD SUCCESSFUL
```

### 已完成：ModBrowser 更新协调职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-gui/src/main/java/com/ecl/modrinth/ui/viewmodel/ModBrowserUpdateCoordinator.java`；
- 修改 `ecl-gui/src/main/java/com/ecl/modrinth/ui/viewmodel/ModBrowserViewModel.java`；
- 将更新检查缓存、更新请求、批量更新、下载任务接入、更新结果状态和更新数量同步移入协调器；
- 保留 ViewModel 的原有公共 API、取消行为、状态消息和 JavaFX 线程切换。

代码规模：

```text
ModBrowserViewModel.java
607 行 → 524 行
```

验证结果：

```text
./gradlew.bat :ecl-gui:checkstyleMain :ecl-gui:test :ecl-core:checkstyleMain :ecl-core:test --no-daemon
BUILD SUCCESSFUL
```

### 已完成：服务器列表单元格职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-gui/src/main/java/com/ecl/server/ServerListCell.java`；
- 修改 `ecl-gui/src/main/java/com/ecl/server/ServerBrowserView.java`；
- 将服务器卡片渲染、状态徽章、标签和类别样式移入独立列表单元格；
- `ServerBrowserView` 保留目录刷新、状态探测、筛选和操作逻辑。

验证结果：

```text
./gradlew.bat :ecl-gui:checkstyleMain :ecl-gui:test :ecl-core:checkstyleMain :ecl-core:test --no-daemon
BUILD SUCCESSFUL
```

### 已完成：服务器状态探测职责提取

日期：2026-08-22

已完成文件：

- 新增 `ecl-gui/src/main/java/com/ecl/server/ServerStatusProbeController.java`；
- 修改 `ecl-gui/src/main/java/com/ecl/server/ServerBrowserView.java`；
- 将状态探测线程池、并发限制、探测中地址集合和刷新回调移入独立控制器；
- `ServerBrowserView` 保留服务器目录、筛选和用户操作逻辑。

### 已完成：LauncherUI 死代码清理

日期：2026-08-22

- 删除已由页面工厂替代的旧版内容库、旧版设置页和重复页面构建逻辑；
- 保留启动表单、路由协调和内容下载入口；
- `LauncherUI.java` 当前约 3,030 行，未改变页面路由和公共行为。

统一验证结果：

```text
./gradlew.bat :ecl-gui:checkstyleMain :ecl-gui:test :ecl-core:checkstyleMain :ecl-core:test --no-daemon
BUILD SUCCESSFUL
```

### 已完成：四个剩余大类的功能职责拆分

日期：2026-08-22

已完成文件：

- `DownloadTaskCenter`：新增 `DownloadTaskEntry`、`DownloadTaskExecutor`、`DownloadTaskNotifier`、`DownloadTaskSnapshots`；
- `ModBrowserViewModel`：新增 `ModBrowserOperationState`、`ModBrowserSearchController`、`ModInstallationWorkflow`、`InstalledModController`；
- `ServerBrowserView`：新增 `ServerBrowserActions`、`ServerCatalogFilter`、`ServerDirectoryRefreshController`；
- `LauncherUI`：新增 `LauncherWindowChrome`、`ModDropImportHandler`。

拆分结果：

- 任务条目、执行、通知和快照与下载任务中心调度分离；
- 模组搜索、安装、本地管理和异步状态从 ViewModel 分离；
- 服务器筛选、目录刷新和用户操作从视图分离；
- 启动器窗口拖动/最小化/最大化及模组拖放导入从 UI 协调器分离。

统一验证结果：

```text
./gradlew.bat :ecl-gui:checkstyleMain :ecl-gui:test :ecl-core:checkstyleMain :ecl-core:test --no-daemon
BUILD SUCCESSFUL
```
