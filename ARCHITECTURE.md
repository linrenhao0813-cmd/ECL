# ECL 代码架构指南

> 本文档是 ECL 项目的唯一架构入口。**任何 AI agent 或新开发者，只需通读本文档即可理解项目结构、模块职责、核心数据流与开发约定，从而安全地上手开发。**
> 阅读顺序：项目概览 → 模块总览 → ecl-core（核心）→ GUI / CLI / 打包 → 设计约定 → 数据流 → 常见开发任务。

---

## 1. 项目概览

ECL 是一个基于 **Java 21 + JavaFX 21 + Gradle** 的轻量 Minecraft 启动器（GPL-3.0），同时提供 GUI 与无头 CLI 两套入口。

| 项目事实 | 值 |
|---|---|
| 根目录 | `E:\ECL` |
| 包根 | `com.ecl` |
| 构建 | Gradle（Wrapper 内置，`gradlew` / `gradlew.bat`） |
| JDK | 21（toolchain，JavaCompile 固定 `--release 21`，UTF-8） |
| 版本事实源 | `gradle/libs.versions.toml`（JavaFX 21 / Gson 2.10.1 / Jackson 2.18.2 / picocli 4.7.6 / SLF4J 2.0.16 + Logback 1.5.16 / JNA 5.15.0 / JUnit 5.11.4） |
| 依赖仓库 | maven.aliyun.com 镜像 + Maven Central（`settings.gradle.kts`） |
| 质量检查 | 所有 java 子工程统一应用 checkstyle + jacoco + spotbugs（规则见根 `build.gradle.kts` 与 `config/`） |
| 定位 | 版本管理、加载器安装（Fabric/Quilt/Forge/NeoForge）、游戏启动、Modrinth 内容下载（mod/shader/resourcepack/modpack）、整合包导入导出、三语切换、跨平台（Windows/macOS/Linux） |

功能全览见根目录 `README.md`；本文档聚焦**代码结构与架构**。

---

## 2. 模块总览（Gradle 多模块）

`settings.gradle.kts` 声明 5 个模块，依赖关系如下：

```
ecl-boot ─────→ ecl-core
     ├──→ ecl-gui（ecl-gui 也 ──→ ecl-core）
     └──→ ecl-cli（ecl-cli 也 ──→ ecl-core）
ecl-dist ─────→ ecl-boot、ecl-cli（仅打包任务，无源码）
```

| 模块 | 职责 | 关键配置 |
|---|---|---|
| **ecl-boot** | 双入口分派（GUI/CLI）与 JavaFX 依赖兜底重启 | `application` 插件，`mainClass = com.ecl.ECL`，applicationName `ECL` |
| **ecl-core** | **纯业务逻辑，不依赖 JavaFX**：认证、下载、版本、启动、任务、基础设施 | `java-library`；gson / jackson / jna-platform / slf4j / logback |
| **ecl-gui** | JavaFX 界面、CSS、图标、UI 快照测试 | 按运行平台自动选 JavaFX classifier（win/mac/mac-aarch64/linux，可 `-PjavafxPlatform=` 覆盖）；`api(project(":ecl-core"))` |
| **ecl-cli** | picocli 无头命令行 | `java-library` + `application`，`mainClass = com.ecl.cli.EclCli` |
| **ecl-dist** | jpackage 三平台装配 | `base` 插件；`packageWindowsApp/packageMacApp/packageLinuxApp` 三个 Exec 任务 |

**核心原则：`ecl-core` 是唯一业务逻辑层，绝不 import JavaFX。** 新增业务代码一律放 ecl-core，界面代码放 ecl-gui，命令代码放 ecl-cli。

### 常用构建命令（根目录执行）

