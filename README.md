# ECL

ECL 是一个基于 JavaFX 的轻量 Minecraft 启动器。项目使用 Gradle 构建，支持版本下载、游戏启动、离线登录、外置 Yggdrasil 登录入口，以及 Modrinth 内容搜索与导入。

## 功能特性

- Minecraft 版本列表获取、版本资源下载与本地版本管理
- 离线登录与 Yggdrasil 外置登录
- 自定义 Java 路径、游戏目录、JVM 参数和最大内存
- 自动解析 Minecraft 版本 JSON、依赖库、原生库与启动参数
- Modrinth 模组、光影包、材质包和整合包搜索下载
- JavaFX 桌面界面
- Windows `jpackage` 应用镜像打包任务

## 环境要求

- JDK 17 或更高版本
- Gradle Wrapper 已包含在项目中，无需单独安装 Gradle
- 网络连接，用于下载 Gradle 依赖、Minecraft 资源和 Modrinth 内容

## 快速开始

在项目根目录执行：

```powershell
.\gradlew.bat run
```

如果在 Linux 或 macOS 上运行：

```bash
./gradlew run
```

首次启动时，Gradle 会下载 JavaFX、Gson、Jsoup 等依赖。

## 构建

生成 Jar：

```powershell
.\gradlew.bat build
```

生成安装目录：

```powershell
.\gradlew.bat installDist
```

构建完成后可在以下目录找到启动脚本和依赖：

```text
build/install/ECL/
```

## Windows 应用打包

在 Windows 环境下，可以使用自定义任务生成应用镜像：

```powershell
.\gradlew.bat packageWindowsApp
```

输出目录：

```text
dist/windows/
```

该任务依赖 JDK 自带的 `jpackage.exe`。

## 项目结构

```text
.
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
└── src
    └── main
        ├── java/com/ecl
        │   ├── auth        # 登录认证
        │   ├── config      # 配置管理
        │   ├── download    # 游戏与内容下载
        │   ├── launcher    # 游戏启动与版本管理
        │   ├── ui          # JavaFX 界面
        │   └── util        # 文件、HTTP、Java 运行时工具
        └── resources
            ├── css
            ├── fxml
            └── icons
```

## 数据目录

ECL 会在用户目录下创建启动器数据目录：

- Windows: `%APPDATA%\.ecl`
- macOS: `~/Library/Application Support/.ecl`
- Linux: `~/.ecl`

其中会保存版本文件、依赖库、资源文件、游戏目录和启动器配置。

## 主要依赖

- JavaFX 21
- Gson 2.10.1
- Jsoup 1.17.2

## 开源协议

本项目基于 MIT License 开源，详见 `LICENSE` 文件。

## 开发提示

- 主入口类：`com.ecl.ECL`
- JavaFX 应用类：`com.ecl.ui.LauncherUI`
- 默认应用版本：`1.0.0`
- 源码编译编码：`UTF-8`

清理构建产物：

```powershell
.\gradlew.bat clean
```
