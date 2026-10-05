# 源码阅读指南

## 从入口开始

启动链路是 `ECL.main` → `ECLauncher.main` → `LauncherUI`。`LauncherUI` 继承
`LauncherUIView`，后者按服务初始化、启动配置加载、窗口展示和初始状态刷新组织启动过程。
`LauncherWindowLayout` 装配窗口框架、顶栏与页脚；应用服务与后台执行器由 `MainController` 管理。

| 模块 | 职责 | 建议阅读入口 |
| --- | --- | --- |
| `ecl-boot` | 检查 JavaFX 并启动应用 | `com.ecl.ECL` |
| `ecl-gui` | 页面、交互、后台任务与界面的衔接 | `com.ecl.ui.LauncherUIView`、`MainController` |
| `ecl-core` | 认证、下载、启动、实例与内容管理 | 按下表选择对应业务包 |
| `ecl-dist` | Windows 应用打包 | `build.gradle.kts` |

Gradle 依赖方向为 `ecl-boot` → `ecl-gui` → `ecl-core`。定位业务规则时先看核心模块，
定位按钮事件与界面状态时再看 GUI 模块中对应的控制器或工作流。

## 按业务查找

以下路径均相对于 `ecl-core/src/main/java/com/ecl/`。

| 场景 | 入口 | 职责划分 |
| --- | --- | --- |
| 账户与登录 | `auth/DefaultAccountService.java` | 账户服务协调具体认证提供者 |
| 游戏启动 | `launch/DefaultLauncher.java` | 启动流程调用命令构建、原生库解压与进程管理 |
| 游戏版本 | `launcher/VersionManager.java` | 配合版本清单、目录扫描与 profile 解析类 |
| 下载调度 | `download/DownloadTaskCenter.java` | 排队、并发名额、取消、重试与历史保留 |
| 下载执行 | `download/DownloadTaskExecutor.java` | 执行具体下载操作，把结果交回调度器 |
| 游戏版本下载 | `download/GameDownloader.java` | 编排元数据、客户端、依赖库和资源下载，保留取消检查与版本锁 |
| 模组依赖解析 | `modrinth/service/DefaultModDependencyResolver.java` | 区分必需、可选、冲突与内嵌依赖，按依赖先于引用者的顺序生成安装计划 |
| 本地 Mod 扫描 | `modrinth/service/DefaultLocalModScanner.java` | 锁定实例、扫描文件、在线识别、整理记录并保存 |
| 扫描缓存 | `modrinth/service/LocalModScanCache.java` | 读取缓存 JSON，原子替换缓存文件 |
| 整合包安装与更新 | `modrinth/pack/MrpackInstaller.java` | 编排读取、依赖准备、安装与更新 |
| 整合包文件事务 | `modrinth/pack/PackUpdateTransaction.java` | 暂存、日志、提交、回滚与崩溃恢复 |
| 事务目标路径 | `modrinth/pack/PackTransactionPaths.java` | 在实例、profile、版本和依赖库范围内编码、解析目标路径 |

## 阅读关键流程

### 界面启动与窗口装配

从 `LauncherUIView.start` 阅读服务初始化、启动配置加载和窗口展示的顺序，再到
`LauncherWindowLayout` 查找窗口框架、顶栏账户入口、导航和页脚。窗口按钮与拖动行为继续由
`LauncherWindowChrome` 处理；页面切换由 `LauncherPageRouter` 负责，Mod 拖入事件在窗口框架建成后安装。

### 游戏版本下载

`downloadVersionInternal` 在同一版本锁中依次保存元数据、调用 `downloadClient`、下载依赖库和资源，
各阶段之间检查取消，最后写入下载完成标记。`downloadClient` 处理客户端下载、校验和失败清理，
没有客户端下载信息时检查可用的继承版本；`addLibraryArtifact` 与 `addNativeArtifact` 分别整理普通库和原生库。

### 模组依赖解析

从 `resolve` 的兼容性检查进入 `visit`：先检查深度、循环、重复版本和数量限制，再顺序解析各个依赖，
最后将当前版本追加到安装顺序。`handleDependency` 按类型分派，`resolveRequiredDependency` 处理必需依赖，
`resolveOptionalDependency` 区分已选与未选的可选依赖，并保留对应的失败或警告行为。
`ResolutionContext` 保存一次解析的选择、路径校验缓存与结果集合，各次解析互不共享这些状态。

### 下载任务结束

`finishSuccess`、`finishFailure`、`finishCancelled` 都进入 `finish`。该方法在调度器锁内
确定最终状态、释放并发名额并裁剪历史；锁外完成 future、通知监听器并调度后续任务。
取消请求优先于操作返回的成功或失败。任务仍在取消中时继续占用并发名额，直到执行器报告结束。

### 扫描本地 Mod

`scanBlocking` 保留流程编排：获取实例锁 → 准备目录 → 读取旧记录与缓存 → 扫描 → 在线识别
→ 整理记录 → 保存索引与缓存。进一步追踪时：

- `scanFiles` 枚举启用和禁用目录，`scanFile` 处理单个文件的哈希与 JAR 元数据。
- `recognizeFiles` 封装在线查询及失败时的本地回退。
- `reconcileRecords` 整理已识别、未知、损坏、缺失文件，并生成重复项目警告。
- `LocalModScanCache` 保持 `launcher-mod-scan.json` 的读写格式，损坏缓存按空缓存处理。

### 整合包事务

先从 `stageReplacement`、`stageDeletion` 和 `commit` 阅读正常路径，再看 `rollbackEntries`
和 `recoverIncompleteTransactions`。日志继续使用 `PREPARED`、`APPLYING`、`APPLIED` 状态。
`PackTransactionPaths` 集中处理目标范围与日志路径解析；文件移动、锁和回滚仍由事务类负责。

## 修改后的验证

需要 JDK 21。在仓库根目录运行：

```powershell
# 核心模块测试
.\gradlew.bat :ecl-core:test

# 全项目测试、Checkstyle、SpotBugs 与覆盖率门槛
.\gradlew.bat check

# 生成包含各模块 JAR 的分发目录
.\gradlew.bat installDist
```

核心测试位于 `ecl-core/src/test/java/`，GUI 测试位于 `ecl-gui/src/test/java/`。
新增职责默认使用包级可见的辅助类；只有外部调用方需要时才扩展公共 API。
拆分方法时保留异常传播、锁范围、回调时机和持久化格式，并用对应行为测试验证。
