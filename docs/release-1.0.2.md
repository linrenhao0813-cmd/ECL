# 1.0.2 候选发布与验收

状态：保留 `1.0.2beta`。真实 Windows 分发包、Microsoft 账号和游戏世界验收完成前，不发布正式版。
差异基线为 `V1.0.1`，本次工作的起点为 `8442be36383e6e587176987d4a54bfb013f03388`。
2026-10-06 已确认下述功能移除符合发布意图。

## 发布说明草案

### 新增与体验变化

- 新增实例工作区、自定义名称、收藏与封面；查看实例与下一次启动目标相互独立。
- 新增一键升级：普通模组实例升级兼容加载器和可识别模组；Modrinth 整合包跟随作者整包版本更新。
  遵循发布通道，不跨 Minecraft 版本或加载器类型，保留存档和启动配置。
- 安装时显式选择加载器版本；Fabric 同时选择 Fabric API 版本。
- 新增本地服务端 JAR 导入、独立 Java/内存配置、EULA 确认、启动/正常停止、日志与控制台命令。
- 使用森林深色界面，重组下载分类、设置和存档浏览；增加账户皮肤头像与明确的重新登录入口。

### 修复

- 修复下载并发、断点续传、低速限流、正文停顿超时和取消/重试后的任务状态。
- 补齐加载器依赖后再切换配置，改善旧版启动参数及 Minecraft 26.x Java/NeoForge 兼容性。
- 防止不同 Mod 项目覆盖同名 JAR，完整维护共享必需依赖与旧索引未知关系。
- 改进跨进程操作保护、整合包恢复范围、覆盖文件优先级和回滚数据保留。
- 保留 NBT 字符串的 Minecraft 编码，防止运行中的游戏被修改存档。
- 修复过期内容搜索结果、中文内容简介以及设置保存/放弃和离页检查。
- 修复配置环境代理时本机服务被错误转发到代理的问题；远程请求继续使用代理。
- 缺少有效摘要或大小的 Mod 下载在地址解析前拒绝，避免等待 DNS 后才报错。

### 移除与兼容性提示

- 移除 CurseForge、Yggdrasil 外置登录、浅色主题、游玩统计和诊断包导出。
  旧 Yggdrasil 账户记录仍保留，不能再用于外置登录。
- 存档设置不再编辑“对局域网开放”与端口；保留难度、游戏模式、允许指令。
- 实例页不再提供独立启动配置编辑；启动仍读取已有配置，新实例默认值不修改已有实例。
- 整合包更新从批量页面迁移到当前启动实例的一键升级；旧包缺少有效 Modrinth 来源时需要重新导入。
- 自定义 JVM 参数拒绝参数文件、外部 agent、bootclasspath 和执行命令类选项；旧参数可能需要调整。
- 本地服务端切页后继续运行，重开可识别旧进程和读取日志，但不能重新连接旧控制台输入。
- 下载任务管理保留在底部任务详情；AI companion 在此版本开发过程中加入后删除，不属于相对基线新增。

存档管理、皮肤上传、世界备份和 Java 自动下载属于已有能力，不列为本次新增。
源码拆分、未使用抽象清理、依赖升级与签名校验维护属于内部变化。

## 自动验收与证据

