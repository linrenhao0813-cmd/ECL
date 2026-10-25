# 审核整改执行记录

用户已以“开始修复”授权执行原审核报告及整改计划；本轮不提交、不推送，不访问真实账户或启动用户游戏。

## 范围与接口裁决

- 事务/恢复、下载/校验、GUI 三组按文件所有权并行实现，Gradle 串行验证；共享文档和升级锁调用链由主代理集成。
- Task 2–5：先取得实例会话，再取得恢复私锁/事务私锁；内部显式借用，独立入口仍竞争物理锁。Draft 普通借用与事务互斥借用分开。
- Task 6–7：GUI 使用 core 的 `GameInstallationRepairService.canRepair`；修复不通过普通全量安装间接下载无摘要库。
- Ruling: 保留父 classifiers。既有旧 Forge/LWJGL 回归验证了该继承兼容性，报告未提供错误 native 的具体输入；只禁止父 artifact 沿用。
- Ruling: 保留 logback 的 implementation。Gradle java-library 的 implementation 不向依赖方的编译类路径导出，core 的 PropertyDefiner 实际需要编译后端类型；原报告将 implementation 等同 api 的判断不成立。
- Ruling: 不引入 ZIP 中央目录解析器识别 Unix symlink 属性。当前提取始终创建普通文件，新增真实属性夹具确认不会创建链接；已有链接/junction 仍按文件系统边界拒绝。
- Ruling: 升级独占由专用虚拟线程持有原有 ReentrantLock 和物理 lease，借用/释放可跨异步线程；没有 ThreadLocal 或全局文件锁重入。该实现保持既有协调器重入语义。
- Ruling: Windows 快捷方式 COM 失败时改用带 UTF-16LE BOM 的 VBS ShellExecute 入口，避免 cmd 代码页、百分号和元字符展开。需要 Windows Script Host；不改变已生成的快捷方式。
- Ruling: 通用 GUI 后台任务使用 JDK 21 托管虚拟线程，避免 CallerRunsPolicy 在饱和时阻塞 FX、关闭后静默丢弃；下载器继续限制并行。

## 已执行证据（持续更新）

- `build/main-red.log`：49 项，新增 6 项预期失败（凭据脱敏、坏皮肤索引、可选依赖身份、未知 OS/Darwin）。
- `build/remediation-combined-red.log`：161 项，21 失败，覆盖新下载/校验/整合包/恢复边界；作为各组修复前证据。
- 首次并发 Gradle 出现 stash/test 目录竞争后改为串行；该类编译错误不作为业务红灯。
- `build/remediation-core-green.log` 第一轮：630 项，4 失败、1 跳过；失败涉及快捷方式、canRepair、两个空加载器目录，均继续处理，没有视为验收通过。
- GUI 对原始 HEAD 的临时复现由自身文件备份/finally 恢复，提交同步异常与关闭执行器三项真实失败；随后运行修复后的 GUI 测试。

## 最终评审与验收

- 独立只读 review 确认一项 Must：缺失 journal 时可能删除唯一回滚备份。pack/mod 显式恢复和活动 close 均补保留边界；`build/missing-journal-red.log`、`active-journal-red.log` 复现，随后 source-file 与 JUnit 回归通过。无未处理的 Must/Should。
- 最后加入的启动设置锁竞争/ESC 测试揭示 Dialog 缺取消按钮类型，已补关闭契约并通过回归；不会因后台读取阻塞关闭。
- 全套运行揭示快捷方式进程夹具以“文件存在”判断正文已写完的竞态，改为原子发布后完整复验；没有通过延长超时掩盖失败。
- 最终 `gradlew.bat check --max-workers=2`：BUILD SUCCESSFUL，日志 `build/remediation-final-verified-check.log`。core 641 项/97 套件、GUI 130 项/29 套件；770 通过、1 跳过、0 失败/错误。
- Checkstyle 主代码和测试均零违规；SpotBugs 主代码和测试通过；core 行/分支 76.36%/58.98%，GUI 行/分支 52.61%/39.57%。
- 五种快照已生成并逐张检查；最后发现的本地服务端旧下载入口提示三语同步更正后，再次完整 check、重拍并重新打包。
- `packageWindowsApp` 成功；完整镜像 374 文件、170.15 MiB。EXE、配置、运行时模块与 JVM DLL 均存在，三个 ECL JAR 的 SHA-256 与本次构建一致。
- 依赖锁与发布流程未变；最终 git diff --check 零错误，未提交/推送，未将 build/dist 或真实账号数据纳入改动。
- 未执行真实 Microsoft 登录、用户游戏、联机或真实多启动器并发验收。文件符号链接夹具因权限跳过，Windows junction 真实回归通过；Windows 目录 fsync 尽力执行，VBS 回退需要 Windows Script Host。

原计划中的测试名是实施前建议，实际等价回归、技术裁决及上述验收结果为本轮交付依据。详见 `docs/review-remediation-2026-10-24.md`。
