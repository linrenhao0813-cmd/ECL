# ECL

ECL 是一个基于 JavaFX 的轻量 Minecraft 启动器。项目使用 Gradle 构建，支持版本与 Mod 加载器安装、Modrinth 整合包安装、游戏启动、Java 运行时自动准备、离线登录、正版 Microsoft 登录和外置 Yggdrasil 登录。

## 功能特性

- Minecraft 正式版、预览版/快照、愚人节版列表获取、版本资源下载、删除与重装
- Fabric、Quilt、Forge 和 NeoForge 加载器安装，可自动选择最新兼容版本
- 离线登录、正版 Microsoft 设备码登录与 Yggdrasil 外置登录
- Microsoft 多账号保存与切换；Yggdrasil 服务器、用户名和密码持久化
- 正版登录时自动复制 Microsoft 设备码，浏览器打开后可直接粘贴授权
- 自定义 Java 路径、游戏目录、JVM 参数、最大内存、窗口分辨率、全屏、直连服务器和处理器核心数
- 启动时按 Minecraft 版本自动选择满足要求的 Java；本机缺失时自动下载并管理 Eclipse Temurin JRE
- 按版本创建独立实例目录，自动准备 `mods`、`shaderpacks`、`resourcepacks`、`saves` 和 `logs`
- 自动解析 Minecraft 版本 JSON、依赖库、原生库、平台规则与启动参数
- 下载和启动前校验客户端、依赖库、原生库与资源索引，缺失或损坏时自动补齐
- Modrinth 模组、光影包、材质包和整合包内置搜索下载
- 模组、光影包和材质包自动导入对应实例目录；`.mrpack` 自动安装依赖、覆盖文件和所需加载器并生成可启动实例
- 游戏输出实时显示在日志页，可选择启动后自动打开控制台
- 可选择游戏启动后隐藏启动器，并在游戏退出后恢复
- 游戏异常退出后自动分析英文日志，输出中文解释和修复建议
- 当前版本可一键打开 mc 中文 Wiki 对应版本更新介绍
- 官方源下载较慢或失败时自动尝试镜像源
- 下载使用 `.part` 临时文件并支持 HTTP Range 续传；中断后保留已完成数据
- ECL、Modrinth、CurseForge 和 MultiMC 整合包预览、事务导入与导出
- 简体中文、繁体中文、English 三语运行时切换，无需重启
- 浅色/深色主题、首次启动向导与一键脱敏诊断包
- 无头 CLI 支持账户、Java、版本、启动 dry-run、模组、整合包、诊断和设置操作
- 依赖库和资源文件使用受控线程池并发下载，默认按设备使用 4～8 个线程
- 极简 Minecraft 风格 JavaFX 桌面界面
- Windows `jpackage` 应用镜像打包任务，可生成 `ECL.exe`

## 正版 Microsoft 登录

首页账号区域提供 `正版登录` 按钮，也可以在账号模式中选择 `微软登录 (Microsoft)`。

登录流程：

1. 点击 `正版登录`。
2. ECL 获取 Microsoft 设备码，并自动复制登录代码到系统剪贴板。
3. 浏览器打开微软验证页面后，直接粘贴代码完成授权。
4. ECL 自动换取 Xbox Live、XSTS 和 Minecraft Services 令牌。
5. 验证账号是否拥有 Minecraft Java 版授权，并读取正版玩家名、UUID 和访问令牌。

授权成功后会加密保存账号令牌，后续启动会优先自动刷新登录状态。可以通过 `添加账号` 保存多个 Microsoft 账号，并在首页账号下拉框中切换。如果自动复制失败，登录窗口仍会显示代码，并提供 `复制代码` 按钮。

## 外置 Yggdrasil 登录

选择 `外置登录 (Yggdrasil)` 后填写认证服务器、用户名和密码即可启动。首次认证成功后，ECL 会保存服务器和用户名，并使用本机密钥加密保存密码；后续启动无需重复输入。

## Mod 加载器与版本管理

版本页提供 `安装加载器`，支持 Fabric、Quilt、Forge 和 NeoForge。填写 Minecraft 版本后，可以留空加载器版本以自动选择最新兼容版本，也可以指定版本安装；安装完成的加载器实例会直接加入版本列表。

同一页面还提供版本删除和重装：

- 删除会同时移除启动器版本文件和对应独立实例，包括模组、存档、设置与日志，操作前会二次确认
- 重装会重建版本元数据和依赖；加载器实例会重新安装对应加载器，同时保留独立实例中的模组、存档与设置

## Modrinth 内容下载

启动器内置 Modrinth 内容下载入口，支持按当前选择的 Minecraft 版本筛选兼容内容：

