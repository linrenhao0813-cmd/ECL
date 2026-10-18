# 贡献指南

感谢你愿意帮助 ECL。开始之前请先阅读仓库根目录的 [AGENTS.md](AGENTS.md)，那里记录了项目的
模块划分、构建命令、编码风格、测试要求与提交规范；本文件只做补充。

## 环境

- Windows
- JDK 21（`java -version` 应为 21.x）
- 无需单独安装 Gradle，仓库自带 Wrapper（Gradle 9.8.0）

如果 `java` 不在 `PATH` 中，请在同一 PowerShell 会话里设置 `JAVA_HOME` 与 `PATH`。

## 构建与验证

```powershell
.\gradlew.bat check            # JUnit + Checkstyle + SpotBugs + JaCoCo
.\gradlew.bat run              # 通过 ecl-boot 启动应用
.\gradlew.bat installDist      # 生成 ecl-boot/build/install/ECL/
.\gradlew.bat captureLauncherUi # 生成 ecl-gui/build/visual-qa/ecl-home.png
```

提 PR 前请至少运行 `check`；涉及界面的改动请附 `captureLauncherUi` 快照（可选
`-PuiSnapshotMode=<模式>`，可用模式见 README 的“界面快照”一节）。

## 代码风格

- UTF-8、4 空格缩进、禁止制表符，行长不超过 160 字符。
- 方法不超过 180 行，圈复杂度不超过 25，禁止未使用的导入。
- 业务逻辑放在 `ecl-core`，JavaFX 专属逻辑放在 `ecl-gui`；依赖方向为
  `ecl-boot` → `ecl-gui` → `ecl-core`。
- 新增界面文案时同步更新 `ecl-core/src/main/resources/i18n/messages*.properties`
  与 `ecl-gui/src/main/resources/i18n/gui*.properties` 的简体中文、英文与繁体中文三份文件。
- 优先复用现有抽象；不要提交未使用代码、无意义封装或仅转发的方法。

## 测试

- 测试放在对应包，类名 `*Test.java`；只有较宽泛的集成检查才用 `*SmokeTest`。
- 修复缺陷时补充回归测试；界面改动建议补 TestFX 用例。
- 覆盖率下限：`ecl-core` 行 60%／分支 40%，`ecl-gui` 行 20%／分支 15%。
- 运行单个测试：`.\gradlew.bat :ecl-core:test --tests com.ecl.util.FileUtilTest`。

## 提交

- 使用简洁的 Conventional Commit 标题：`feat: ...`、`fix: ...`、`refactor: ...`、`docs: ...`、`test: ...`、`chore: ...`。
- 祈使语气，说明范围；一次提交只完成一项明确改动。
- 禁止提交 API 密钥、账号数据、生成的构建产物（`build/`、`dist/`）。

## 拉取请求

请在描述中包含：

1. 问题与解决方案（关联相关 Issue）。
2. 验证命令与结果（例如 `.\gradlew.bat check` 通过）。
3. JavaFX 改动的前后截图。
4. 若修改了文档中描述的行为，确认已同步 README 与 `docs/` 下对应章节。

提交前请自查未提交内容：

```powershell
git status
git diff
git diff --check
```

确认没有遗漏、无关或敏感文件。