```bash
./gradlew.bat run                 # 启动 GUI
./gradlew.bat :ecl-cli:run --args="doctor --json"   # CLI（无头）
./gradlew.bat build               # 构建
./gradlew.bat installDist         # 生成安装目录（ecl-boot/build/install/ECL/）
./gradlew.bat packageWindowsApp   # jpackage 打包 ECL.exe / ECL-CLI.exe → dist/windows/ECL/
./gradlew.bat captureLauncherUi   # 渲染 GUI 截图做视觉 QA
```

---

## 3. ecl-core 包结构（核心业务层）

源码根：`ecl-core/src/main/java/com/ecl/`

```
com.ecl
├── ECLConfig.java            # 全局常量：数据目录、内存计算、下载线程数、默认 Yggdrasil 服务器等
├── auth/                     # 认证/账户体系（离线 / Microsoft / Yggdrasil）
├── backup/                   # 世界存档备份
├── config/                   # 设置持久化（SettingsManager + SettingKey）
├── diagnostic/               # 脱敏诊断包导出
├── download/                 # 版本/库/资源下载 + 下载源解析 + Modrinth 旧下载器
│   ├── DownloadService.java / GameDownloader.java / ModrinthDownloader.java
│   ├── install/              # Task 化的版本安装（FetchVersionMetadataTask 等四阶段）
│   └── provider/             # 下载源（官方 / 镜像 BMCLAPI、fastmcmirror）
├── event/                    # 事件总线
├── exception/                # 异常体系
├── game/                     # 版本元数据模型（VersionMetadata/Library/AssetIndex…）与仓库
├── i18n/                     # 国际化（简体中文 / 繁体中文 / English）
├── launch/                   # 新启动管线（CLI 使用）
├── launcher/                 # 旧启动服务（GameLauncher/VersionManager/ModLoaderInstaller）
├── modrinth/                 # Modrinth 平台完整集成（新版，GUI ModBrowser 使用）
├── pack/                     # 整合包导入导出（ECL / MultiMC / CurseForge / MRPACK 四种格式）
├── runtime/                  # Java 运行时管理（Temurin JRE 探测与自动下载）
├── task/                     # Task 任务依赖图体系
└── util/                     # 基础设施工具（Http/Crypto/File/Zip/Json/Messages…）
```

---

## 4. 认证与账户体系 `com.ecl.auth`

- `AuthType`：`OFFLINE / MICROSOFT / YGGDRASIL` 枚举。
- `AuthProvider`（接口）：`getUsername/getUUID/getAccessToken/login/logout/isLoggedIn`；实现 `OfflineAuth` / `MicrosoftAuth` / `YggdrasilAuth`。
- `AuthProviderRegistry`：按 `AuthType` 建 `EnumMap`，内置三种协议，并支持 `ServiceLoader.load(AuthProviderFactory.class)` 扩展外部认证协议。
- `DefaultAccountService`（实现 `AccountService`）：**统一账户仓库 `accounts.json`**（位于数据目录），离线/Microsoft/Yggdrasil 三种账号统一管理；accessToken / refreshToken 一律用 `CryptoUtil.encrypt`（AES-256-GCM）加密后写入，写文件走临时文件 + `ATOMIC_MOVE`。
- `MicrosoftAccountStore`：Microsoft 多账号单独持久化；登录流程为设备码登录：Microsoft Live → Xbox Live → XSTS → Minecraft Services，token 加密保存，后续启动自动刷新。
- `MicrosoftAuth` 内含"登录代次 generation"机制，防止 logout 后后台线程复活登录状态。

---

## 5. 下载体系 `com.ecl.download` + `com.ecl.modrinth`

### 5.1 现状说明（重要）

> 项目已经以 `ModMetadataProvider` / `ModMetadataProviderRegistry` 建立内容平台抽象，并把搜索、详情、依赖、更新和本地扫描服务接入该边界。当前仅内置 Modrinth 实现；CurseForge 内容平台仍需单独实现 Provider，现阶段 CurseForge 仅作为**整合包导入/导出格式**存在（见 `com.ecl.pack` 的 `PackFormat` / `DefaultPackService`）。
> 当前内容下载实际是**两条并行线**：官方版本/库/资源下载线 + Modrinth 内容线。

