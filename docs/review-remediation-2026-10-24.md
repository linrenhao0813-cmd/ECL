# 2026-10-24 审核整改

基线为 `228efa3`。本轮按用户“开始修复”授权修改工作区，不提交或推送代码。

## 严重问题

| 审核项 | 处理 |
| --- | --- |
| 损坏账户库阻止启动 | GUI 读取边界降级并显示三语错误；底层写入与迁移继续拒绝覆盖坏库，回归核对原字节 |
| 整合包遗漏持久运行标记 | 准备、应用、事务创建和恢复检查真实进程标记，锁内再检查 |
| 未完成事务检测过宽 | 精确匹配实例名加规范 UUID；完整 APPLIED 不阻塞预览，坏日志拒绝恢复 |
| 清单路径别名进入用户目录 | 共用纯词法路径策略，拒绝 `..`、反斜杠、盘符、Windows 尾点/空格别名；排除存档等用户目录 |
| 同步提交失败留下忙状态 | 启动准备和升级各阶段释放忙状态、prepared 与任务所有权；generation 隔离迟到回调 |
| 裸 Maven 被当成可自动修复 | 无声明摘要时存在/缺失均不可自动修复；混合报告只下载可信项，不额外查询 `.sha1` |

## 重要问题与补充修复

- 发布锁改为最多等待 30 秒，可中断；取得后重验目标，复用竞争者发布的正确文件。
- 日志配置限制 16 MiB；随机暂存目标的 part/meta 在失败、取消及完成时清理。
- 修复读取与写入使用相同注入根目录，实际实例目录与元数据目录独立；`hint:local` 遵循实际启动路径。
- UI 复用服务端 `canRepair`；部分下载失败也返回 after 报告。修复不预先删除已存在的完成标记。
- 无效日志声明单独列为无法验证，其余检查继续；正式解析和启动仍拒绝不安全声明，保留日志安全参数。
- 直接 restore 与 inspect 共用 pending/previous 收尾，覆盖交换崩溃、无效 pending、RESTORING 续做与快照失败。
- 升级从最终指纹检查、before 快照到 after 快照持有实例会话；内部事务显式借用，写借用互斥。取消观察 Future 不能跳过内部清理。
- Prepared 整合包固定暂存树和归档摘要，应用前复查；旧的一步更新也拒绝坏旧清单和用户目录覆盖。
- payload 与 manifest 强制刷新，新 active 读回核验后才删除 previous。Windows 目录刷新仍为尽力执行。
- 备份绑定浏览世界所属实例，弹窗生命周期内固定目标；Java 探测、配置读取/保存和备份 IO 离开 FX。
- 服务端停止展示 STOPPED/TIMED_OUT，保留真实运行状态；阻塞等待不占共享页面执行器。
- 通用后台任务采用 JDK 21 托管虚拟线程，下载池继续限制并行；关闭后的提交明确拒绝。
- 补真实升级状态机、恶意归档、压缩比/大小/数量限制、Windows junction 及真实模组升级/回退覆盖。
- 离线皮肤坏索引在导入前唯一备份；CachedSession 日志输出脱敏；未知平台拒绝不支持的下载，Darwin 不再误判 Windows。
- 仅声明 versionId 的已选可选依赖失败不再降为 warning；typed 元数据保留直接父版本；旧资源物化前校验源对象 SHA-1。
- 快捷方式 COM 失败时回退为 UTF-16LE VBS ShellExecute，真实启动夹具覆盖中文、空格、百分号与元字符参数。
- 删除无消费者的版本分类迁移及确认无调用的下载辅助代码；下载测试根目录统一由 TempDir 管理，日志先于测试根目录切换初始化。
- 删除防重复提交，检查报告不再重复弹完成框，文案键转换使用 Locale.ROOT；启动设置支持 ESC，关闭后忽略后台迟到结果。

独立评审额外发现：缺失 journal 时仍可能删除唯一回滚备份。现模组/整合包的显式恢复遇备份或未知内容会拒绝并保留现场，活动事务关闭同样保留回滚备份；两条路径均有红绿回归。

## 核验后保留的行为

- 父 classifiers 继承有旧 Forge/LWJGL 兼容测试支持，保留；子坐标不能复用父 artifact。直接删除所有父 classifiers 会破坏既有兼容性。
- logback 的 implementation 不等同 api，不向依赖方编译类路径导出；core 的 PropertyDefiner 实际需要后端编译依赖，保留现状。
- Windows 目标 Path 的大小写去重已经成立，仅补回归。
- ZIP Unix symlink 属性条目按普通文件写入，不创建文件系统链接；没有为此新增中央目录解析器。已有 symlink/junction 仍拒绝。
- 安装页测试使用同父容器中的相对布局关系，没有固定像素值，保留该断言。
- 恢复按钮允许进入检查和中断收尾；修正文档中过早声称“仅存在恢复点才可用”的描述。
- 旧目标旁的 part/meta 不迁移、不复用、不自动删除，兼容策略已记入数据格式文档。

## 验收

| 检查 | 本轮结果 |
| --- | --- |
| `gradlew.bat check --max-workers=2` | BUILD SUCCESSFUL；包含 JUnit、Checkstyle、SpotBugs、JaCoCo 门槛 |
| JUnit | core 641 项、GUI 130 项；合计 770 通过、0 失败/错误、1 跳过 |
| core 覆盖率 | 行 76.36%，分支 58.98% |
| GUI 覆盖率 | 行 52.61%，分支 39.57% |
| `captureLauncherUi` | forest、local-versions、saves-details、backups-en、local-servers 均生成并逐张查看 |
| `packageWindowsApp` | BUILD SUCCESSFUL；374 文件、170.15 MiB 的完整应用镜像 |
| 包内容 | ECL.exe、app/ECL.cfg、runtime/lib/modules、runtime/bin/server/jvm.dll 存在；三个 ECL 模块包内 JAR 的 SHA-256 与本次 build/libs 一致 |
| 工作区审计 | git status、git diff、git diff --check 已核对；无空白错误，未加入构建产物或真实账户文件，依赖锁文件未变 |

日志位于 `build/remediation-final-verified-check.log`、`build/remediation-package.log` 及 `build/remediation-snapshot-*.log`。镜像为 `dist/windows/ECL/`，不能只复制 ECL.exe。逐批失败与裁决见 [执行记录](superpowers/plans/2026-10-24-remediation-execution.md)，快照路径见 [截图记录](images/README.md)。

真实 Microsoft 登录、用户 Minecraft 实例和联机游戏不在本轮执行范围；文件符号链接权限受 Windows 环境限制，junction 另有真实夹具验证。VBS 回退需要 Windows Script Host。
