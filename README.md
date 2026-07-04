# ECL

ECL 是一个基于 JavaFX 的轻量 Minecraft 启动器。项目使用 Gradle 构建，支持版本下载、游戏启动、离线登录、正版 Microsoft 登录、外置 Yggdrasil 登录，以及 Modrinth 内容搜索与导入。

## 功能特性

- Minecraft 正式版、预览版/快照、愚人节版列表获取、版本资源下载与本地版本管理
- 离线登录、正版 Microsoft 设备码登录与 Yggdrasil 外置登录
- 正版登录时自动复制 Microsoft 设备码，浏览器打开后可直接粘贴授权
- 自定义 Java 路径、游戏目录、JVM 参数和最大内存
- 按版本创建独立实例目录，自动准备 `mods`、`shaderpacks`、`resourcepacks`、`saves` 和 `logs`
- 自动解析 Minecraft 版本 JSON、依赖库、原生库与启动参数
- Modrinth 模组、光影包、材质包和整合包内置搜索下载
- 下载内容自动导入对应目录：`mods`、`shaderpacks`、`resourcepacks`、`modpacks`
- 游戏异常退出后自动分析英文日志，输出中文解释和修复建议
- 当前版本可一键打开 mc 中文 Wiki 对应版本更新介绍
- 官方源下载较慢或失败时自动尝试镜像源
- 极简 Minecraft 风格 JavaFX 桌面界面
- Windows `jpackage` 应用镜像打包任务，可生成 `ECL.exe`

## 正版 Microsoft 登录

首页账号区域提供 `正版登录` 按钮，也可以在账号模式中选择 `微软登录 (Microsoft)`。

登录流程：

1. 点击 `正版登录`。
2. ECL 获取 Microsoft 设备码，并自动复制登录代码到系统剪贴板。
3. 浏览器打开微软验证页面后，直接粘贴代码完成授权。
4. ECL 自动换取 Xbox Live、XSTS 和 Minecraft Services 令牌。
5. 验证账号是否拥有 Minecraft Java 版授权，并读取正版玩家名、UUID 和访问令牌。

授权成功后会保存刷新令牌，后续启动会优先自动刷新登录状态。如果自动复制失败，登录窗口仍会显示代码，并提供 `复制代码` 按钮。

## Modrinth 内容下载

启动器内置 Modrinth 内容下载入口，支持按当前选择的 Minecraft 版本筛选兼容内容：

- 模组：支持 Fabric、Forge、NeoForge、Quilt 加载器筛选，下载 `.jar` 并导入 `mods`
- 光影包：下载 `.zip` 并导入 `shaderpacks`
- 材质包：下载 `.zip` 并导入 `resourcepacks`
- 整合包：下载 `.mrpack` 并导入启动器 `modpacks` 库

搜索结果支持点击查看简介，下载完成后会自动放入对应目录。模组下载会尝试同时处理 Modrinth 标记的必需依赖。

没有输入关键词时，下载窗口会自动加载 Modrinth 官网下载量排序列表，便于像 PCL2 下载页一样直接浏览热门模组、光影包、材质包和整合包。

## 版本更新介绍

启动页的当前版本旁提供 `更新介绍` 按钮。选择正式版或快照版后，点击按钮会打开 mc 中文 Wiki 对应版本页面，用于查看该 Minecraft 版本的更新内容、修复项目和变更说明。

## 启动错误中文诊断

游戏启动后，ECL 会后台监控 Minecraft 进程输出和本次启动生成的 crash report。异常退出时会自动弹出中文诊断窗口，包含：

- 错误类型中文说明
- 可执行的修复建议
- 从英文日志中提取的关键报错行
- 打开崩溃报告目录和 `mods` 目录的快捷按钮

当前可识别的常见问题包括 Java 版本不匹配、内存不足、重复模组、前置依赖缺失、模组版本不兼容、Mixin 注入失败、OpenGL/显卡驱动异常、文件占用、jar 损坏和网络下载失败。

## 界面结构

- 左侧导航栏区分首页、版本、Modrinth、设置和日志
- 首页聚合版本选择、账号模式、正版登录、游戏目录和 JVM 参数
- 右侧显示下载队列、Modrinth 推荐和崩溃诊断入口
- 长页面和弹窗支持鼠标滚轮滚动

## 环境要求

- JDK 17 或更高版本
- Gradle Wrapper 已包含在项目中，无需单独安装 Gradle
- 网络连接，用于下载 Gradle 依赖、Minecraft 资源、Microsoft 登录令牌和 Modrinth 内容

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
dist/windows/ECL/
```

可执行文件：

```text
dist/windows/ECL/ECL.exe
```

该任务依赖 JDK 自带的 `jpackage.exe`。如果打包时提示 `dist/windows` 无法删除，通常是旧版 `ECL.exe` 正在运行、资源管理器占用目录，或 OneDrive 正在同步旧 runtime 文件。关闭相关进程或暂停同步后重新执行打包即可。

## 数据目录

ECL 会在用户目录下创建启动器数据目录：

- Windows: `%APPDATA%\.ecl`
- macOS: `~/Library/Application Support/.ecl`
- Linux: `~/.ecl`

其中会保存版本文件、依赖库、资源文件、账号刷新令牌、游戏目录和启动器配置。

默认游戏根目录为数据目录下的 `game`，具体版本会进入独立实例目录：

```text
game/instances/<version>/
```

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

## 主要依赖

- JavaFX 21
- Gson 2.10.1
- Jsoup 1.17.2

## 开源协议

本项目基于 GNU General Public License v3.0 开源，详见 `LICENSE` 文件。

## 开发提示

- 主入口类：`com.ecl.ECL`
- JavaFX 应用类：`com.ecl.ui.LauncherUI`
- 默认应用版本：`1.0.0`
- 源码编译编码：`UTF-8`

清理构建产物：

```powershell
.\gradlew.bat clean
```