### 5.2 官方版本下载线 `com.ecl.download`

- `DownloadService`（接口）+ `GameDownloader`（实现）：下载版本 JSON、client jar、libraries、assets；SHA-1 校验；`ExecutorCompletionService` 并发；`DownloadListener` 回调进度。
- `install/` 子包：**Task 化重写版**——`InstallState`（各阶段共享可变状态）、`InstallHelpers`（公共下载/校验原语），四个阶段任务 `FetchVersionMetadataTask` → `DownloadClientJarTask` → `DownloadLibrariesTask` → `DownloadAssetsTask`，均继承 `com.ecl.task.Task`。
- `provider/` 子包：下载源解析。`DownloadProvider`（接口：`id/priority/supports/resolve/concurrentDownloads`）+ `OfficialDownloadProvider`（priority 0）+ `MirrorDownloadProvider`（priority 100，把 Mojang 域名映射到 BMCLAPI / fastmcmirror）+ `DownloadProviderRegistry`（含 ServiceLoader 扩展）。官方源慢或失败时自动切镜像。

### 5.3 Modrinth 内容线

**旧版**：`ModrinthDownloader.java`（同步下载器，GUI 首页侧栏"Modrinth 推荐"四类内容在用），按 projectType 下载 mod/shader/resourcepack/modpack，自动处理必需依赖（带环检测 `ensureNoDependencyCycle`）。

**新版完整集成 `com.ecl.modrinth`**（GUI 的 Modrinth 页 `ModBrowserView` 使用）：

```
com.ecl.modrinth
├── api/            # ModrinthApiClient（接口）+ DefaultModrinthApiClient（异步、TTL 缓存、in-flight 合并、指数退避重试、429 处理）；ModrinthHttpTransport（函数式传输）；DTO 在 api/dto/
├── model/          # ModProject / ModVersion / ModFile / ModDependency / DependencyType / InstalledMod / ModCompatibility / ReleaseChannel
├── service/        # 接口 + Default* 实现：ModManagementService(list/setEnabled/uninstall/importLocalJar)、ModInstallationService、ModUpdateService、ModDependencyResolver、ModVersionSelector、LocalModScanner、InstanceOperationLock、SequentialBatchRunner
├── transaction/    # InstallationPlanBuilder + ModInstallationPlan + ModInstallationTransaction（事务化安装计划，可预览/提交/回滚）
├── download/       # ModFileDownloadService（带 HashVerifier 校验）、ModDownloadRequest/Progress
├── repository/     # InstalledModRepository + FileInstalledModRepository（实例内 mods 索引 JSON 持久化）
├── instance/       # ModInstanceContext（实例上下文）+ VersionProfileModInstanceContext + ModLoader 枚举
├── pack/           # MrpackInstaller：.mrpack 安装（解压 overrides、下载依赖、准备加载器、生成可启动实例）
└── provider/       # ModMetadataProvider + Registry（搜索/详情/依赖/哈希更新全链路，ServiceLoader 扩展点）
```

`ModBrowserViewModel`、`DefaultModDependencyResolver`、`DefaultModUpdateService` 与 `DefaultLocalModScanner` 只依赖 `ModMetadataProvider`；`MainController.modSourceServices` 按数据源装配并缓存整套服务。当前内置 `ModrinthMetadataProvider`，第二数据源可通过 `ServiceLoader` 注册而无需修改页面。浏览详情提供依赖分组钻取、渠道/加载器徽标与最小 Markdown 更新日志；进入已安装 Tab 后使用 30 分钟 TTL 自动检查更新。

