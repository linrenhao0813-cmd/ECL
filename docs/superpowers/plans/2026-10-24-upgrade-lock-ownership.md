# 升级实例锁所有权子计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用主计划选定的执行方式；本文件是任务 5 的技术细化，不是开始实施的授权。

**Goal:** 从升级 before 快照开始至 after 快照完成，阻止同进程与其它启动器的实例变更，同时允许本次升级内部按顺序执行加载器、模组及整合包事务。

**Architecture:** 显式持有可跨异步阶段传递的实例会话；只有持有该会话的内部调用可以借用已有独占权。普通操作继续通过既有协调器与文件 lease 竞争，不引入全局可重入文件锁或 ThreadLocal 自动绕过。

**Tech Stack:** Java 21、CompletableFuture、现有实例协调器、InstanceOperationLease。

**Spec:** [修复范围 B6](../specs/2026-10-24-review-remediation-design.md)；[主计划 Task 5](2026-10-24-review-remediation.md)。

## 只读核验结论

当前锁及异步链：

```text
InstanceUpdateService.execute
  supplyAsync(requireFresh -> ensureStopped -> recovery.begin)
  thenCompose(executeWithRestorePoint)
    普通: LoaderUpdateService.update(supplyAsync)
          -> ModUpdateService.applyPreparedUpdate
             -> ModInstallationService.install(supplyAsync)
                -> operationLock.acquire
                -> FileModInstallationTransaction.recoverIncompleteTransactions
                -> new FileModInstallationTransaction [另取跨进程锁]
    整包: DefaultModpackUpdateService.applyPreparedUpdate(supplyAsync)
          -> operationLock.acquire
          -> MrpackInstaller.applyPreparedUpdate
             -> new PackUpdateTransaction [另取跨进程锁]
  handleAsync(recovery.complete -> execution.close)
```

- InstanceOperationCoordinator 当前 ReentrantLock 只支持同线程释放；不能从完成回调线程 close 原 lease。
- LoaderUpdateService 与 ModInstallationService 会再次取得同实例进程内锁，事务又取文件 lease。只给 execute 外层加锁会让它们自锁。
- ModInstallationService 的恢复路径也会再次取文件锁，借用必须同时涵盖恢复与新事务。
- MainController 的后台池同时承载 loader、模组与整包的异步任务，不能在该池一个任务中 join 同池后继来规避线程所有权。
- 目前 execute 使用 handleAsync 收尾；执行器关闭时该回调也可被拒绝，不能把清理依赖放在它上面。
- 默认 InstanceUpgradeRecoveryService 构造没有 coordinator，GUI 注入的有。测试与非 GUI 入口不能因此绕过跨进程保护。

## 锁顺序与适用范围

采用固定顺序：进程内实例独占 → InstanceOperationLease → 升级恢复私有锁 → 单个事务私有锁。

inspect/restore 与 begin/complete 使用相同顺序；不持有恢复私有锁再等待实例锁。事务私有锁在已有实例会话下借用生命周期 lease；独立事务按统一顺序自行获取生命周期 lease 与自身私有锁。不能让 pack 恢复继续以相反顺序持 pack 锁等待实例 lease。

跨进程锁绑定规范化运行目录；会话还绑定 instance UUID。借用同时校验 UUID 和目录；大小写身份采用现有 Windows 路径策略。共享运行目录的不同 profile 仍必须竞争同一跨进程锁。

## 拟定接口

生产接口细化如下；不得以 boolean alreadyLocked 代替所有权对象。

```java
// 新增 com.ecl.operation.InstanceOperationSession；构造器不公开。
public final class InstanceOperationSession implements AutoCloseable {
    public UUID instanceId();
    public Path gameDirectory();
    public Borrow borrow(UUID instanceId, Path gameDirectory) throws IOException;
    @Override public void close() throws IOException;
    public final class Borrow implements AutoCloseable {
        @Override public void close() throws IOException;
    }
}

// InstanceOperationCoordinator 新入口：快失败，失败不留下半初始化会话。
public InstanceOperationSession tryAcquireSession(UUID id, Path gameDirectory)
        throws IOException;
```

会话持有线程无关的协调器独占许可与 InstanceOperationLease。协调器现有 acquire/tryAcquire/acquireInterruptibly 的线程绑定 lease 行为保留；实现内部独占状态时明确区分 legacy 线程所有者与 session 所有者，legacy 同线程重入不能冒认 session。现有调用者签名不变。

