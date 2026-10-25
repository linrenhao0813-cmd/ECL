# ECL 审核问题修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复审核报告中经核验成立的所有问题，不损坏用户数据，并用新的回归与完整构建证据验收。

**Architecture:** 保留现有 core/gui 分层及服务接口，按账户、整合包事务、恢复、下载校验、界面工作流分批修复。升级独占必须贯穿快照与修改，显式携带所有权，不把线程绑定锁跨异步线程释放。每一批先用最小回归重现，再修根因。

**Tech Stack:** Java 21、JavaFX 21、Gradle Wrapper、JUnit、Monocle、现有本地 HTTP 测试夹具。

**Spec:** [审核修复范围与设计](../specs/2026-10-24-review-remediation-design.md)；原始来源为用户本轮完整审核报告。

## Global Constraints

- Java 21；不引入新第三方依赖，不提交或推送代码。
- UTF-8、四空格 Java 缩进、LF；Checkstyle 行长不超过 160、方法不超过 180 行、圈复杂度不超过 25。
- core 覆盖率下限行 60%、分支 40%，GUI 行 20%、分支 15%。
- 不读写真实 APPDATA；测试使用 TempDir 或现有隔离 headless 数据目录。
- 三语文案一起维护，锁/临时文件在同步拒绝、失败、取消时都释放。
- 每批执行顺序固定：新增回归 → 运行并确认预期失败 → 最小生产修复 → 运行同组测试 → 检查差异；下列勾选项分别记录这些动作。
- 用户已以“开始修复”授权；按明确文件所有权分组执行，证据与裁决见同目录 `2026-10-24-remediation-execution.md`。

## Review Focus

1. 同一个损坏账户库在首页、账户页、旧库迁移三个入口都不被空列表覆盖：任务 1。
2. 两个启动器竞争同一实例，与同一升级内部嵌套事务不同；不得以全局文件锁重入绕过竞争：任务 5。
3. 路径校验要覆盖普通模组模式，不只整合包模式的 `mods/../saves`：任务 3。
4. 单线程/饱和执行器下不能因阻塞等待未来任务而死锁，清理回调也不能再次依赖已关闭执行器：任务 5、6、10。
5. 待发布恢复点存在而 active 已旧或缺失时，直接 restore 与 inspect 后 restore 必须一致：任务 4。

## 执行环境

PowerShell 每次调用显式设置 JDK：