**四类内容目标**（GUI 侧 `LauncherUI.createContentTargets()` 定义）：`mod`（.jar，支持 Fabric/Forge/NeoForge/Quilt 加载器筛选，导入 `mods`）、`shader`（.zip → `shaderpacks`）、`resourcepack`（.zip → `resourcepacks`）、`modpack`（.mrpack → 生成独立可启动实例）。

---

## 6. 版本管理与加载器安装 `com.ecl.launcher` + `com.ecl.game`

- `VersionManager`：拉取 `version_manifest_v2.json`（镜像回退 + 本地缓存），分类 `FEATURED/RELEASE/PREVIEW/APRIL_FOOLS/ALL`；`mergeLocalLoaderProfiles` 把本地加载器 profile 与远端版本合并排序。
- `VersionRepository`（`com.ecl.game`）：解析 `inheritsFrom` 继承链 → 合成有效 `VersionMetadata`（子版本库/参数覆盖父级，Maven 槽位替换），带 `ConcurrentHashMap` 缓存与 `invalidate`。
- `ModLoaderInstaller`：四种加载器安装。
  - Fabric/Quilt：走 `meta.fabricmc.net/v2`、`meta.quiltmc.org/v3` 的 profile JSON，直接写入 versions 目录并加 `eclModLoader` / `eclMinecraftVersion` 标记。
  - Forge/NeoForge：下载官方 installer.jar（`.sha1` 校验）、按需自动准备 Java、运行 `--installClient` / `--install-client`，把生成的 profile 与 libraries 合并进 ECL 仓库。
- `CrashAnalyzer`：游戏崩溃英文日志分析 → 中文诊断与修复建议。

---

## 7. 游戏启动：新旧两条管线并存

> **注意：项目存在两条启动管线，改动时需确认改哪条。GUI 首页用旧管线，CLI 用新管线。**

- **旧管线 `com.ecl.launcher`**：`LaunchService`（接口）+ `GameLauncher`。自行解析 version JSON 继承链、构建命令、解压 natives（带 ZIP 炸弹防护 `MAX_EXTRACTED_*` 与指纹 marker `.ecl-natives-extracted`）、按版本推断 Java 大版本（1.20.5+ → 21、1.18+ → 17、旧版 → 8）。
- **新管线 `com.ecl.launch`**：
  - `Launcher`（接口）+ `DefaultLauncher`（实现）：`prepare / preview / launch`，发布 `GameLifecycleEvent`（LAUNCHING/STARTED/EXITED/TERMINATED）。
  - 类型化版本模型在 `com.ecl.game`：`VersionMetadata` / `Library` / `DownloadObject` / `AssetIndex` / `ArgumentToken` / `VersionArguments`。
  - `LaunchCommandBuilder`：纯逻辑组装命令（JVM/游戏参数、classpath、rules 评估、`--server/--port`、命令长度上限 32000）。
  - `LaunchOptions`（Builder 模式）、`LaunchEnvironment`、`LaunchVariables`（`${...}` 变量替换）、`ServerAddress`。
  - `NativeLibraryExtractor`、`GameProcess`（进程句柄 + `ProcessOutputPump` + `whenExited()` future）、`ProcessOutputListener`、`BoundedLogBuffer`、`JavaVersionRequirement`。

### Java 运行时自动准备 `com.ecl.runtime`

- `JavaRuntimeUtil` / `JavaRuntimeDownloader` / `DefaultJavaManager`：探测本机 Java（用户配置路径、当前运行时、`JAVA_HOME`、`runtimes/` 目录、常见 JDK 安装目录），不满足版本要求时自动从 Eclipse Adoptium 下载 Temurin JRE 到 `<数据目录>/runtimes/`，后续复用。支持 Windows/macOS/Linux 的 x64、AArch64。

---

## 8. 任务与并发体系 `com.ecl.task`