Borrow 的规则：

- 活动 session、相同 UUID 和规范目录才可借用；关闭后或错实例抛 IOException。
- Borrow 增减引用数；close 幂等；借用者只能释放自己的借用，不能释放 owner。
- owner close 后禁止新借用，已有借用全部释放后才真正释放跨进程及协调器许可。这防止取消 future 提前释放运行中事务的锁。
- 文件路径/祖先和持久化 GameProcessMarker 检查不能因为借用而略去。
- 不允许两个写事务并发借用同一会话；上层升级链仍逐项执行。

在以下原入口旁添加显式 session 重载；无 session 的独立入口继续取锁：

```text
LoaderUpdateService.update(instance, plan, listener, session)
ModUpdateService.applyPreparedUpdate(prepared, session)
ModInstallationService.install(plan, listener, session)
ModpackUpdateService.applyPreparedUpdate(prepared, gameRoot, listener, session)
MrpackInstaller.applyPreparedUpdate(prepared, gameRoot, running, listener, session)
FileModInstallationTransaction(gameDirectory, session)
FileModInstallationTransaction.recoverIncompleteTransactions(gameDirectory, session)
PackUpdateTransaction(instanceRoot, profileFile, session)
PackUpdateTransaction.recoverIncompleteTransactions(instanceRoot, profileFile, session)
```

服务默认 session 重载如无法真正借用，必须 failedFuture UnsupportedOperationException；不得默认调用自行取锁的旧方法（会自锁）。Fake 服务需明确实现 session 入口，不能靠弱化真实协调器/事务通过测试。

回退点 begin/complete 在 session 下运行；独立 begin 路径创建同等保护，不能仅靠恢复私有锁。Draft 清楚记录由谁负责 owner close，不重复释放升级持有的会话。

## 异步与取消

- execute 同步提交失败：关闭尚未移交的 PreparedExecution；会话如已创建也关闭。
- 取得会话后才进行最终指纹/持久化 marker 检查与 before 快照。
- 任一实际修改失败：仍在会话内计算 after 并发布真实变化的恢复点；原异常与收尾异常互为保留，不吞掉主因。
- 收尾用不依赖可关闭池再次调度的完成处理；可能阻塞的磁盘收尾留在明确的后台生命周期上下文，绝不能因直接执行器/立即完成 future 回落 FX。
- 对外取消不能把“future 已取消”等同“文件写入已停止”。恢复 complete 必须发生在内部真实修改任务退出后；不得对仍在执行的任务计算最终 after。
- 执行阶段 GUI 不提供取消，但应用关闭/线程中断仍须测试上述边界。

## 回归步骤

- [ ] 先新增协调器测试：session 在 A 线程获取、B 线程关闭；持有期间 legacy tryAcquire 在任何线程均失败；独立实例可取得；错目录/错 UUID/已关闭借用拒绝；最后一个借用结束前物理锁不释放。
- [ ] 运行预期失败，再实施协调器与 session 最小接口，不同时改业务链。
- [ ] 新增事务借用测试：同一会话能够进行恢复与提交；另一个无 session 的事务失败；伪造/过期会话不能绕过进程标记；close borrowed 事务不释放顶层独占。
- [ ] 运行预期失败，再实现事务重载及统一锁顺序；重跑既有事务回滚测试。
- [ ] 新增 service 借用测试：loader→真实模组安装能完成；借用失败不执行网络或写文件；pack prepared 临时目录在同步拒绝时释放。
- [ ] 运行预期失败，再贯通服务重载，不改变普通独立入口语义。
- [ ] 用 latch 卡在 before 后、单项 commit 后、complete 前，分别从其它线程和其它进程尝试变更，必须失败/等待而不能混进快照。
- [ ] 单线程 orchestration executor 测试不得 join 同池后继；注入明确拒绝策略测试主操作和收尾，无 CallerRuns 误当通过。
- [ ] 中断/取消测试真实任务退出后才 complete，恢复点准确描述已改动文件；所有终态最终可重新取锁。

## 实施边界

本子计划涉及多个现有服务接口，必须作为一个串行批次实施并完整评审，不能同时让多个代理修改协调器、事务和执行链。若最小原型显示需要改变上述公开语义，先更新设计供确认，不以更宽松全局重入掩盖问题。