- 模组：支持 Fabric、Forge、NeoForge、Quilt 加载器筛选，下载 `.jar` 并导入 `mods`
- 光影包：下载 `.zip` 并导入 `shaderpacks`
- 材质包：下载 `.zip` 并导入 `resourcepacks`
- 整合包：下载 `.mrpack`，校验并下载客户端依赖，解压 overrides，自动准备所需加载器，并生成独立的可启动版本

搜索结果支持点击查看简介，下载完成后会自动安装到对应实例。模组下载会尝试同时处理 Modrinth 标记的必需依赖。

没有输入关键词时，下载窗口会自动加载 Modrinth 官网下载量排序列表，便于直接浏览热门模组、光影包、材质包和整合包。

## 启动可靠性与运行环境

ECL 会在启动前根据当前 Minecraft 版本 JSON 中的 `javaVersion.majorVersion` 自动选择合适的 Java 运行时。如果版本 JSON 没有提供该字段，会按常见版本规则推断：旧版本使用 Java 8，1.18 到 1.20.4 使用 Java 17，1.20.5 及以上使用 Java 21。

Java 查找顺序包括用户配置路径、当前运行时、`JAVA_HOME`、启动器自管的 `runtimes` 目录和常见本地 JDK 安装目录。如果没有找到精确匹配的 Java，ECL 会从 Eclipse Adoptium 自动下载对应版本的 Temurin JRE，解压到启动器数据目录并在后续启动中复用。目前自动下载支持 Windows、macOS 和 Linux 的 x64、AArch64，以及服务可用时的 x86 运行时。

本地版本不再只按文件是否存在判断是否可启动。启动前会校验客户端 jar、依赖库、原生库和资源索引的 SHA-1；文件缺失或损坏时会回到下载流程自动补齐。依赖库和原生库也会按 Minecraft 官方 `rules` 判断当前系统、架构和系统版本，避免下载或加载不适用于当前平台的文件。

## 游戏参数与实时控制台

高级设置中可以配置窗口分辨率、全屏启动、直连服务器地址和游戏可见的处理器核心数，也可以选择游戏启动后隐藏启动器、退出后恢复，以及启动后自动打开实时控制台。

日志页会在游戏运行期间持续显示标准输出，并保留最近的日志尾部；可以随时清空控制台，或打开日志、崩溃报告和当前实例的 `mods` 目录。

## 版本更新介绍

启动页的当前版本旁提供 `更新介绍` 按钮。选择正式版或快照版后，点击按钮会打开 mc 中文 Wiki 对应版本页面，用于查看该 Minecraft 版本的更新内容、修复项目和变更说明。

## 启动错误中文诊断

游戏启动后，ECL 会后台监控 Minecraft 进程输出和本次启动生成的 crash report。异常退出时会自动弹出中文诊断窗口，包含：

- 错误类型中文说明
- 可执行的修复建议
- 从英文日志中提取的关键报错行
- 打开崩溃报告目录和 `mods` 目录的快捷按钮

当前可识别的常见问题包括 Java 版本不匹配、内存不足、重复模组、前置依赖缺失、模组版本不兼容、Mixin 注入失败、OpenGL/显卡驱动异常、文件占用、jar 损坏和网络下载失败。

## 界面结构

- 左侧导航栏区分首页、版本、Modrinth、设置和日志
- 首页聚合版本选择、账号模式、Microsoft 多账号、游戏目录和 JVM 参数
- 版本页提供加载器安装、版本删除、重装和本地目录入口
- 右侧显示下载队列、Modrinth 推荐和崩溃诊断入口
- 长页面和弹窗支持鼠标滚轮滚动

## 环境要求

- JDK 21
- Gradle Wrapper 已包含在项目中，无需单独安装 Gradle
- 网络连接，用于下载 Gradle 依赖、Minecraft 资源、Microsoft 登录令牌和 Modrinth 内容

## 快速开始

在项目根目录执行：

```powershell
.\gradlew.bat run
```

如果在 Linux 或 macOS 上运行：

```bash
./gradlew run
```

首次启动时，Gradle 会下载 JavaFX、Gson、Jackson、picocli 等依赖。

无图形环境或自动化脚本可以使用命令行入口：

```powershell
.\gradlew.bat :ecl-cli:run --args="doctor --json"
```

打包后的启动器也支持 `ECL --cli doctor --json`。CLI 高频命令包括：

```text
doctor
java detect|list
version list|inspect
account list|add-offline|remove|default
launch <version> [--dry-run] [--account ...] [--memory ...]
mod list|enable|disable
pack preview|import|export
diagnostics <output.zip>
settings get|set
```

全局 `--json` 可用于脚本化输出。Windows 运行 Gradle/CLI 脚本前若 `java` 不在 `PATH`，需要在同一个 PowerShell 会话设置 JDK 21 的 `JAVA_HOME` 与 `PATH`。

## 构建

生成 Jar：

```powershell
.\gradlew.bat build
```

生成安装目录：

```powershell
.\gradlew.bat installDist
```

构建完成后可在以下目录找到启动脚本和依赖：