- `Task`：抽象任务单元，`dependsOn(Task...)` 声明依赖图，`reportProgress`、`cancel()`（协作式取消）、`weight` 权重。
- `TaskExecutor`：执行依赖图（依赖先跑、同图内共享依赖只跑一次、失败中止后续），向 `TaskListener` 转发 `TaskEvent`（queued/started/progress/finished/failed/cancelled），`TaskFuture` 封装 `FutureTask`。
- 使用方：版本安装四阶段任务（`download/install/`）、GUI 下载队列。

---

## 9. 基础设施 `com.ecl.config` + `com.ecl.util` + `com.ecl.event` 等

| 基础设施 | 说明 |
|---|---|
| **配置** | `config.SettingKey`（类型安全键）+ `SettingsManager`（`settings.json`，ATOMIC_MOVE 写，加密值加 `_enc_` 前缀，支持键迁移）；`ECLConfig` 集中所有 `KEY_*` 常量 |
| **日志** | SLF4J + Logback，配置在 `ecl-core/src/main/resources/logback.xml` |
| **HTTP** | `util.HttpUtil`：基于 `java.net.http.HttpClient` 连接池；同步 + 异步（`requestAsync`）；**下载 `.part` 续传**：目标文件旁写 `.part` 与 `.part.meta`（记录 source/ETag/Last-Modified），`Range: bytes=N-` + `If-Range` 续传，完成后 ATOMIC_MOVE 改名；官方源超时 8s、镜像源 60s |
| **加密** | `util.CryptoUtil`：AES-256-GCM（IV 12B 前置 + 128bit tag）；密钥经 Windows DPAPI（`Crypt32Util`）、macOS/Linux 系统钥匙串（`SystemSecretStore`）保护；测试可用 `-Decl.crypto.keyFile=` 明文覆盖 |
| **诊断** | `diagnostic.DiagnosticBundleService`：导出脱敏 ZIP（token/password/Authorization/Cookie 正则剔除，日志截断 512KB） |
| **事件** | `event.EventBus`（发布/订阅、注册序执行、单 handler 失败隔离）；事件有 `GameLifecycleEvent`、`LocaleChangedEvent`、`SettingsChangedEvent` |
| **国际化** | `i18n.ResourceBundleI18n`（英文回退 + 实时切换通知），资源在 `ecl-core/src/main/resources/i18n/`（`messages.properties` 简体默认 / `messages_zh_TW.properties` / `messages_en.properties`）；静态门面 `util.Messages`（`get/format/setLocale/onLocaleChanged`） |
| **工具** | `util.FileUtil`（SHA-1、目录删除）、`ZipUtil`（路径穿越防护）、`PlatformUtil`（OS 枚举）、`RuleEvaluator`/`MinecraftRuleUtil`（Minecraft rules 评估）、`GsonProvider`/`JsonUtil`、`TextUtil`（缩写/脱敏）、`DownloadSourceUtil` |

---

## 10. ecl-gui（JavaFX 界面层）

源码根：`ecl-gui/src/main/java/com/ecl/`

```
com.ecl
├── ECLauncher.java              # JavaFX 启动：Application.launch(LauncherUI.class)
├── ui/
│   ├── LauncherUI.java          # 主窗口（约 4450 行，所有页面构建方法集中于此）
│   ├── MainController.java      # 服务组合根（composition root），构造/关闭所有服务
│   ├── CrashDiagnosticDialog.java
└── modrinth/ui/
    ├── ModBrowserView.java      # 模组浏览器（VBox 组件，双 Tab）
    └── viewmodel/ModBrowserViewModel.java  # 绑定逻辑
```