```powershell
$env:JAVA_HOME='C:\Users\ASUS\.jdks\ms-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

基线核验：`main @ 228efa3`，开始时工作树干净；JDK 21.0.10 可用。尚未运行本轮 Gradle 测试。

下列文件块均为仓库根目录相对路径；同名测试放在相同 Java 包下的 `src/test/java`。

### Task 1: 账户读取降级与敏感文本

**Files:**
```text
ecl-core/src/main/java/com/ecl/auth/MicrosoftAccountStore.java
ecl-core/src/main/java/com/ecl/auth/MicrosoftAuth.java
ecl-core/src/main/java/com/ecl/auth/OfflineSkinStore.java
ecl-gui/src/main/java/com/ecl/ui/LauncherLaunchForm.java
ecl-core/src/test/java/com/ecl/auth/MicrosoftAccountStoreTest.java
ecl-core/src/test/java/com/ecl/auth/DefaultAccountServiceTest.java
ecl-core/src/test/java/com/ecl/auth/MicrosoftAuthTest.java
ecl-core/src/test/java/com/ecl/auth/OfflineSkinStoreTest.java
ecl-gui/src/test/java/com/ecl/ui/LauncherUiHeadlessTest.java
```
**Interfaces:** 保留底层 `DefaultAccountService.read` 对坏库的拒绝；GUI 建立可见错误状态，不把 AuthException 全局吞掉。新错误文案放入对应三语资源。

- [ ] 新增 `malformedAccountsDoNotPreventHomeConstruction`、`failedAccountReadDoesNotAllowOverwrite`、`cachedSessionRedactsTokens`、`corruptSkinIndexIsPreservedBeforeImport`，断言坏库字节不变、GUI 可建且有提示、秘密不在 toString、既有损坏备份不覆盖。
- [ ] 运行上述四组 core/GUI 定向测试确认红灯，不更改原有底层拒绝写测试来掩盖问题。
- [ ] 在 GUI 读取边界降级；补账户页/迁移同类入口；敏感 record 输出脱敏；皮肤写入前备份损坏索引，备份失败不写。
- [ ] 重跑同组测试并检查测试未生成真实账号文件。

### Task 2: 整合包事务运行保护及精确检测

**Files:**
```text
ecl-core/src/main/java/com/ecl/modrinth/pack/PackUpdateTransaction.java
ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackInstaller.java
ecl-core/src/main/java/com/ecl/modrinth/pack/DefaultModpackUpdateService.java
ecl-core/src/test/java/com/ecl/modrinth/pack/PackUpdateTransactionTest.java
ecl-core/src/test/java/com/ecl/modrinth/pack/MrpackInstallerTest.java
ecl-core/src/test/java/com/ecl/modrinth/pack/DefaultModpackUpdateServiceTest.java
```
**Interfaces:** `hasIncompleteTransactions(Path)` 保持只读；只匹配 `<实例名>-<规范 UUID>`，完整 APPLIED 不算 incomplete，其他匹配异常日志保守阻止。

- [ ] 新增 `persistentGameMarkerRejectsTransactionAndRecovery`、`persistentGameMarkerRejectsPrepareAndApply`、`appliedJournalDoesNotBlockPreview`、`similarlyNamedInstanceDoesNotBlockPreview`，用当前测试进程合法 marker 而非仅伪造内存谓词。
- [ ] 分别运行 PackUpdateTransactionTest/MrpackInstallerTest/DefaultModpackUpdateServiceTest 并记录失败。
- [ ] 取得 lease 后检查 marker，异常确保释放；检测复用 UUID 精确匹配并校验 journal 状态/形状，不能无条件信任仅含 APPLIED 的损坏 JSON。
- [ ] 重跑三组测试，验证 marker 存在时实例及 journal 字节不变。

### Task 3: 统一托管路径与暂存完整性

**Files:**
```text
ecl-core/src/main/java/com/ecl/modrinth/pack/PackManifest.java
ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackInstallSupport.java
ecl-core/src/main/java/com/ecl/modrinth/pack/MrpackInstaller.java
ecl-core/src/main/java/com/ecl/modrinth/service/InstanceUpgradeRecoveryService.java
ecl-core/src/main/java/com/ecl/util/ManagedPackPathPolicy.java (new)
ecl-core/src/test/java/com/ecl/util/ManagedPackPathPolicyTest.java (new)
ecl-core/src/test/java/com/ecl/modrinth/pack/MrpackInstallerTest.java
ecl-core/src/test/java/com/ecl/modrinth/service/InstanceUpgradeRecoveryServiceTest.java
```
**Interfaces:** 新增 `ManagedPackPathPolicy.requireManagedRelativePath(String): Path throws IOException`，返回规范相对路径；拒绝任何 `..` 段和保留首段，不做 CWD 文件系统检查。通用文件作用域校验与托管用户数据排除分开。

- [ ] 参数测试拒绝 `mods/../saves/x`、`./saves/x`、`a/../.ecl/config/launch-profile.json`、反斜杠/盘符/空路径；普通模组恢复也拒绝 `mods/../logs/x`。断言实例文件不变。
- [ ] 新增 `legacyUpdateRejectsMissingOrCorruptManifest`、`preparedUpdateRejectsTamperedFileAndManifest`；不仅篡改 packVersion，还修改一个 JAR 字节及其清单内容。
- [ ] 运行新测试，观察预期失败后再引入统一策略；Manifest 读取不再接受消掉 `..` 的别名，旧入口不再空清单继续，应用对暂存 SHA-512 和预览固定目标集合复核。
- [ ] 重跑 PathPolicy、PackTransactionPaths、MrpackInstaller、Recovery 全组。

### Task 4: 恢复收尾、持久化与崩溃窗口

**Files:**
```text
ecl-core/src/main/java/com/ecl/modrinth/service/InstanceUpgradeRecoveryService.java
ecl-core/src/test/java/com/ecl/modrinth/service/InstanceUpgradeRecoveryServiceTest.java
```
**Interfaces:** `inspect` 和 `restore` 在相同锁顺序中调用同一恢复前置逻辑；不递归获取非重入文件锁。保留现有 State/manifest 字段。

- [ ] 增加 `directRestoreFinalizesPendingBeforeChoosingActive`、`restoreResumesRestoringEntries`、`recoversInterruptedActivePreviousSwap`、`invalidPendingPreservesBothRestorePoints`、`snapshotFailurePreservesPreviousPoint`，用真实 before/after 文件。
- [ ] 运行测试确认失败；增加轻量文件操作故障注入点仅用于可重复崩溃窗口，不能通过直接调用私有实现伪造通过。
- [ ] payload force(true)，manifest 临时文件 force(true) 后原子替换；新点读回/摘要通过再删除 previous。恢复目标创建父目录之前及之后检查祖先。
- [ ] 重跑恢复与备份相关测试；文档明确 Windows 目录 fsync 局限。

### Task 5: 升级全生命周期独占与过期策略

**Files:**
```text
ecl-core/src/main/java/com/ecl/modrinth/service/InstanceUpdateService.java
ecl-core/src/main/java/com/ecl/modrinth/service/InstanceUpgradeRecoveryService.java
ecl-core/src/main/java/com/ecl/operation/InstanceOperationCoordinator.java
ecl-core/src/main/java/com/ecl/util/InstanceOperationLease.java
ecl-core/src/main/java/com/ecl/launcher/LoaderUpdateService.java
ecl-core/src/main/java/com/ecl/modrinth/service/ModUpdateService.java
ecl-core/src/main/java/com/ecl/modrinth/service/DefaultModUpdateService.java
ecl-core/src/main/java/com/ecl/modrinth/service/ModInstallationService.java
ecl-core/src/main/java/com/ecl/modrinth/pack/ModpackUpdateService.java
ecl-core/src/main/java/com/ecl/modrinth/pack/DefaultModpackUpdateService.java
ecl-core/src/main/java/com/ecl/modrinth/transaction/FileModInstallationTransaction.java
ecl-core/src/main/java/com/ecl/modrinth/pack/PackUpdateTransaction.java
ecl-core/src/test/java/com/ecl/modrinth/service/InstanceUpdateServiceTest.java
ecl-core/src/test/java/com/ecl/operation/InstanceOperationCoordinatorTest.java
```
**Interfaces:** 依照[升级实例锁所有权子计划](2026-10-24-upgrade-lock-ownership.md)定义携带实例身份/根目录/活动状态的显式 mutation session，协调器和事务提供受控借用入口；旧入口仍独立获取锁。不能把现有 ReentrantLock 的 AutoCloseable 直接从 future 完成线程 close，也不能在等待的 executor 中递归 join 同池任务。

- [ ] 新增 `executeExcludesCompetingMutationsUntilSnapshotComplete`、`nestedUpdateCanBorrowItsOwnSessionOnly`、`asyncCompletionReleasesSessionAfterFailure`、`singleThreadExecutorDoesNotDeadlock`；覆盖真实模组更新和失败后的回退点。
- [ ] 注入可控 Clock 或接受时间戳，测试预览未接受超时拒绝、已接受准备超过 10 分钟仍执行、指纹变化仍拒绝。
- [ ] 运行相关测试重现后按明确所有权改造整个调用链；固定锁获取顺序，对所有同步/异步退出路径一次性收尾。
- [ ] 重跑全部 modrinth、LoaderUpdateService 和 operation 测试，增加跨进程 lease 竞争验证，不只用同线程可重入锁测试。

### Task 6: 启动与升级忙状态收尾

**Files:**
```text
ecl-gui/src/main/java/com/ecl/ui/GameLaunchPreparation.java
ecl-gui/src/main/java/com/ecl/ui/InstanceUpdateWorkflow.java
ecl-core/src/main/java/com/ecl/download/DownloadTaskCenter.java
ecl-gui/src/test/java/com/ecl/ui/LauncherTaskLifecycleTest.java
ecl-gui/src/test/java/com/ecl/ui/InstanceUpdateWorkflowTest.java (new)
ecl-core/src/test/java/com/ecl/download/DownloadTaskCenterTest.java
```
**Interfaces:** 完成路径按任务身份/generation 清理；不得让旧回调清空新任务。TaskCenter 同步提交失败也使任务进入终态。

- [ ] 测试 closed center、拒绝执行器、preview/prepare/execute 各自同步抛错、取消排队任务、确认弹窗取消、旧 generation 晚到；断言 busy/preparing 清除、controls 复原、prepared close 一次且可再次发起。
- [ ] 运行红灯后在每个提交边界捕获同步异常，core execute 同步拒绝也释放 execution；收尾不再依赖可能被拒绝的 handleAsync。
- [ ] 用可控 futures 直接跑真实工作流状态机，两轮确认及依赖展示不能只覆盖测试专用适配器。
- [ ] 重跑生命周期、维护工作流与 TaskCenter 测试。

### Task 7: 校验/修复信任边界与自定义根

**Files:**
```text
ecl-core/src/main/java/com/ecl/download/GameInstallationVerifier.java
ecl-core/src/main/java/com/ecl/download/GameInstallationRepairService.java
ecl-core/src/main/java/com/ecl/download/GameDownloader.java
ecl-gui/src/main/java/com/ecl/ui/InstanceCheckRepairWorkflow.java
ecl-core/src/test/java/com/ecl/download/GameInstallationVerifierTest.java
ecl-core/src/test/java/com/ecl/download/GameInstallationRepairServiceTest.java
ecl-gui/src/test/java/com/ecl/ui/InstanceMaintenanceWorkflowTest.java
```
**Interfaces:** 报告/服务提供单一 `canRepair` 判断给 UI；下载器根目录通过现有构造参数扩展注入，默认构造保持兼容。无摘要依赖不自动抓 .sha1 修复；完整报告仍诚实保留 unverifiable。

- [ ] 用真实 Fabric/Quilt `name+url` 无 downloads 夹具，测试存在/缺失均无自动修复；混合有摘要与无摘要依赖只下载前者。
- [ ] 测试自定义 versions/libraries/assets 与默认目录完全不同，修复只写注入目标；hint:local 按实例路径检查且不修到共享目录。
- [ ] 测试损坏自定义 metadata 不显示不可执行修复按钮；中途下载失败返回 after 与 failed，保留原有效完成标记策略。
- [ ] 运行红灯后实现最小修复，重跑 verifier/repair/downloader 与 GUI 维护测试。

### Task 8: 下载竞争、断点及临时清理

**Files:**
```text
ecl-core/src/main/java/com/ecl/download/GameDownloadBatchExecutor.java
ecl-core/src/main/java/com/ecl/game/ClientLoggingConfig.java
ecl-core/src/main/java/com/ecl/util/ManagedLockPaths.java
ecl-core/src/test/java/com/ecl/download/GameDownloadBatchExecutorTest.java
ecl-core/src/test/java/com/ecl/game/ClientLoggingConfigTest.java
```
**Interfaces:** 发布锁最长等待 30 秒（实现选择），可中断；取得锁后重验目标，时间到仍失败且保留旧目标。两份相同目标摘要的 size=-1/已知大小可合并。

- [ ] 用 latch 控制并发发布，断言竞争者只复用正确文件，不取消整批；分别覆盖等待取消、超时、竞争者发布损坏内容。
- [ ] 测试相同 SHA1 且 size=-1/known 合并、两个 known size 冲突；Windows Foo/foo 应同一目标，若已成立只补回归不改实现。
- [ ] 本地 HTTP 测试日志下载失败/取消无随机临时 part/meta 残留；超过 16 MiB 的声明下载前拒绝。
- [ ] 运行红灯后实现等待/重验及清理；旧 target.part 保留且文档说明不复用，无证据不迁移续传。
- [ ] 重跑批下载、HTTP、日志配置和任务取消测试。

### Task 9: 元数据继承与旧版资源

**Files:**
```text
ecl-core/src/main/java/com/ecl/game/VersionRepository.java
ecl-core/src/main/java/com/ecl/game/VersionMetadata.java
ecl-core/src/main/java/com/ecl/download/LegacyAssetMaterializer.java
ecl-core/src/test/java/com/ecl/game/VersionRepositoryTest.java
ecl-core/src/test/java/com/ecl/download/LegacyAssetMaterializerTest.java
ecl-core/src/test/java/com/ecl/launch/LaunchCommandBuilderTest.java
```
**Interfaces:** 检查报告将 logging 错误隔离为 LOGGING UNVERIFIABLE 并继续其它检查，严格 resolve/启动仍 fail-closed，不能静默丢掉安全日志参数。继承元信息与调用方约定一致；旧 Forge/LWJGL 父 classifiers 兼容继承保持不变。

- [ ] 测试无效 logging 不压掉客户端/库/资源检查，单独显示 LOGGING UNVERIFIABLE 且不可修复；严格 parse/启动仍拒绝不安全声明，没有日志文件请求。
- [ ] 保留并运行 legacyForgeProfileKeepsParentPlatformNativeLibraries、bareChildCoordinateUsesItsOwnArtifactAndKeepsParentNatives；补 typedMetadataPreservesDirectParentInMultiLevelChain、baseMetadataHasNoDirectParent，不把 inheritsFrom 加回扁平 raw JSON 导致重复解析。
- [ ] 测试同大小错误资产对象在物化之前被拒绝，原 virtual/resources 文件不变。
- [ ] 运行红灯后修复解析/继承/哈希检查，重跑版本解析与启动命令、旧资源测试。

### Task 10: 存档目标、服务器停止与 FX 响应

**Files:**
```text
ecl-gui/src/main/java/com/ecl/ui/WorldSavesPage.java
ecl-gui/src/main/java/com/ecl/ui/BackupManagerDialog.java
ecl-gui/src/main/java/com/ecl/ui/ServerManagementPage.java
ecl-gui/src/main/java/com/ecl/ui/InstanceLaunchProfileDialog.java
ecl-gui/src/test/java/com/ecl/ui/WorldSaveExplorerTest.java
ecl-gui/src/test/java/com/ecl/ui/ServerManagementPageTest.java
ecl-gui/src/test/java/com/ecl/ui/InstanceSettingsNavigationTest.java
```
**Interfaces:** 备份弹窗构造/打开入口显式接收目标 profileId；服务器停止保留 StopOutcome 并使用不会回落 FX 的后台执行策略；弹窗读取/保存异步且有关闭/generation 防护。

- [ ] 测试启动目标 A、浏览 B，备份创建/恢复仅针对 B，弹窗打开后切换全局目标也不改 B。
- [ ] 测试 STOPPED/TIMED_OUT 不同可见文案，超时不清除运行状态；饱和执行器下 FX pulse 仍可运行。
- [ ] 测试 Java 探测、锁竞争、备份列表均非 FX，关闭后无晚到控件更新，ESC 关闭且焦点返回。
- [ ] 运行红灯后实现最小异步化和目标注入，重跑 GUI 全组相关测试。

### Task 11: 安全兼容、依赖选择及小 UX

**Files:**
```text
ecl-core/src/main/java/com/ecl/desktop/DesktopShortcutService.java
ecl-core/src/main/java/com/ecl/util/OperatingSystem.java
ecl-core/src/main/java/com/ecl/modrinth/service/DefaultModDependencyResolver.java
ecl-core/src/main/java/com/ecl/config/SettingsManager.java
ecl-gui/src/main/java/com/ecl/ui/InstanceDetailsPane.java
ecl-gui/src/main/java/com/ecl/ui/InstanceCheckRepairWorkflow.java
ecl-core/src/test/java/com/ecl/desktop/DesktopShortcutServiceTest.java
ecl-core/src/test/java/com/ecl/util/OperatingSystemTest.java
ecl-core/src/test/java/com/ecl/modrinth/service/ModDependencyResolverTest.java
ecl-gui/src/test/java/com/ecl/ui/InstanceDetailsPaneTest.java
```
**Interfaces:** 不改变用户启动目标语义；未知 OS 拒绝不支持的下载；版本级可选选择映射到真实 projectId。

- [ ] 测试 Unicode/空格路径脚本回退、unknown OS 一致行为、selected optional versionId 失败不被 warning 吞掉。
- [ ] 测试删除双击只产生一个任务、Turkish locale 文案键正确、关闭检查报告不再重复显示完成模态；运行红灯。
- [ ] 修复上述行为，删除无人读取的设置迁移；恢复入口保留可进入检查/收尾，文档不声称按钮必须先知道 active 是否存在。
- [ ] 重跑对应 core/GUI 测试。

### Task 12: 测试覆盖、文档和最终验证

**Files:**
```text
ecl-core/src/test/java/com/ecl/modrinth/pack/MrpackFileInstallerTest.java
ecl-core/src/test/java/com/ecl/modrinth/pack/MrpackOverrideExtractorTest.java
ecl-core/src/test/java/com/ecl/server/LocalServerManagerTest.java
ecl-core/src/test/java/com/ecl/download/GameDownloaderTest.java
ecl-gui/src/test/java/com/ecl/ui/InstanceInstallPageTest.java
README.md
docs/architecture.md
docs/codebase-guide.md
docs/data-formats.md
docs/images/README.md
```
**Interfaces:** 无新产品接口。仅在全仓无引用后删除 5 参 DownloadTask 构造器及 GameAssetVerifier 无用方法。logback 依赖先核实 Gradle 与继承需求，不为 implementation 等同 api 的错误假设改动模块。

- [ ] 补 files[].path 逃逸、条目数、大小、压缩比、链接条目和 junction 夹具；记录无法获得文件 symlink 权限的真实跳过，不伪称覆盖。
- [ ] 下载测试数据统一 TempDir；GUI 安装测试以可见性、布局关系/容器边界语义取代脆弱像素值。
- [ ] 同步三语键集及所有行为文档，删除 reduceMotion 和 versionCategory2 的旧说法，补断点兼容、恢复持久化及独占所有权说明。
- [ ] 执行 ` .\gradlew.bat check --rerun-tasks `（去掉两侧空格），确认 BUILD SUCCESSFUL，读取本轮 JUnit/JaCoCo/Checkstyle/SpotBugs 实际报告。
- [ ] 执行快照：home、local-versions、saves-details、backups-en、local-servers；逐张 read_image 核对，更新快照模式记录；必要时补 zh-TW。若 README 展示布局受影响，重拍对应三张正式 PNG。
- [ ] 独立评审本轮差异与安全边界，发现新问题先复现再修复；最后再运行相关测试。
- [ ] 检查 git status、完整 git diff 和 git diff --check，排除生成文件、临时诊断、账号/token、无关改动；输出逐项修复/不成立/环境未测清单。

## 执行建议

推荐 Native：在本会话按上述批次实施，每批定向回归，最终一次独立评审。主要原因是任务 2–5 共用事务与锁，任务 6–7 共用异步收尾边界，盲目并行改源码容易造成锁语义冲突。账户、独立兼容项可在明确文件所有权后单独委派，但不需要六个代理同时修改同一工作树。

已按用户授权开始实施；任务 5 已补[锁会话子计划](2026-10-24-upgrade-lock-ownership.md)，执行时先按其接口核对事务修复，避免嵌套重复取锁与恢复锁顺序反转。
