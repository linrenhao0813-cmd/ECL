# ECL

[![CI](https://github.com/linrenhao0813-cmd/ECL/actions/workflows/ci.yml/badge.svg)](https://github.com/linrenhao0813-cmd/ECL/actions/workflows/ci.yml)

ECL 是一个基于 JavaFX 的 Minecraft Java 版启动器，覆盖版本安装、账户管理、内容下载、整合包维护与服务器浏览等常用流程。界面为固定深色的森林绿主题，支持简体中文、繁体中文和英文。

> 当前版本：`1.0.1` · Windows 平台 · 从源码构建需要 JDK 21

## 功能

### 游戏版本与实例

- 安装、重装、删除 Minecraft 正式版、快照和愚人节版本，下载时校验客户端、资源和依赖文件。
- “实例”菜单从用户配置的 `.minecraft/versions` 中检测已有实例，直接切换启动目标；不会默认选中在线版本从零下载。
- 带加载器的实例使用隔离运行目录，原版实例共享游戏根目录。
- 打包版可为所选实例创建桌面或开始菜单快捷方式。

### 模组加载器

- 安装 Fabric、Quilt、Forge、NeoForge 加载器，选择 Fabric 时自动安装匹配的 Fabric API。
- 首页“一键升级”将当前实例的加载器和可识别模组升级为当前 Minecraft 版本兼容的最新版本，保留实例目录、存档及启动配置。加载器优先选择稳定版；模组遵循设置中的发布通道，不跨 Minecraft 版本或加载器类型升级。

### 账户与皮肤

- 支持离线账户和 Microsoft 设备码登录，保存的凭据采用加密存储。旧版 Yggdrasil 账号记录保留，但不再提供外置登录。
- Microsoft 登录默认使用内置公共客户端 ID，可通过 `ecl.microsoft.clientId` 或环境变量 `ECL_MICROSOFT_CLIENT_ID` 覆盖。
- Microsoft 正版账户可上传官方皮肤；离线账户可导入本地皮肤，并在启动游戏时自动注入。两种方式均支持 64×64 或 64×32 的 PNG（最大 1 MiB），可选经典（宽手臂）或纤细（细手臂）模型。
- 窗口右上角的玩家头像显示 Microsoft 账号或已导入离线皮肤的头部；没有可用皮肤时显示史蒂夫头部。

### 内容下载与 Mod 管理

- 按 Minecraft 版本和加载器筛选 Modrinth 的模组、光影包、资源包和整合包，支持依赖解析与事务式安装。
- Mod 管理支持多选顺序更新、批量启用、禁用和卸载，本地 `.jar` 文件可拖入启动器导入当前实例。
- 下载默认并行数按处理器数量在 4–8 之间调整，支持断点续传与失败换源；用户取消显示为“已取消”而非“下载失败”。

### 整合包

- 导入 Modrinth `.mrpack` 整合包，导出 ECL、MultiMC 或 MRPACK 格式。
- 已记录 Modrinth 来源的整合包可在“整合包更新”页面检查并批量更新。

### 服务器

- 浏览公开 Minecraft 服务器目录，搜索、查看在线状态、复制地址或作为下次启动的直连地址。

### Java 与启动配置

- 自动选择合适的 Java，本机缺少匹配运行时时可下载 Eclipse Temurin JRE（下载后校验 SHA-256）。
- 按实例保存 Java 路径、内存模式、最大内存和自定义 JVM 参数；在高级设置中编辑已选择实例时同步更新该实例配置。

### 其他

- 崩溃报告中文诊断、世界备份。

## 快速开始

```powershell
git clone https://github.com/linrenhao0813-cmd/ECL.git
cd ECL
.\gradlew.bat run
```

首次构建会通过 Gradle Wrapper 自动下载 Gradle 8.14.4 与项目依赖，无需另行安装 Gradle。运行启动器、登录、下载游戏或在线内容时需要网络连接。

### 环境要求

- Windows
- JDK 21

若 `java` 不在 `PATH` 中，请在同一 PowerShell 会话里配置 JDK 21 的 `JAVA_HOME` 和 `PATH`。

## 图形界面使用

1. **下载页**：在“游戏实例”分类选择 Minecraft 版本，进入安装页后选择原版或模组加载器。账户可从窗口右上角入口管理。
2. **设置**：按需配置游戏目录、Java 路径、内存、JVM 参数、分辨率和直连服务器地址。选中实例后打开高级设置时，Java、内存和 JVM 参数写入该实例的启动配置；未选中实例时保存的全局值作为新实例默认值。
3. **内容**：在“下载”页检索并安装模组、光影包、资源包或整合包，搜索结果按当前实例的版本与加载器过滤。Mod 更新页面支持多选顺序更新。
4. **服务器页**：选择公开服务器，复制地址或设置为下次启动时的直连地址。
5. **皮肤**：通过窗口右上角账户入口进入账号设置，正版账户选择“上传皮肤”，离线账户选择“导入皮肤”；离线皮肤仅保存在本机 ECL 数据目录，可随时“清除皮肤”。

首页主区域显示当前实例、启动和“一键升级”操作；账户摘要及底部活动栏不再显示。背景区域随窗口高度伸展，图片按比例铺满。

选择带 Fabric、Quilt、Forge 或 NeoForge 的实例后，点击首页“一键升级”，启动器会自动升级加载器、补齐新版加载器依赖库、识别本地模组并顺序安装兼容更新，进度和结果显示在按钮下方。原版和未选择实例时按钮不可用，运行中的实例拒绝升级。禁用、未知来源、损坏或重复项目的模组会跳过；单个模组失败会保留旧文件并继续其他更新，已成功的更新保留，可再次点击重试。整合包实例也可使用此功能，但独立升级其组件会偏离整合包作者指定的版本。

离线皮肤与玩家名（含大小写）绑定，更改玩家名后需要重新导入。

### 公开服务器目录说明

公开服务器目录来自第三方服务。目录收录不表示 ECL、Mojang 或 Microsoft 对服务器内容、安全性或运营方式的认可，请自行判断后再连接。

## 构建与验证

以下命令均在仓库根目录执行。

### 测试与静态检查

```powershell
.\gradlew.bat check
```

`check` 会运行 JUnit、Checkstyle、SpotBugs 和 JaCoCo 报告任务，并对核心模块和 GUI 模块执行覆盖率下限校验：

| 模块 | 行覆盖率 | 分支覆盖率 |
| --- | --- | --- |
| `ecl-core` | 60% | 40% |
| `ecl-gui` | 20% | 15% |

运行单个测试示例：`.\gradlew.bat :ecl-core:test --tests com.ecl.package.ClassTest`。Gradle 依赖使用锁定和校验元数据。

### 构建与分发

```powershell
.\gradlew.bat build          # 编译并验证全部模块
.\gradlew.bat installDist   # 生成分发目录：ecl-boot/build/install/ECL/
```

### 界面快照

```powershell
.\gradlew.bat captureLauncherUi
```

默认输出 `ecl-gui/build/visual-qa/ecl-home.png`，使用隔离的数据目录，不会读写当前用户的 ECL 配置。可选模式：

| 模式 | 说明 |
| --- | --- |
| `-PuiSnapshotMode=forest` | 带固定实例的森林首页 |
| `-PuiSnapshotMode=forest-compact` | 1180×720 紧凑窗口 |
| `-PuiSnapshotMode=forest-en` | 英文界面 |
| `-PuiSnapshotMode=forest-empty` | 无本地实例 |

背景素材及生成说明位于 `ecl-gui/src/main/resources/images/`；背景中不含按钮或文字，交互均由 JavaFX 控件提供。

### Windows 应用镜像

```powershell
.\gradlew.bat packageWindowsApp
```

使用 Windows JDK 21 自带的 `jpackage.exe` 生成包含 Java 运行时的应用镜像，输出位于 `dist/windows/ECL/ECL.exe`。注意：该任务生成的是应用镜像而非单文件安装程序，发布时必须保留整个 `dist/windows/ECL/` 目录，`ECL.exe`、`app/` 和 `runtime/` 需保持原有相对位置。

### CI

GitHub Actions 在 Windows 上执行 `check`（PR 另有依赖审查），随后生成 Windows 应用镜像作为工作流产物。

## 数据目录与实例配置

启动器数据默认保存于：

| 系统 | ECL 数据目录 | 默认游戏目录 |
| --- | --- | --- |
| Windows | `%APPDATA%\.ecl` | `%APPDATA%\.minecraft` |

数据目录保存版本元数据、库、资源、运行时、配置、备份和运行日志；游戏目录可在设置中覆盖。实例目录内部：

- `.ecl/config/launch-profile.json` — 实例启动配置，带 `schemaVersion` 的 UTF-8 JSON，临时文件 + 原子替换写入。实例首次读取时迁移现有全局 `javaPath`、`maxMemoryMb` 和 `jvmArgs`，旧全局设置保留为未迁移实例的默认值。
- `.ecl/operations/` — 操作协调器持久化的操作日志（`operationId` 及运行中、成功、失败状态）。Mod 安装、启用、禁用、卸载、索引修复和整合包更新共享同一协调器：同一实例的文件变更串行执行，不同实例互不阻塞。
- `.ecl/game-process.json` — 运行中游戏的 PID 与启动时间。

自动修复执行器和对应界面属于后续开发内容。

## 安全与可靠性

- **来源校验**：版本清单和启动元数据只从 Mojang 权威地址读取，镜像仅用于下载带官方摘要的二进制文件；网络内容缺少有效摘要或大小时拒绝安装，HTTP 仅允许显式环回地址。
- **路径防护**：对版本 ID、继承版本和客户端 JAR 标识统一校验，通过规范路径检查将版本元数据和客户端文件限制在 `versions` 目录内；依赖库与资源路径同样检查目录边界。
- **运行时安全**：Java 运行时下载后校验 SHA-256；解压 Windows ZIP 时限制条目数、单文件大小、总大小和压缩比，拒绝越出运行时目录的条目。内容库图标仅从 Modrinth 的 HTTPS CDN 加载。
- **游戏进程管理**：监控使用守护线程，关闭启动器不会等待或终止运行中的游戏；`game-process.json` 使重新打开的 ECL 仍能阻止运行时配置覆盖和重复启动。
- **整合包事务**：Modrinth 整合包通过 `.ecl-pack-manifest.json` 记录受管理文件及 SHA-512。更新在同一 journal 事务中提交实例内容、`.mrpack` 和 profile 元数据；新版移除的文件仅在仍匹配旧哈希时删除，用户修改过的文件保留并警告，失败时全部回滚。加载器依赖变化时会先准备对应加载器版本，再提交整合包事务。整合包更新与 Mod 文件操作共用实例锁，运行中的实例拒绝更新，下载完成后、提交文件前会再次检查运行状态。

## 项目结构

源码入口、业务职责与关键流程见[源码阅读指南](docs/codebase-guide.md)。

| 模块 | 职责 |
| --- | --- |
| `ecl-boot/` | JavaFX 图形启动入口（`com.ecl.ECL`） |
| `ecl-core/` | 认证、下载、游戏启动、实例、整合包等平台无关服务 |
| `ecl-gui/` | JavaFX 界面、控制器、样式与资源 |
| `ecl-dist/` | Windows `jpackage` 打包任务 |
| `config/` | Checkstyle 与 SpotBugs 配置 |

依赖方向为 `ecl-boot` → `ecl-gui` → `ecl-core`。生产代码位于 `src/main/java`，测试位于 `src/test/java`，运行时资源位于 `src/main/resources`。

## 技术栈

- Java 21 · JavaFX 21 · Gradle 8.14.4
- Gson · Jackson · JNA · TwelveMonkeys ImageIO
- SLF4J · Logback
- JUnit 5 · TestFX · Checkstyle · SpotBugs · JaCoCo

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。

## 贡献指南

贡献前请先阅读 [AGENTS.md](AGENTS.md)，了解项目结构、开发命令、编码风格、测试要求与提交规范。