- **页面导航**：`LauncherUI` 内枚举 `AppView { HOME, VERSIONS, MODRINTH, LOGS, SETTINGS }`；`renderActiveView(int slideDirection)` 切换页面（滑动动画，`ecl.reduceMotion` 可禁用）。对应构建方法：`getOrCreateHomePage`（首页：启动面板 + 下载队列 + 侧栏 Modrinth 推荐 + 崩溃诊断）、`createVersionsPage`（版本页：加载器安装/重装/删除/备份管理）、`createModrinthPage`（嵌入 `ModBrowserView`）、`createLogsPage`（实时控制台、崩溃目录、导出诊断包）、`createSettingsPage`（语言/主题/高级设置）。
- **主题**：`applyTheme → applyThemeToScene` 给场景根节点切换 `theme-light` / `theme-dark` CSS 类；CSS 在 `ecl-gui/src/main/resources/css/launcher.css`（深色为默认，Cyber HUD 风格）。
- **i18n 切换**：`switchLanguage(languageTag)` 后重建 homePage、重设导航文案并 `renderActiveView()`；启动时从设置读 `KEY_LANGUAGE`。
- **组合根**：`MainController` 构造所有服务（DownloadService、Modrinth 系列服务、线程池 `ecl-background-*` / `ecl-mod-download-*`），`close()` 统一关闭；`runAsync/supplyAsync` 带线程名包装。**新增服务在这里装配。**
- 视觉 QA：`LauncherUiSnapshot`（`captureLauncherUi` 任务）。

---

## 11. ecl-cli（无头命令行）

单一文件 `ecl-cli/src/main/java/com/ecl/cli/EclCli.java`（约 720 行），所有命令为静态嵌套类实现 `Callable<Integer>`。

顶层命令 `ecl`（全局 `--json` 选项，`ScopeType.INHERIT` 输出机器可读 JSON）下的子命令树：

```
ecl
├── doctor                    # 检查 Java 与数据目录
├── java   detect | list      # 探测 / 列出 Java 运行时
├── version list | inspect <id>   # 本地版本列表 / 解析继承后的元数据
├── account list | add-offline <name> | remove <identity> | default <identity>
├── launch <version>          # --account/--username/--memory/--dry-run/--show-secrets/--wait；dry-run 输出脱敏命令
├── mod <version> list | enable <file> | disable <file>   # .jar.disabled 启禁用
├── pack   preview <archive> | import <archive> | export <instance> <out> --minecraft <v> [--format ECL|MULTIMC|CURSEFORGE|MRPACK]
├── diagnostics <output.zip>  # 脱敏诊断包
└── settings get <key> | set <key> <value>
```

入口：`main()` → `execute(args)` → `CommandLine.execute`，异常处理器统一输出 JSON/文本错误。

---

## 12. ecl-boot（入口分派）与 ecl-dist（打包）

### 12.1 双入口分派 `ecl-boot/src/main/java/com/ecl/ECL.java`

`main()` 逻辑：
1. **CLI 判定**：参数含 `--cli` 或系统属性 `java.awt.headless=true` → `System.exit(EclCli.execute(args))`。
2. **JavaFX 兜底**：若 classpath 无 `javafx.application.Application` 且未设置 `ECL_BOOTSTRAPPED=1`，用当前 `java.home` 拉起新 java 进程，classpath 追加从 `~/.gradle/caches/modules-2/files-2.1` 递归找出的 JavaFX（按 OS 选 classifier）+ gson jar，设 `ECL_BOOTSTRAPPED=1` 防递归。失败则打印中文提示。
3. **正常 GUI**：`ECLauncher.main(args)` → `Application.launch(LauncherUI.class)`。

### 12.2 jpackage 装配 `ecl-dist/build.gradle.kts`

三个 Exec 任务都 `dependsOn(ecl-boot 的 installDist)`，输入 `ecl-boot/build/install/ECL/lib`：
- `packageWindowsApp`：`jpackage --type app-image` → `dist/windows`；`--main-class com.ecl.ECL`；`--add-launcher ECL-CLI`（main-class `com.ecl.cli.EclCli`，win-console=true）；图标 `ecl-gui/src/main/resources/icons/ecl-icon.ico`。产物 `ECL.exe`（GUI）与 `ECL-CLI.exe`（控制台）。
- `packageMacApp`：按 arch 分 `dist/macos/mac` / `mac-aarch64`，可选 `-PmacSigningIdentity` 签名，图标 `.icns`。
- `packageLinuxApp`：按 arch 分 `dist/linux/linux-x64` / `linux-aarch64`，图标 `.png`。
- 打包前 `normalizeForDelete` 强制解除只读后清空输出目录。

