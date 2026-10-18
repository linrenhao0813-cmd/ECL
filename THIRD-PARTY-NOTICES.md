# 第三方组件与许可

ECL 以 [GNU General Public License v3.0](LICENSE) 发布。本项目在构建与运行时使用以下第三方
组件，它们各自保留原许可。发布打包版时请同时保留本文件与各组件许可声明。

## 运行时依赖

| 组件 | 版本 | 许可 | 用途 |
| --- | --- | --- | --- |
| [JavaFX](https://openjfx.io/) | 21.0.10 | GPL-2.0 with Classpath Exception | 图形界面与窗口、控件、图像 |
| [Gson](https://github.com/google/gson) | 2.14.0 | Apache-2.0 | JSON 序列化与解析 |
| [Jackson Databind](https://github.com/FasterXML/jackson)（含 core / annotations） | 2.22.3 | Apache-2.0 | JSON 数据处理 |
| [JNA](https://github.com/java-native-access/jna)（jna / jna-platform） | 5.19.1 | Apache-2.0 or LGPL-2.1（双许可） | Windows DPAPI 等原生调用 |
| [SLF4J API](https://www.slf4j.org/) | 2.0.20 | MIT | 日志门面 |
| [Logback](https://logback.qos.ch/)（classic / core） | 1.6.4 | EPL-1.0 or LGPL-2.1（双许可） | 日志实现与滚动文件 |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys)（common-*、imageio-*） | 3.15.2 | BSD-3-Clause | WebP 图像解码（封面、图标） |
| [Error Prone Annotations](https://github.com/google/error-prone) | 2.48.0 | Apache-2.0 | 编译期注解（传递依赖） |

## 构建与测试依赖

| 组件 | 版本 | 许可 |
| --- | --- | --- |
| [Gradle](https://gradle.org/) | 9.8.0 | Apache-2.0 |
| [JUnit](https://junit.org/) | 6.1.3 | EPL-2.0 |
| [TestFX](https://github.com/TestFX/TestFX) | 4.0.18 | EUPL-1.1 |
| [Monocle](https://github.com/TestFX/Monocle)（openjfx-monocle） | 21.0.2 | GPL-2.0 with Classpath Exception |
| [Hamcrest](https://hamcrest.org/) | 3.0 | BSD-3-Clause |
| [Checkstyle](https://checkstyle.org/) | 10.24.0 | LGPL-2.1-or-later |
| [SpotBugs Gradle Plugin](https://github.com/spotbugs/spotbugs-gradle-plugin) | 6.5.12 | Apache-2.0 |
| [JaCoCo](https://www.jacoco.org/) | 随 Gradle 提供 | EPL-2.0 |

依赖版本由 `gradle/libs.versions.toml` 管理，并由各模块 `gradle.lockfile` 与
`gradle/verification-metadata.xml` 固定与校验；`gradle/verification-keyring.keys` 保存已信任的
构建依赖发布者签名公钥。

## 外部服务

ECL 运行时会访问以下第三方服务，它们各自适用其服务条款与隐私政策，详见 README 的
“网络请求与隐私”一节：

- Mojang 与 Microsoft（版本清单、资源、会话、皮肤、账户登录）
- Modrinth（内容目录、项目元数据与 CDN 下载）
- Adoptium（Eclipse Temurin Java 运行时）
- Fabric、Quilt、Forge、NeoForge（加载器元数据与安装构件）
- authlib-injector（离线皮肤支持，含 BMCLAPI 镜像）
- mcsrvstat.us、minecraft-java-servers.com（服务器在线状态与目录）
- 翻译服务：腾讯 Transmart、Google Translate、MyMemory（内容简介自动翻译）

## 商标与免责

- **Minecraft** 是 Mojang Studios 的商标。ECL 是非官方第三方启动器，与 Mojang Studios、
  Microsoft 无关联，也未获其背书或赞助。
- **Microsoft**、**Xbox Live** 是 Microsoft Corporation 的商标。
- 使用 ECL 启动 Minecraft 需要用户自备合法账号，并遵守
  [Minecraft 最终用户许可协议](https://www.minecraft.net/eula)。
- 公开服务器目录、Modrinth 内容与第三方翻译结果均来自第三方，ECL 不对其内容、
  安全性或运营方式背书。
- 背景素材与图标见 `ecl-gui/src/main/resources/images/README.md`。
