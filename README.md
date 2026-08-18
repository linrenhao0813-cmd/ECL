# ECL

[![CI](https://github.com/linrenhao0813-cmd/ECL/actions/workflows/ci.yml/badge.svg)](https://github.com/linrenhao0813-cmd/ECL/actions/workflows/ci.yml)

ECL 是一个基于 JavaFX 的 Minecraft Java 版启动器。它提供图形界面和无头 CLI，覆盖游戏版本与加载器安装、账户管理、内容安装、整合包导入导出、服务器浏览，以及诊断与打包等常用流程。

> 当前项目版本：`1.0.0`。从源码构建需要 JDK 21。

## 功能

- 安装、重装、删除 Minecraft 正式版、快照和愚人节版本；下载时校验客户端、资源和依赖文件。
- 使用统一下载任务中心管理游戏、服务端、加载器、Java 运行时和内容下载；支持并行数与限速设置、取消、失败重试和自动清理历史任务。
- 安装 Fabric、Quilt、Forge、NeoForge 加载器，并为带加载器的实例提供隔离运行目录。
- 使用离线账户、Microsoft 设备码登录或 Yggdrasil 外置登录；保存的账户凭据采用加密存储。
- Microsoft 正版账户可上传官方皮肤；离线账户可导入本地皮肤，并在启动游戏时自动注入。
- 按 Minecraft 版本和加载器筛选 Modrinth、CurseForge 的模组、光影包、资源包和整合包内容；支持依赖解析及事务式安装。
- 导入 Modrinth `.mrpack` 与 CurseForge 整合包，导出 ECL、MultiMC、CurseForge 或 MRPACK 格式。
- 浏览公开 Minecraft 服务器目录，搜索、查看在线状态、复制地址或作为直连地址启动。
- 自动选择合适的 Java；本机缺少匹配运行时时可下载 Eclipse Temurin JRE。
- 提供启动日志、崩溃中文诊断、世界备份、浅色/深色主题和简体中文、繁体中文、英文切换；首页与服务器页面的主要界面文案已纳入语言资源。
- 提供适合脚本和服务器环境的 CLI，支持 JSON 输出。

## 安全和可靠性

- 对版本 ID、继承版本和客户端 JAR 标识执行统一校验，并通过规范路径检查将版本元数据和客户端文件限制在 `versions` 目录中；依赖库与资源路径也会检查目录边界。
- Java 运行时下载完成后校验 SHA-256。Linux 与 macOS 的 `.tar.gz` 使用内置解压器处理，不依赖系统 `tar`；绝对路径、路径穿越、符号链接、硬链接、异常校验和以及超过条目或体积限制的归档会被拒绝。
- 游戏进程监控使用守护线程。关闭启动器不会等待仍在运行的游戏退出，游戏本体也不会因此被终止。
- 用户取消下载会显示为“已取消”而不是“下载失败”。下载中心仅保留最近 200 条已结束任务，排队或执行中的任务不会被自动删除。

## 快速开始

在仓库根目录执行：

```powershell
.\gradlew.bat run
```

macOS 或 Linux：

```bash
./gradlew run
```

首次构建会下载 Gradle 与项目依赖。运行启动器、登录、下载游戏或在线内容时也需要网络连接。

## 环境要求

- JDK 21
- 项目已包含 Gradle Wrapper，无需另行安装 Gradle
- Windows、macOS 或 Linux

若 Windows 中 `java` 不在 `PATH`，请在同一 PowerShell 会话中配置 JDK 21 的 `JAVA_HOME` 和 `PATH`。

## 图形界面使用

1. 在首页选择 Minecraft 版本与账户，必要时安装加载器。
2. 按需设置游戏目录、Java 路径、内存、JVM 参数、分辨率和直连服务器地址。
3. 在“内容库”中检索并安装模组、光影包、资源包或整合包；搜索结果会按当前实例的 Minecraft 版本与加载器过滤。
4. 在“服务器”页选择公开服务器，可复制地址或设置为下次启动时的直连地址。
5. 在首页或账号设置中选择“上传皮肤”：Microsoft 正版账户会上传到 Minecraft 官方皮肤服务；离线账户会导入到本地并随游戏启动自动注入。两种方式均支持 64×64 或 64×32 的 PNG 文件（最大 1 MiB），可选择经典（宽手臂）或纤细（细手臂）模型。

离线皮肤与玩家名（含大小写）绑定；更改玩家名后需要重新导入。离线皮肤仅保存在本机 ECL 数据目录，可随时通过“清除皮肤”移除。

CurseForge 官方 API 需要 API Key。可在应用的高级设置中填写，或通过环境变量 `CURSEFORGE_API_KEY`、JVM 参数 `-Decl.curseforge.apiKey=...` 提供。未配置时，Modrinth 功能仍可正常使用。

公开服务器目录来自第三方服务。目录收录不表示 ECL、Mojang 或 Microsoft 对服务器内容、安全性或运营方式的认可，请自行判断后再连接。

## CLI

通过 Gradle 启动 CLI：

```powershell
.\gradlew.bat :ecl-cli:run --args="doctor --json"
```

也可以在打包后的 Windows 应用中使用：

```powershell
ECL --cli doctor --json
```

常用命令：

```text
doctor
java detect | list
version list | inspect <version>
account list | add-offline | remove | default | skin | skin-remove
account skin <username> <skin.png> [--slim]
account skin-remove <username>
launch <version> [--dry-run] [--account <identity>] [--memory <MiB>] [--wait]
mod list | enable | disable
pack preview | import | export
diagnostics <output.zip>
settings get | set
```

在任意命令后加 `--help` 查看完整参数；全局 `--json` 输出机器可读结果。`account skin` 为离线账户导入本地 PNG 皮肤，`--slim` 选择纤细模型；`launch --dry-run` 仅预览启动命令，默认会隐藏凭据。

## 构建与验证

运行测试和静态检查：

```powershell
.\gradlew.bat check
```

`check` 会运行 JUnit、Checkstyle、SpotBugs 和 JaCoCo 报告任务。

构建全部模块：

```powershell
.\gradlew.bat build
```

生成可分发的应用目录：

```powershell
.\gradlew.bat installDist
```

产物位于：

```text
ecl-boot/build/install/ECL/
```

GitHub Actions 会在 Ubuntu、Windows 和 macOS 上执行 `build check`，并额外生成 Windows 与 macOS 应用镜像作为工作流产物。

### 原生应用镜像

`jpackage` 必须在目标操作系统上运行。以下任务会先构建分发目录：

```powershell
.\gradlew.bat packageWindowsApp
```

```bash
./gradlew packageMacApp
./gradlew packageLinuxApp
```

输出位置：

```text
dist/windows/ECL/ECL.exe
dist/windows/ECL/ECL-CLI.exe
dist/macos/mac/ECL.app
dist/macos/mac-aarch64/ECL.app
dist/linux/linux-x64/ECL/
dist/linux/linux-aarch64/ECL/
```

## 数据目录

启动器数据默认保存于：

| 系统 | ECL 数据目录 | 默认游戏目录 |
| --- | --- | --- |
| Windows | `%APPDATA%\.ecl` | `%APPDATA%\.minecraft` |
| macOS | `~/Library/Application Support/.ecl` | `~/Library/Application Support/minecraft` |
| Linux | `~/.ecl` | `~/.minecraft` |

该目录会保存版本元数据、库、资源、运行时、配置、备份和诊断文件。游戏目录可在设置中覆盖；默认情况下，带加载器的实例使用隔离运行目录，原版实例共享游戏根目录。

## 项目结构

```text
ecl-boot/  GUI 与 CLI 的主入口
ecl-core/  认证、下载、游戏启动、实例、整合包与基础服务
ecl-gui/   JavaFX 界面、样式与资源
ecl-cli/   picocli 无头命令行入口
ecl-dist/  jpackage 打包任务
config/    Checkstyle 与 SpotBugs 配置
```

## 技术栈

- Java 21、JavaFX 21、Gradle 8.5
- Gson、Jackson、picocli
- SLF4J 与 Logback
- JUnit 5、Checkstyle、SpotBugs、JaCoCo

## 许可证

本项目使用 [GNU General Public License v3.0](LICENSE) 许可证。详见 [LICENSE](LICENSE)。