---

## 13. 数据目录与文件布局

ECL 数据目录（`ECLConfig.getBaseDir()`）：Windows `%APPDATA%\.ecl`、macOS `~/Library/Application Support/.ecl`、Linux `~/.ecl`。

```
.ecl/
├── accounts.json          # 统一账户仓库（token 为 AES-256-GCM 密文）
├── microsoft-accounts.json# Microsoft 多账号
├── settings.json          # 设置（加密值 _enc_ 前缀）
├── versions/              # Minecraft 版本文件
├── libraries/             # 依赖库
├── assets/                # 资源文件
├── backups/               # 世界存档备份
└── runtimes/              # 自管 Temurin JRE
```

游戏根目录默认系统 `.minecraft`。`DefaultGameRepository.runDirectory` 是启动、Mod 管理、内容导入和备份的统一运行目录决策入口：`DefaultIsolationType.MODDED` 为默认策略，带加载器实例隔离，原版实例共享游戏根，整合包始终隔离。实例可通过 `<实例根>/.ecl/config/instance-game-settings.json` 覆盖为独立目录或自定义目录。

```
.minecraft/
├── mods/ saves/ config/ ...           # 未隔离实例共享的可变数据
└── versions/<version>/                 # 稳定实例根
    ├── .ecl/config/instance-game-settings.json
    ├── natives-<platform>/             # 实例级原生库
    ├── libraries/                      # hint=local 的实例私有库
    └── mods/ saves/ config/ ...        # 隔离实例的可变数据
```

---

## 14. 关键设计模式与开发约定（agent 必须遵守）

1. **无 DI 框架、无注解扫描**。装配靠**手动构造注入**：GUI 侧 `MainController` 是组合根；CLI 侧命令直接 `new` 服务。没有 Spring/Guice。
2. **ServiceLoader 扩展点（三处）**：`DownloadProviderRegistry`（下载源）、`AuthProviderRegistry`（认证协议）、`ModMetadataProviderRegistry`（Mod 元数据）。新实现放 `META-INF/services`。
3. **命名约定**：接口 `XxxService` / `XxxRepository` / `XxxProvider`；实现统一 `DefaultXxx`（如 `DefaultAccountService`、`DefaultJavaManager`）或 `FileXxx`（如 `FileInstalledModRepository`）；`Manager` 后缀用于 `SettingsManager`、`VersionManager`。
4. **凭据不落明文**：token/密码一律 `CryptoUtil` AES-256-GCM 加密；CLI 输出与诊断包必须脱敏。
5. **原子写**：所有关键文件（accounts.json、settings.json、索引、marker、下载产物）用临时文件 + `ATOMIC_MOVE`。
6. **安全习惯**：路径穿越防护（`safeProfileDirectory`、`safeModPath`、`ZipUtil`、native 解压越界检查）；ZIP 炸弹解压预算（500MB 总量 / 100MB 单文件 / 1 万条目）。
7. **事件解耦**：跨模块通知走 `EventBus`（`GameLifecycleEvent` / `LocaleChangedEvent` / `SettingsChangedEvent`），不要硬耦合。
8. **GUI 线程规则**：耗时操作放后台线程池（`ecl-background-*`），回 JavaFX Application Thread 用 `Platform.runLater`。
9. **版本解析**：继承链解析在 `VersionRepository`（新）与 `GameLauncher.loadVersionJsonWithInheritance`（旧）各一份，改启动逻辑时注意新旧两条管线都要考虑（或只改被调用方）。
10. **`src/` 根目录是历史遗留空壳**：原代码已迁到多模块（git 提交 `01f6d37`），`E:\ECL\src\main\java\com\ecl\download` 下**没有代码**，忽略即可。真正代码都在 `ecl-*/src/`。

