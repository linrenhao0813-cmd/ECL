# ECL 功能拆分计划

## 目标与原则

将 `LauncherUI.java`、`EclCli.java`、`ModBrowserView.java` 和 `HttpUtil.java` 按独立业务职责拆成单独的 Java 文件，同时保持现有界面、命令行参数、配置格式、网络行为和公开 API 不变。

- 采用分阶段重构，每个阶段都保持可编译、可测试。
- 不修改 CSS、界面文案、配置键、用户目录或持久化格式。
- 新增内部类型默认使用包级可见性，不扩大公开 API。
- 兼容入口只负责委托，具体业务逻辑放入对应功能文件。

## 实施步骤

1. 建立 GUI 截图、CLI 输出和 HTTP 行为基线。
2. 将 HTTP 客户端、请求、JSON、断点下载、限速和断点元数据从 `HttpUtil` 拆出，保留原有静态 API 门面。
3. 将每个 Picocli 命令拆到与命令类同名的文件，提取输出、运行环境、脱敏和 Mod 文件操作支持类。
4. 将 Mod 浏览器拆成搜索、详情、已安装、更新和安装流程组件，保留原构造器与生命周期。
5. 将启动器拆成窗口外壳、共享状态、导航、页面、对话框和业务协调器；`LauncherUI` 只负责 JavaFX 生命周期和组装。
6. 逐模块运行测试，最后运行 `check`、`build`、GUI 截图回归、`installDist` 和可用时的 Windows 打包烟测。

## 兼容性要求

- 保持 `ECLauncher`、`LauncherUI`、`EclCli.main/execute`、`ModBrowserView` 构造器和 `HttpUtil` 公共方法不变。
- 保持 CLI 命令名、选项、帮助、JSON 字段、退出码及 stdout/stderr 选择不变。
- 保持搜索防抖、异步取消、下载断点续传、限速、主题、语言和页面导航行为不变。
- 不新增框架或运行时依赖，不进行数据迁移。

## 验收标准

- 四个原始文件不再承载多个独立功能的具体实现。
- 每个独立功能有对应顶层 Java 文件。
- 模块测试、JUnit、Checkstyle、SpotBugs、JaCoCo 和完整构建通过。
- JavaFX 稳定场景截图没有非预期布局变化。
- CLI、设置、账号、版本、实例、缓存和下载数据保持兼容。

## 实施顺序

行为基线 → `HttpUtil` → `EclCli` → `ModBrowserView` → `LauncherUI` → 完整验证。

## 实施结果

- 已完成 HTTP、CLI、Mod 浏览器和 LauncherUI 主要业务流程的职责拆分。
- 已新增账号、皮肤、游戏启动、版本操作、备份、加载器安装、设置对话框等独立协调器/组件。
- 已通过 `:ecl-core:test`、`:ecl-cli:test`、`:ecl-gui:test`、`check` 和 `build`。
- 已生成 `installDist`，并通过 `packageWindowsApp` 生成 `dist/windows/ECL/ECL.exe` 与 `ECL-CLI.exe`。
- 已完成主页、版本、Modrinth、设置和备份页面截图回归；Modrinth 在线请求的 SSL 握手警告属于当前网络环境限制。