| 范围 | 当前证据 | 边界 |
| --- | --- | --- |
| 扩展回归 | Linux/JDK 21，101 项测试，零失败、零错误、零跳过 | 包含 3 项新增 V1.0.1 编码/结构兼容测试；不是用户真实数据升级 |
| 设置与实例兼容 | `V101SettingsUpgradeTest`、`V101InstanceUpgradeTest` | 合成旧配置；保留原实例路径、未接入旧字段和 LAN 侧车文件 |
| 账户兼容 | `V101AccountUpgradeTest` | 独立构造旧 AES-GCM/密钥封装编码；Windows 用 DPAPI，Linux 用旧 LOCAL-3；不验证真实账号授权 |
| Mod、任务与服务端 | 共享依赖、覆盖冲突、取消/重试、升级、备份与真实子 JVM 的正常停止回归通过 | HTTP 内容和服务端 JAR 是受控测试夹具，不是真实 Modrinth/游戏服务端验收 |
| 静态检查与分发目录 | 核心主代码/测试和 GUI 测试的 Checkstyle、SpotBugs，以及 `installDist` 通过 | Linux 分发目录不能当成 Windows 应用镜像 |
| 完整 `check` | 已尝试，Linux 下未通过；发现的下载元数据验证顺序问题和新增测试的静态告警已修复并复验 | Windows 可执行路径和非 Windows 密钥保护提供者的环境差异仍需 Windows 完整检查确认 |
| 镜像检查器 | PowerShell 语法、合成镜像正/反例及真实 Windows 镜像检查通过 | 不执行合成 EXE；结构检查独立于启动检查 |
| Windows CI | [#160](https://github.com/linrenhao0813-cmd/ECL/actions/runs/37466351456) 的 verify、dependency-review、package 全部通过 | 验证代码提交 `04bb628255f49409920f43ad21f9b2543e6da236`，后续提交仅补充本记录 |
| Windows EXE 自动启动 | #160 确认真实窗口及包内 JVM，`exe-startup-result.json` 为 PASS | 检查同时观察 jpackage 启动父进程与其同 EXE 子进程；不验证交互或游戏 |

新增 CI 步骤检查 EXE、配置、运行时、类路径、模块版本和入口类；拒绝测试类及测试依赖混入应用。
`windows-image-evidence` 保存 `image-result.json`，包含候选文件 SHA-256，检查范围明确为结构与哈希。
此步骤不证明 EXE 可以启动或游戏流程成功。
随后 `smoke-windows-app.ps1` 使用隔离 APPDATA 启动真实 EXE，等待窗口并核对加载的是候选包内 `jvm.dll`，
最后关闭该测试进程；`exe-startup-result.json` 记录结果。它不验证页面交互、登录或游戏流程。

## Windows 候选包验收入口

先在包含本次修改的 Windows 检出目录运行：

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat packageWindowsApp --no-daemon
.\scripts\test-windows-app-validator.ps1
.\scripts\verify-windows-app.ps1
.\scripts\smoke-windows-app.ps1
.\scripts\verify-runtime.ps1 -AppImagePath .\dist\windows\ECL -CandidateRevision (git rev-parse HEAD)
```

最后一条需要完整 JDK 21、可联网的桌面和拥有 Minecraft Java 版的 Microsoft 账号。
按终端提示进行设备码授权；第一次启动创建一个 `ECL Runtime Test` 单人世界并进入后退出；
第二次启动打开该世界、进入后退出。脚本检查加密账号重载、游戏下载、Fabric 安装与升级、
世界扫描/编辑/备份恢复的 SHA-256，以及升级前后存档和启动配置保持一致。

脚本从候选镜像 `app/` 读取应用 JAR，使用外部 JDK 启动验收程序和 Minecraft。
因此它验证候选应用代码，不替代 `ECL.exe` 与内置运行时的启动验收。
另行启动 `dist/windows/ECL/ECL.exe`，验证页面、账户入口和任务操作。

每次真实运行使用 `build/runtime-validation/<唯一 ID>/` 隔离数据。
记录 `result.properties`、`application-jars.sha256` 和镜像 `image-result.json`。
`harnessRevision` 是验收工具所在检出的提交，`candidateRevision` 是显式传入的候选提交；
来源不同或检出有修改时必须注明，不能把旧提交 CI 当作修改后验证。
不要分享其中的 `appdata/` 或原始日志，它们可能包含真实凭据与世界数据。

## 仍需完成的实际验收

| 项目 | 操作与通过条件 | 状态 |
| --- | --- | --- |
| Windows EXE | CI #160 已验证窗口和包内 JVM；仍需在用户 Windows 会话检查主要页面与交互 | 自动启动已通过，人工交互待执行 |
| Microsoft 与游戏世界 | 运行上述脚本；所有里程碑与最终结果 PASS，升级后进入原世界 | 待执行 |
| 重开保护 | 游戏运行时关闭并重开 ECL，拒绝重复启动、升级、修改存档；游戏退出后恢复操作 | 待执行 |
| Modrinth | 真实安装两个共享依赖项目，更新后依赖者完整；禁用/卸载受关系保护；取消和重试后无卡住状态 | 待执行 |
| 本地服务端 | 导入真实服务端 JAR，缺少 Java 时选择/取消行为正确；确认 EULA 后启动，命令/日志可用，正常停止保存世界 | 待执行 |
| V1.0.1 真实数据升级 | 同一 Windows 用户下复制旧账户、密钥、设置和实例到隔离测试目录；重新指定测试游戏根目录；验证账号重载、实例/存档/启动配置保持 | 待执行 |
| 功能移除 | 已确认移除符合发布意图，发布说明明确列出 | 已确认 |

旧版真实数据只在副本上测试，不将密钥或 token 提交到仓库。
加密账户复制必须包含原密钥，Windows DPAPI 验收在原 Windows 用户下进行。
测试前确认复制后的 `settings.json` 和实例自定义运行目录均指向副本，避免修改原游戏数据。

## 正式发布条件

所有“待执行”项完成后，记录候选提交、镜像哈希、环境、日期和结果；有失败则先修复并重验受影响范围。
再将 `build.gradle.kts` 与 `ECLConfig.LAUNCHER_VERSION` 同步改为 `1.0.2`，更新 README，重新运行 Windows CI。
正式版本会改变应用 JAR 名称，须再次检查最终镜像并启动最终 EXE，证据以正式产物哈希为准。

```powershell
.\scripts\verify-windows-app.ps1 -ExpectedVersion 1.0.2 -ZipPath .\dist\releases\ECL-1.0.2-windows.zip
```

ZIP 保留整个 `ECL/` 目录，包括 `ECL.exe`、`app/`、`runtime/`。
最后在已验收提交建立 `V1.0.2` 标签并发布正式 Release，附 ZIP、SHA-256 和上述发布说明。
当前未修改正式版本号、建立标签或发布 Release。

候选包证据：CI #160 的 `windows-app` 为 75,314,086 字节，GitHub artifact ZIP SHA-256 为
`dd28aed9e0b2a9885568f0177a610788bfe178183042fc2f0a52ba1459569a8f`。
这是 CI artifact ZIP 的摘要，不是将来正式 Release ZIP 的摘要；实际应用文件哈希见同 run 的
`windows-image-evidence/image-result.json`。
