P1 高优先级问题

 1. MRPACK 更新可通过符号链接越界写文件
    ecl-core/src/main/java/com/ecl/modrinth/pack/PackManifest.java:141-149
    MrpackInstallSupport.java:18-21
    PackUpdateTransaction.java:179-190
    仅进行字符串路径校验，若 mods 等目录是指向外部目录的符号链接，更新可写入实例目录之外。
    修复： 拒绝符号链接路径组件，并使用真实路径或安全目录流进行写入。

 2. 下载 URL 存在 SSRF 风险，重定向可绕过主机白名单
    ModrinthDownloader.java:175-176
    ResumableFileDownloader.java:126-136
    允许元数据指定环回地址；MRPACK 下载重定向后只重新检查 HTTPS/环回协议，未重新检查可信主机。
    修复： 下载内容仅允许 HTTPS 和明确白名单主机；每次重定向都重新校验，拒绝私网、环回和链路本地地址。

 3. 内容安装绕过事务机制，依赖失败会留下半安装状态
    ecl-gui/src/main/java/com/ecl/ui/ContentDownloadWorkflow.java:124-133
    ecl-core/src/main/java/com/ecl/modrinth/download/ModrinthDownloader.java:178-212
    文件直接写入实际 mods 目录，后续依赖下载失败时无法回滚，也可能覆盖同名用户文件。
    修复： 所有文件先写入 staging，完整校验后通过 ModInstallationService 事务提交。

 4. 同一实例允许重复启动，运行状态会被提前清除
    GameLaunchCoordinator.java:114-121,170-176
    GameProcessMonitor.java:65-71
    启动后立即解除控件锁；多个进程共享一个布尔运行状态，任一进程退出都会清除状态。
    修复： 为实例建立原子 single-flight 锁，或按进程引用计数。

 5. MRPACK 更新锁无法阻止跨进程并发更新
    PackUpdateTransaction.java:69-79
    每次事务锁定自己的 UUID 目录，而不是实例稳定锁。两个进程仍可同时更新同一实例。
    修复： 锁定永久的实例级标记文件。

 6. 非 Windows 加密密钥没有用户秘密保护
    CryptoKeyStore.java:98-109,158-162
    密钥完全由用户名、路径、系统信息、机器 ID/MAC/主机名派生。获取数据目录后可重建包装密钥。
    修复： 使用系统密钥环；不可用时要求用户密码，或明确将其视为弱保护并强化权限控制。

 7. 备份临时文件名固定，跨进程创建备份会互相覆盖
    WorldBackupService.java:73-81
    WorldBackupMetadata.java:46-52
    同一 profile 同秒创建备份时会共用 .part 文件。
    修复： 使用 Files.createTempFile，并加 profile 级跨进程锁。

 8. 下载中心重试绕过原始工作流的忙状态和取消管理
    DownloadTaskCenter.java:237-246
    DownloadTasksPage.java:207-211
    重试只复用操作工厂，GUI 锁定、监听器和进度初始化在工厂外完成，可能导致监听器覆盖、错误取消或并发下载。
    修复： 将每次尝试的初始化、清理和 single-flight 控制放入操作工厂。

 9. 关闭内容下载窗口不会取消后台安装
    LauncherContentBrowser.java:260-273
    ContentDownloadWorkflow.java:119-245
    窗口关闭后只使回调失效，后台任务仍继续写文件，且启动控件被重新启用。
    修复： 保存并取消任务句柄，或持续持有实例操作锁直到任务结束。

 10. 运行中的世界仍可被 GUI 修改
     WorldSavesPage.java:247-279
     WorldSaveService.java:54-72
     没有检查实例运行状态，可能与 Minecraft 同时改写 level.dat。
     修复： 运行期间禁用保存，并在实际写入前再次检查。

 11. “Open to LAN”和端口设置只保存，不会被消费
     WorldSavesPage.java:160-169,249-279
     WorldSaveService.java:131-144
     全项目搜索未发现启动或游戏运行时读取这两个设置。
     修复： 实现启动时注入逻辑，或暂时移除这些控件。

 12. 资源验证标记会永久跳过资源哈希校验
     GameDownloader.java:272-291
     GameAssetVerifier.java:26-30,54-58
     只要资源索引 SHA-1 未变，之后被篡改的资源只检查“文件存在”。
     修复： 启用校验模式时始终校验对象哈希，或保存与文件状态绑定的验证记录。

 13. 事务 journal 未强制落盘，断电后可能丢失恢复信息
     PackUpdateTransaction.java:133-141,293-300,327-335
     文件写入和重命名未 force(true)，断电可能出现文件已移动但 journal 不完整。
     修复： 使用 FileChannel.force(true)，并同步父目录；无 journal 时不要直接删除可能包含回滚数据的目录。

 P2 中优先级问题

 - 运行时摘要显示全局配置，而实际启动使用实例配置
   RuntimeSummaryPresenter.java:20-35
   GameLaunchCoordinator.java:71-101
   用户看到的 Java/内存可能与真正启动参数不一致。

 - 进程退出后立即分析日志，可能遗漏最后输出
   GameProcessMonitor.java:46-53
   应等待 ProcessOutputPump 完成，并使用 gameProcess.capturedOutput()。

 - 崩溃诊断可能打开错误实例的 mods 目录
   GameLaunchCoordinator.java:190-192
   应传递发生崩溃的固定版本或启动目录，而不是读取当前选择。

 - 版本列表恢复与刷新存在竞态
   VersionActions.java:241-299
   两个异步任务可能后完成者覆盖较新的版本列表。

 - 世界存档扫描和 NBT 解析运行在 JavaFX 线程
   WorldSavesPage.java:174-179
   大型游戏目录会导致界面冻结。

 - GUI 新拆分类缺乏行为测试
   build.gradle.kts:52-65
   GUI 覆盖率门槛仅 6%/10%，无法有效保护新提取的启动、下载和认证流程。

 - GitHub Actions 使用可变 tag
   .github/workflows/ci.yml:23-52
   建议固定到完整 commit SHA。

 - SpotBugs 表示暴露规则被全局抑制
   config/spotbugs/exclude.xml:18-21
   应缩小到明确 DTO 或具体类
