## 变更说明

<!-- 说明问题与解决方案；关联相关 Issue，例如 Closes #123 -->

## 变更类型

- [ ] feat：新功能
- [ ] fix：缺陷修复
- [ ] refactor：重构（不改变行为）
- [ ] docs：文档
- [ ] test：测试
- [ ] chore：构建、依赖或工具

## 验证

<!-- 粘贴实际执行的命令与结果 -->

```powershell
.\gradlew.bat check
```

- [ ] `.\gradlew.bat check` 通过
- [ ] 涉及界面时附 `captureLauncherUi` 前后截图
- [ ] 涉及用户可见行为时已同步 README 与 `docs/` 对应章节
- [ ] 新增界面文案已同步 `messages*.properties` 与 `gui*.properties` 的简中、英文、繁中三份文件
- [ ] 修复缺陷时补充了回归测试

## 自查

- [ ] `git status` 无遗漏或无关文件
- [ ] `git diff --check` 无空白错误
- [ ] 未提交 API 密钥、账号数据或生成的构建产物（`build/`、`dist/`）

## 备注

<!-- 需要 reviewer 特别注意的地方：锁范围、事务边界、迁移逻辑等 -->
