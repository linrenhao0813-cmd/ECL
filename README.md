# ECL

[![CI](https://github.com/linrenhao0813-cmd/ECL/actions/workflows/ci.yml/badge.svg)](https://github.com/linrenhao0813-cmd/ECL/actions/workflows/ci.yml)

ECL 是一个基于 JavaFX 的 Minecraft Java 版启动器，覆盖游戏版本与加载器安装、账户管理、内容安装、整合包导入导出、服务器浏览，以及诊断与打包等常用流程。

> 当前项目版本：`1.0.0`。从源码构建需要 JDK 21。

## 功能

- 安装、重装、删除 Minecraft 正式版、快照和愚人节版本；下载时校验客户端、资源和依赖文件。
- 使用统一下载任务中心管理游戏、服务端、加载器、Java 运行时和内容下载；支持并行数与限速设置、取消、失败重试和自动清理历史任务。
- 安装 Fabric、Quilt、Forge、NeoForge 加载器，并为带加载器的实例提供隔离运行目录。
- 使用离线账户、Microsoft 设备码登录或 Yggdrasil 外置登录；保存的账户凭据采用加密存储。
- Microsoft 正版账户可上传官方皮肤；离线账户可导入本地皮肤，并在启动游戏时自动注入。
- 按 Minecraft 版本和加载器筛选 Modrinth、CurseForge 的模组、光影包、资源包和整合包内容；支持依赖解析及事务式安装。
- Mod 管理支持多选顺序更新、批量启用、禁用和卸载，也可以将本地 `.jar` 文件拖入启动器导入当前模组实例。
- 导入 Modrinth `.mrpack` 与 CurseForge 整合包，导出 ECL、MultiMC、CurseForge 或 MRPACK 格式；已记录 Modrinth 来源的整合包可在“整合包更新”页面检查并批量更新。当前在线整合包更新仅支持 Modrinth，CurseForge 整合包仍支持导入和导出，但不提供在线版本更新。
- 浏览公开 Minecraft 服务器目录，搜索、查看在线状态、复制地址或作为直连地址启动。
- 自动选择合适的 Java；本机缺少匹配运行时时可下载 Eclipse Temurin JRE。
- 按实例保存 Java 路径、内存模式、最大内存和自定义 JVM 参数；在高级设置中编辑已选择实例时会同步更新该实例配置。
- 首页按实例展示累计游玩时长、最近启动时间和启动次数；打包版可为所选实例创建桌面或开始菜单快捷方式。
- 提供启动日志、崩溃中文诊断、诊断包导出、世界备份、浅色/深色主题和简体中文、繁体中文、英文切换；首页与服务器页面的主要界面文案已纳入语言资源。

## 实例配置与操作基础

- 实例启动配置保存在 `<实例目录>/.ecl/config/launch-profile.json`，采用带 `schemaVersion` 的 UTF-8 JSON，并通过临时文件与原子替换写入。
- 游玩时长、启动次数和最近启动记录保存在 `<实例目录>/.ecl/config/playtime.json`。
- 实例第一次读取启动配置时，会迁移现有全局 `javaPath`、`maxMemoryMb` 和 `jvmArgs`；旧全局设置继续保留，作为其他尚未迁移实例的默认值。
- Mod 安装、启用、禁用、卸载、索引修复和整合包更新共享实例操作协调器；同一实例的文件变更会串行执行，不同实例互不阻塞。
- 协调器支持为自动修复、备份恢复等语义化操作生成 `operationId`，并将运行中、成功或失败状态写入 `<实例目录>/.ecl/operations/`。自动修复执行器和对应界面仍属于后续开发内容。

## 安全和可靠性

- 对版本 ID、继承版本和客户端 JAR 标识执行统一校验，并通过规范路径检查将版本元数据和客户端文件限制在 `versions` 目录中；依赖库与资源路径也会检查目录边界。
- 版本清单和版本启动元数据只从 Mojang 权威地址读取；镜像仅用于下载带官方摘要的二进制文件。网络内容缺少有效摘要或大小时会拒绝安装，HTTP 仅允许显式环回地址。
- Java 运行时下载完成后校验 SHA-256；解压 Windows ZIP 时会限制条目数、单文件大小、总大小和压缩比，并拒绝越出运行时目录的条目。
- 内容库图标只会从 Modrinth 或 CurseForge 的 HTTPS CDN 加载；诊断包会对带前缀的令牌、密码和 API Key 配置执行脱敏。
- 游戏进程监控使用守护线程。关闭启动器不会等待仍在运行的游戏退出，游戏本体也不会因此被终止。
- 整合包更新与 Mod 文件操作共用实例锁，运行中的实例会拒绝更新；下载完成后、提交文件前还会再次检查运行状态。
- Modrinth 整合包通过 `.ecl-pack-manifest.json` 记录受管理文件及其 SHA-512。更新会在同一 journal 事务中提交实例内容、`.mrpack` 和 profile 元数据；新版移除的文件仅在仍匹配旧哈希时删除，用户修改过的文件会保留并显示警告，失败时全部回滚。加载器依赖变化时会先准备对应的 Fabric、Quilt、Forge 或 NeoForge 版本，再提交整合包事务。
- 用户取消下载会显示为“已取消”而不是“下载失败”。下载中心仅保留最近 200 条已结束任务，排队或执行中的任务不会被自动删除。

## 快速开始

获取源码并在仓库根目录启动：

```powershell
git clone https://github.com/linrenhao0813-cmd/ECL.git
cd ECL
.\gradlew.bat run
```

首次构建会通过 Gradle Wrapper 下载 Gradle 8.14.4 与项目依赖。运行启动器、登录、下载游戏或在线内容时也需要网络连接。

## 环境要求

- JDK 21
- 项目已包含 Gradle Wrapper，无需另行安装 Gradle
- Windows

若 Windows 中 `java` 不在 `PATH`，请在同一 PowerShell 会话中配置 JDK 21 的 `JAVA_HOME` 和 `PATH`。

## 图形界面使用

1. 在首页选择 Minecraft 版本与账户，必要时安装加载器。
2. 按需设置游戏目录、Java 路径、内存、JVM 参数、分辨率和直连服务器地址。选择实例后打开高级设置，Java、内存和 JVM 参数会写入该实例自己的启动配置；未选择实例时保存的全局值会作为新实例默认值。
3. 在“内容库”中检索并安装模组、光影包、资源包或整合包；搜索结果会按当前实例的 Minecraft 版本与加载器过滤。Mod 更新页面支持多选顺序更新；“整合包更新”页面用于检查和更新已记录来源的 Modrinth 整合包。
4. 在“服务器”页选择公开服务器，可复制地址或设置为下次启动时的直连地址。
5. 在首页或账号设置中选择“上传皮肤”：Microsoft 正版账户会上传到 Minecraft 官方皮肤服务；离线账户会导入到本地并随游戏启动自动注入。两种方式均支持 64×64 或 64×32 的 PNG 文件（最大 1 MiB），可选择经典（宽手臂）或纤细（细手臂）模型。

离线皮肤与玩家名（含大小写）绑定；更改玩家名后需要重新导入。离线皮肤仅保存在本机 ECL 数据目录，可随时通过“清除皮肤”移除。

CurseForge 官方 API 需要 API Key。可在应用的高级设置中填写，或通过环境变量 `CURSEFORGE_API_KEY`、JVM 参数 `-Decl.curseforge.apiKey=...` 提供。未配置时，Modrinth 功能仍可正常使用。

公开服务器目录来自第三方服务。目录收录不表示 ECL、Mojang 或 Microsoft 对服务器内容、安全性或运营方式的认可，请自行判断后再连接。

## 构建与验证

运行测试和静态检查：

```powershell
.\gradlew.bat check
```

`check` 会运行 JUnit、Checkstyle、SpotBugs 和 JaCoCo 报告任务。
核心模块和 GUI 模块还会执行 JaCoCo 行覆盖率与分支覆盖率下限校验；Gradle 依赖使用锁定和校验元数据。

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

GitHub Actions 会在 Windows 上执行 `check`，并生成 Windows 应用镜像作为工作流产物。

### 界面快照

需要对启动器首页进行视觉检查时，可生成固定状态的 JavaFX 界面快照：

```powershell
.\gradlew.bat captureLauncherUi
```

默认输出为 `ecl-gui/build/visual-qa/ecl-home.png`。该任务使用隔离的数据目录，不会读取或修改当前用户的 ECL 配置。

### 原生应用镜像

以下任务使用 Windows JDK 21 自带的 `jpackage.exe`，并会先构建分发目录：

```powershell
.\gradlew.bat packageWindowsApp
```

输出位置：

```text
dist/windows/ECL/ECL.exe
```

该任务生成的是包含 Java 运行时的 Windows 应用镜像，不是单文件安装程序。发布或复制时需要保留整个 `dist/windows/ECL/` 目录；`ECL.exe`、`app/` 和 `runtime/` 必须位于原有相对位置，不能只分发 EXE 文件。

## 数据目录

启动器数据默认保存于：

| 系统 | ECL 数据目录 | 默认游戏目录 |
| --- | --- | --- |
| Windows | `%APPDATA%\.ecl` | `%APPDATA%\.minecraft` |

该目录会保存版本元数据、库、资源、运行时、配置、备份和诊断文件。游戏目录可在设置中覆盖；默认情况下，带加载器的实例使用隔离运行目录，原版实例共享游戏根目录。实例目录内部的 `.ecl/config/` 保存实例设置，`.ecl/operations/` 保存已通过操作协调器执行的持久化操作日志。

## 项目结构

```text
ecl-boot/  JavaFX 图形启动入口
ecl-core/  认证、下载、游戏启动、实例、整合包与基础服务
ecl-gui/   JavaFX 界面、样式与资源
ecl-dist/  jpackage 打包任务
config/    Checkstyle 与 SpotBugs 配置
```

## 技术栈

- Java 21、JavaFX 21、Gradle 8.14.4
- Gson、Jackson、JNA、TwelveMonkeys ImageIO
- SLF4J 与 Logback
- JUnit 5、Checkstyle、SpotBugs、JaCoCo

## 许可证

本项目使用 [GNU General Public License v3.0](LICENSE) 许可证。详见 [LICENSE](LICENSE)。
