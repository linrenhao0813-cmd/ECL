# 仓库指南

## 代理工作流程

每次开始处理任务前，先阅读根目录的 `README.md`，以确认当前功能、命令和目录说明。任务完成后，按实际变更同步更新 `README.md`，并审计未提交代码：检查 `git status`、`git diff` 和 `git diff --check`，确认没有遗漏、无关或敏感文件。

## 项目结构与模块组织

ECL 是基于 Java 21、JavaFX 和 Gradle 的多模块项目。`ecl-boot/` 包含 `com.ecl.ECL` 应用入口；`ecl-core/` 存放认证、下载、游戏启动、实例、整合包等平台无关服务；`ecl-gui/` 存放 JavaFX 界面、控制器、CSS、图标和本地化资源；`ecl-dist/` 定义 Windows `jpackage` 打包任务。生产代码位于 `src/main/java`，测试位于 `src/test/java`，运行时资源位于 `src/main/resources`。共享的 Checkstyle 与 SpotBugs 配置位于 `config/`。`build/` 和 `dist/` 是生成目录，不要手动提交其中的文件。

## 构建、测试与开发命令

请在仓库根目录使用 JDK 21 执行以下命令：

- `.\gradlew.bat run`：通过 `ecl-boot` 启动应用。
- `.\gradlew.bat check`：运行 JUnit 5、Checkstyle、SpotBugs 和 JaCoCo 校验。
- `.\gradlew.bat build`：编译并验证全部模块。
- `.\gradlew.bat installDist`：生成 `ecl-boot/build/install/ECL/` 分发目录。
- `.\gradlew.bat captureLauncherUi`：生成 `ecl-gui/build/visual-qa/ecl-home.png` 界面快照。
- `.\gradlew.bat packageWindowsApp`：在 `dist/windows/ECL/` 生成 Windows 应用镜像。

## 编码风格与命名约定

使用 UTF-8、四空格缩进，禁止制表符。Java 包名在 `com.ecl` 下使用小写；类型使用 `PascalCase`，方法和字段使用 `camelCase`，并使用 `Service`、`Manager`、`Repository` 等清晰的职责后缀。实现应保持简洁，优先复用现有抽象，避免重复逻辑、无意义的封装和未使用代码。Checkstyle 要求行长不超过 160 个字符、方法不超过 180 行，禁止未使用的导入，并将圈复杂度限制为 25。业务逻辑放在 `ecl-core`，JavaFX 专属逻辑放在 `ecl-gui`。新增界面文案时同步更新适用的 `i18n/*.properties` 文件。

## 测试规范

测试应放在对应包中，类名使用 `*Test.java`；只有较宽泛的集成检查才使用 `*SmokeTest`。运行单个测试示例：`.\gradlew.bat :ecl-core:test --tests com.ecl.package.ClassTest`。JaCoCo 最低覆盖率为：`ecl-core` 行覆盖率 60%、分支覆盖率 40%；`ecl-gui` 行覆盖率 20%、分支覆盖率 15%。修复缺陷时应补充回归测试；界面改动应使用 `captureLauncherUi` 检查快照。

## 提交与拉取请求规范

近期提交倾向使用简洁的 Conventional Commit 标题，例如 `feat: ...`、`fix: ...` 和 `refactor: ...`。提交信息应使用祈使语气、说明范围，并保持一次提交只完成一项明确改动。拉取请求应说明问题与解决方案，关联相关 Issue，列出验证命令；JavaFX 改动请附前后截图。禁止提交 API 密钥、账户数据和生成的构建产物。
