# 仓库指南

## 代理工作流程

每次开始处理任务前，先阅读根目录的 `README.md`，以确认当前功能、命令和目录说明。任务完成后，按实际变更同步更新 `README.md`，并审计未提交代码：检查 `git status`、`git diff` 和 `git diff --check`，确认没有遗漏、无关或敏感文件。

新增或调整用户可见功能时，除 `README.md` 外还须同步文档：

- 持久化文件、字段或目录结构变化 → 更新 `docs/data-formats.md`（字段必须能指回代码里的 record 组件或 JSON 键名）。
- 新增类、包或改变关键流程 → 更新 `docs/codebase-guide.md` 与 `docs/architecture.md`。
- 界面改动 → 重新运行 `captureLauncherUi` 生成快照，并核对 `docs/images/README.md` 里记录的模式。
- 新增设置项或网络请求 → 更新 README 的“图形界面使用”“网络请求与隐私”章节。

## 项目结构与模块组织

ECL 是基于 Java 21、JavaFX 和 Gradle 的多模块项目。`ecl-boot/` 包含 `com.ecl.ECL` 应用入口；`ecl-core/` 存放认证、下载、游戏启动、实例、整合包等平台无关服务；`ecl-gui/` 存放 JavaFX 界面、控制器、CSS、图标和本地化资源；`ecl-dist/` 定义 Windows `jpackage` 打包任务。生产代码位于 `src/main/java`，测试位于 `src/test/java`，运行时资源位于 `src/main/resources`。共享的 Checkstyle 与 SpotBugs 配置位于 `config/`。`build/` 和 `dist/` 是生成目录，不要手动提交其中的文件。

`ecl-boot`、`ecl-core`、`ecl-gui` 三个 Java 模块各自持有 `gradle.lockfile`；`ecl-dist` 只有打包任务，没有锁文件。依赖版本集中在 `gradle/libs.versions.toml`，校验元数据与公钥位于 `gradle/`。

## 构建、测试与开发命令

请在仓库根目录使用 JDK 21 执行以下命令：

- `.\gradlew.bat run`：通过 `ecl-boot` 启动应用。
- `.\gradlew.bat check`：运行 JUnit 5、Checkstyle、SpotBugs 和 JaCoCo 校验。
- `.\gradlew.bat build`：编译并验证全部模块。
- `.\gradlew.bat installDist`：生成 `ecl-boot/build/install/ECL/` 分发目录。
- `.\gradlew.bat captureLauncherUi`：生成 `ecl-gui/build/visual-qa/ecl-home.png` 界面快照（模式见 README“界面快照”）。
- `.\gradlew.bat packageWindowsApp`：在 `dist/windows/ECL/` 生成 Windows 应用镜像。

## 编码风格与命名约定

使用 UTF-8、四空格缩进、LF 行尾，禁止制表符与行尾空白。Java 包名在 `com.ecl` 下使用小写；类型使用 `PascalCase`，方法和字段使用 `camelCase`，并使用 `Service`、`Manager`、`Repository` 等清晰的职责后缀。实现应保持简洁，优先复用现有抽象，避免重复逻辑、无意义的封装和未使用代码。Checkstyle 要求行长不超过 160 个字符、方法不超过 180 行，禁止未使用的导入，并将圈复杂度限制为 25。业务逻辑放在 `ecl-core`，JavaFX 专属逻辑放在 `ecl-gui`。新增界面文案时同步更新适用的 `i18n/*.properties` 文件（简体、英文、繁体三份都要改）。

行尾与格式由仓库根目录的 `.gitattributes` 与 `.editorconfig` 约定：文本文件统一 LF，`*.bat`/`*.cmd` 保持 CRLF，`*.png`/`*.jar`/`*.ico`/`*.gz` 等按二进制处理；Java/Kotlin/Markdown 使用 4 空格缩进（YAML/JSON/Markdown 内容缩进 2 空格）。Gradle 锁文件与校验元数据要保持字节稳定，不要手工重排。

## 测试规范

测试应放在对应包中，类名使用 `*Test.java`；只有较宽泛的集成检查才使用 `*SmokeTest`。运行单个测试示例：`.\gradlew.bat :ecl-core:test --tests com.ecl.util.FileUtilTest`（也可用 `com.ecl.download.GameDownloaderTest` 等真实存在的测试类）。JaCoCo 最低覆盖率为：`ecl-core` 行覆盖率 60%、分支覆盖率 40%；`ecl-gui` 行覆盖率 20%、分支覆盖率 15%。修复缺陷时应补充回归测试；界面改动应使用 `captureLauncherUi` 检查快照。

GUI 测试在 headless Monocle 下运行，`ecl-gui:test` 会把隔离的 `user.home`/`APPDATA` 指向 `build/headless-test-appdata`，不会污染真实的 `%APPDATA%\.ecl`；写测试时不要读写真实用户目录。

## 提交与拉取请求规范

近期提交倾向使用简洁的 Conventional Commit 标题，例如 `feat: ...`、`fix: ...` 和 `refactor: ...`。提交信息应使用祈使语气、说明范围，并保持一次提交只完成一项明确改动。拉取请求应说明问题与解决方案，关联相关 Issue，列出验证命令；JavaFX 改动请附前后截图（用 `captureLauncherUi` 生成，不要伪造）。禁止提交 API 密钥、账户数据和生成的构建产物。

发布由 tag 触发的 `release` 工作流完成；改动发布流程时同步检查 `.github/workflows/release.yml` 与 README 的 CI 说明。
