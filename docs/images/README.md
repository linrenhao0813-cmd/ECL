# 界面截图说明

本目录的三张 PNG 由 `captureLauncherUi` 在隔离预览目录下渲染，供 README“界面预览”使用。界面修改后应重新生成并查看实际图片，不用修图或占位图代替。

## 当前截图

以下截图于 2026-10-18 使用 Windows、JDK 21 和 `1.0.2beta` 代码生成，并已逐张查看。

| 文件 | 快照模式 | 内容 |
| --- | --- | --- |
| `launcher-home.png` | `forest` | 森林首页，示例实例“生存世界 / 原版探索” |
| `launcher-downloads.png` | `downloads` | 下载页“游戏实例”分类；共 5 个分类，离线预览不加载在线版本清单 |
| `launcher-settings.png` | `settings-page` | 设置页“通用”标签，窗口与启动区域折叠 |

实例维护入口另以 `local-versions` 模式核对，临时快照输出到 `ecl-gui/build/visual-qa/ecl-instances.png`。该模式展示启动设置、检查与修复、恢复上次升级和更多操作。
2026-10-22 的旧版资源、日志配置与依赖修复另用 `local-versions` 模式生成并查看
`ecl-gui/build/visual-qa/ecl-review-fixes.png`，核对实例维护入口与现有布局；客户端日志配置分类的简体、英文和繁体文案由 `ResourceBundleI18nTest` 验证。
本轮文案变更另用 `backups-en` 与 `modrinth-en` 核对英文布局，输出 `ecl-gui/build/visual-qa/ecl-backups-en.png` 和 `ecl-modrinth-en.png`。

## 重新生成

在仓库根目录执行：

```powershell
.\gradlew.bat captureLauncherUi -PuiSnapshotMode=forest -PuiSnapshotPath=E:/ECL/docs/images/launcher-home.png
.\gradlew.bat captureLauncherUi -PuiSnapshotMode=downloads -PuiSnapshotPath=E:/ECL/docs/images/launcher-downloads.png
.\gradlew.bat captureLauncherUi -PuiSnapshotMode=settings-page -PuiSnapshotPath=E:/ECL/docs/images/launcher-settings.png
.\gradlew.bat captureLauncherUi -PuiSnapshotMode=local-versions -PuiSnapshotPath=E:/ECL/ecl-gui/build/visual-qa/ecl-instances.png
```

其他检出目录请替换输出路径。完整模式表见根目录 README“界面快照”章节，模式分支以 `LauncherUiSnapshot.java` 为准。

## 注意事项

- 默认模式为 `home`，默认输出 `ecl-gui/build/visual-qa/ecl-home.png`；复现 README 首页应显式使用 `forest`。
- 快照使用 `ecl-gui/build/ui-preview-appdata/`，不读取真实 ECL 配置；此目录会复用，已有测试实例可能出现在后续模式中。
- `createVisualProfile` 同时创建启动元数据与配置游戏根目录下的实例目录，保证示例可被实例扫描识别。
- 在线模式会多等待网络结果；普通离线快照只能验证布局与夹具状态，不能替代真实下载或启动验收。
- `content-server` 是旧模式名称，现在退回整合包分类；服务端安装入口为“游戏实例 → 服务端”。
- 新 PNG 是由实际 JavaFX 页面重新渲染生成的，示例实例与路径均属于隔离预览夹具。