```text
ecl-boot/build/install/ECL/
```

## Windows 应用打包

在 Windows 环境下，可以使用自定义任务生成应用镜像：

```powershell
.\gradlew.bat packageWindowsApp
```

输出目录：

```text
dist/windows/ECL/
```

可执行文件：

```text
dist/windows/ECL/ECL.exe
dist/windows/ECL/ECL-CLI.exe
```

`ECL.exe` 为桌面入口，`ECL-CLI.exe` 为带控制台输出的无头入口。

该任务依赖 JDK 自带的 `jpackage.exe`。如果打包时提示 `dist/windows` 无法删除，通常是旧版 `ECL.exe` 正在运行、资源管理器占用目录，或 OneDrive 正在同步旧 runtime 文件。关闭相关进程或暂停同步后重新执行打包即可。

macOS 和 Linux 分别使用：

```bash
./gradlew packageMacApp
./gradlew packageLinuxApp
```

macOS 可通过 `-PmacSigningIdentity="Developer ID Application: ..."` 启用 `jpackage` 签名。Linux 应用镜像输出到 `dist/linux/linux-x64/` 或 `dist/linux/linux-aarch64/`。签名证书、时间戳服务及各平台公证必须由发布环境注入，发布前按 [发布清单](docs/RELEASE_CHECKLIST.md) 复核。

## 数据目录

ECL 会在用户目录下创建启动器数据目录：

- Windows: `%APPDATA%\.ecl`
- macOS: `~/Library/Application Support/.ecl`
- Linux: `~/.ecl`

其中会保存版本文件、依赖库、资源文件、自管 Java 运行时、加密的账号凭据、游戏目录和启动器配置。自动下载的 Java 位于：

```text
<ECL 数据目录>/runtimes/
```

Microsoft 多账号列表位于 `microsoft-accounts.json`，令牌以加密形式保存；Yggdrasil 密码同样写入加密配置，不以明文保存。

统一账户服务使用 `accounts.json` 保存离线、Microsoft 与 Yggdrasil 账户元数据，访问令牌和刷新令牌采用 AES-GCM 加密；CLI 列表与诊断包不会输出凭据。

默认游戏根目录为系统 Minecraft 目录 `.minecraft`，具体版本使用独立的版本隔离目录：

```text
.minecraft/versions/<version>/
```

## 项目结构

```text
.
├── build.gradle.kts              # 聚合构建与质量任务
├── settings.gradle.kts           # 五模块声明
├── gradle/libs.versions.toml      # 依赖版本唯一事实源
├── gradlew
├── gradlew.bat
├── ecl-boot/                      # GUI/CLI 双入口与启动兜底
├── ecl-core/                      # 认证、下载、版本、启动、任务与基础设施
├── ecl-gui/                       # JavaFX 界面、样式和图标
├── ecl-cli/                       # picocli 无头命令行入口
├── ecl-dist/                      # jpackage 装配任务
└── config/checkstyle/             # 静态检查规则
```

## 主要依赖

- JavaFX 21
- Gson 2.10.1
- Jackson 2.18.2
- picocli 4.7.6
- SLF4J 2.0.16 + Logback 1.5.16
- JUnit 5.11.4

## 开源协议

本项目基于 GNU General Public License v3.0 开源，详见 `LICENSE` 文件。

## 开发提示

- 主入口类：`com.ecl.ECL`
- JavaFX 应用类：`com.ecl.ui.LauncherUI`
- 默认应用版本：`1.0.0`
- 源码编译编码：`UTF-8`
- 下载线程数：默认自动选择 4～8，可通过 JVM 参数 `-Decl.download.threads=线程数` 调整（范围 2～16）

清理构建产物：

```powershell
.\gradlew.bat clean
```

## 本次更新：Windows 与 macOS 跨平台适配

- Windows 继续使用 `win` JavaFX 依赖，并保留 `packageWindowsApp` 打包任务生成 `ECL.exe`。
- macOS 会按当前芯片自动选择 JavaFX 原生依赖：Intel 使用 `mac`，Apple Silicon / M 系列使用 `mac-aarch64`。
- 新增 `packageMacApp` 打包任务，可生成 `ECL.app`；输出目录会按架构区分为 `dist/macos/mac/` 和 `dist/macos/mac-aarch64/`。
- Minecraft 原生库匹配已适配 macOS Intel 与 M 系列，兼容 `natives-osx`、`natives-macos`、`natives-osx-arm64`、`natives-macos-arm64` 等 classifier。
- macOS 启动游戏时会自动补充必要的 `-XstartOnFirstThread` 参数，避免 LWJGL/窗口线程问题。
- Java 自动检测增强：支持 Windows 常见 JDK 目录、macOS `.jdk` bundle、`/Library/Java/JavaVirtualMachines`、Homebrew Intel 与 Apple Silicon JDK 路径。