---

## 15. 核心数据流

### 15.1 游戏版本生命周期（新管线）

```
VersionManager（远端清单 version_manifest_v2.json，镜像回退）
  → download/install 四阶段 Task（FetchVersionMetadata → DownloadClientJar → DownloadLibraries → DownloadAssets，经 TaskExecutor 跑依赖图，HttpUtil .part 续传）
  → VersionRepository（解析 inheritsFrom 继承链 → 合成 VersionMetadata）
  → DefaultLauncher（prepare/preview/launch）
      → LaunchCommandBuilder（组命令，rules 评估）
      → NativeLibraryExtractor + GameProcess（ProcessOutputPump 输出泵）
      → 发布 GameLifecycleEvent；异常退出 → CrashAnalyzer 中文诊断
```

### 15.2 Modrinth 内容安装流（新版）

```
DefaultModrinthApiClient（搜索/版本查询，TTL 缓存 + 退避重试）
  → ModVersionSelector（按 MC 版本 + 加载器筛选兼容版本）
  → ModDependencyResolver（解析依赖，环检测）
  → InstallationPlanBuilder → ModInstallationPlan（可预览的事务计划）
  → ModInstallationTransaction（提交 → ModFileDownloadService 下载 + HashVerifier 校验）
  → 按内容类型导入实例目录（mods/shaderpacks/resourcepacks）或 MrpackInstaller（.mrpack）
  → FileInstalledModRepository 更新索引
```

### 15.3 认证流

```
UI/CLI 选择 AuthType → AuthProviderRegistry 取对应 AuthProvider
  → login()（离线：nameUUIDFromBytes；Microsoft：设备码 → Live → Xbox → XSTS → Minecraft Services；Yggdrasil：POST 认证服务器）
  → DefaultAccountService 加密持久化到 accounts.json
```

---

## 16. 常见开发任务指引

| 任务 | 改哪里 |
|---|---|
| 加一个新的下载源（镜像） | 实现 `DownloadProvider` 并注册进 `DownloadProviderRegistry`（可放 `META-INF/services`） |
| 加一个外部认证协议 | 实现 `AuthProviderFactory` + `AuthProvider`，ServiceLoader 注册 |
| 新增业务服务 | 在 ecl-core 建接口 + `DefaultXxx` 实现，然后在 `MainController`（GUI）与 `EclCli`（CLI）中装配 |
| 新增 GUI 页面 | 在 `LauncherUI` 扩展 `AppView` 枚举 + 对应 `createXxxPage` 方法，注册导航按钮 |
| 新增 CLI 子命令 | 在 `EclCli` 加静态嵌套类命令，挂到对应顶层命令下 |
| 新增整合包格式 | 在 `com.ecl.pack` 扩展格式处理（参考现有 ECL/MultiMC/CurseForge/MRPACK） |
| 新增模组内容平台 | 实现 `ModMetadataProvider` 并通过 `ServiceLoader` 注册；若接入 CurseForge，还需实现其搜索、版本与更新能力 |

---

## 17. 上手阅读路线（3 步）

1. **入口**：`ecl-boot/ECL.java`（分派）→ `ecl-gui/ECLauncher.java` → `LauncherUI.start()`；CLI 看 `EclCli.main()`。
2. **服务地图**：读 `MainController`（组合根），理解所有 Service 的装配与生命周期。
3. **两条主线**：游戏版本流（第 15.1 节）与 Modrinth 内容流（第 15.2 节），配合 `Task` 体系与 `HttpUtil` 理解下载与启动。

> 保持本文件与代码同步：每次架构级改动（新模块、新扩展点、新核心类、管线变化）后，请同步更新本文档。
