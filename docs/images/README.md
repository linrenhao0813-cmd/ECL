# 界面截图说明

展示截图和已生成的界面快照已于 2026-10-25 清理，不再保存在本目录；本文件保留模式、历史核对记录和生成命令。界面修改后仍应使用 `captureLauncherUi` 在隔离预览目录下重新生成并查看实际图片，不用修图或占位图代替。

## 历史展示截图

以下截图于 2026-10-18 使用 Windows、JDK 21 和 `1.0.2beta` 代码生成，并已逐张查看。

| 文件 | 快照模式 | 内容 |
| --- | --- | --- |
| `launcher-home.png` | `forest` | 森林首页，示例实例“生存世界 / 原版探索” |
| `launcher-downloads.png` | `downloads` | 下载页“游戏实例”分类；共 5 个分类，离线预览不加载在线版本清单 |
| `launcher-settings.png` | `settings-page` | 设置页“通用”标签，窗口与启动区域折叠 |

实例维护入口另以 `local-versions` 模式核对，临时快照输出到 `ecl-gui/build/visual-qa/ecl-instances.png`。该模式展示启动设置、检查与修复、恢复上次升级和更多操作。
2026-10-24 审核整改重新生成并逐张查看以下隔离快照；表中路径为当时的输出记录，图片现已清理：

| 快照模式 | 本轮输出（仓库相对路径） | 核对内容 |
| --- | --- | --- |
| `forest` | `ecl-gui/build/visual-qa/remediation-forest.png` | 首页、实例选择及启动/升级入口 |
| `local-versions` | `ecl-gui/build/visual-qa/remediation-local-versions.png` | 实例维护按钮与布局 |
| `saves-details` | `ecl-gui/build/visual-qa/remediation-saves-details.png` | 正在浏览的世界所属实例与备份管理入口 |
| `backups-en` | `ecl-gui/build/visual-qa/remediation-backups-en.png` | 固定实例的英文备份弹窗 |
| `local-servers` | `ecl-gui/build/visual-qa/remediation-local-servers.png` | 本地服务端表单与更正后的下载入口提示 |

这些快照用于布局核对；超时停止、异步关闭与备份目标隔离由 headless 回归验证，不把静态图片作为运行证明。

2026-10-22 的旧版资源、日志配置与依赖修复另用 `local-versions` 模式生成并查看
`ecl-gui/build/visual-qa/ecl-review-fixes.png`，核对实例维护入口与现有布局；客户端日志配置分类的简体、英文和繁体文案由 `ResourceBundleI18nTest` 验证。
本轮文案变更另用 `backups-en` 与 `modrinth-en` 核对英文布局，输出 `ecl-gui/build/visual-qa/ecl-backups-en.png` 和 `ecl-modrinth-en.png`。

## 重新生成

2026-10-25 同职责文件合并后，使用 `downloads` 模式重新生成并查看
`ecl-gui/build/visual-qa/file-consolidation-downloads.png`：游戏实例与模组、光影包、材质包、整合包五个分类入口显示正常。
四类内容的兼容条件与实例目录映射另由 `ContentTargetTest` 校验；离线快照不作为在线下载验收。

同日扩大合并范围后，使用 `modrinth-en` 模式重新生成并查看
`ecl-gui/build/visual-qa/file-consolidation-modrinth-en.png`，核对模组浏览页和 Installed / Updates 标签入口；该空列表快照不证明实际模组行或在线更新结果。

同日全仓库审查与死代码清理后，运行默认 `home` 模式并查看 `ecl-gui/build/visual-qa/ecl-home.png`，
核对首页、导航与启动入口。隔离预览目录保留的 `visual-fabric-instance` 正常展示；本次仅删除未调用及测试专用转发，无可见布局调整。

同日修复事务日志校验、实例 session、设置合并与服务器状态刷新后，重新运行默认 `home` 模式查看首页，
并用 `servers` 模式查看 `ecl-gui/build/visual-qa/servers.png`：服务器分类栏、“刷新列表”按钮与结果列表显示正常。
本次未改动布局，服务器页改动只在手动刷新时清除旧状态并重新探测，快照不证明在线状态结果。

在仓库根目录执行：

```powershell
.\gradlew.bat captureLauncherUi -PuiSnapshotMode=forest -PuiSnapshotPath=E:/ECL/ecl-gui/build/visual-qa/launcher-home.png
.\gradlew.bat captureLauncherUi -PuiSnapshotMode=downloads -PuiSnapshotPath=E:/ECL/ecl-gui/build/visual-qa/launcher-downloads.png
.\gradlew.bat captureLauncherUi -PuiSnapshotMode=settings-page -PuiSnapshotPath=E:/ECL/ecl-gui/build/visual-qa/launcher-settings.png
.\gradlew.bat captureLauncherUi -PuiSnapshotMode=local-versions -PuiSnapshotPath=E:/ECL/ecl-gui/build/visual-qa/ecl-instances.png
```

其他检出目录请替换输出路径。完整模式表见根目录 README“界面快照”章节，模式分支以 `LauncherUiSnapshot.java` 为准。

## 注意事项

- 默认模式为 `home`，默认输出 `ecl-gui/build/visual-qa/ecl-home.png`；复现历史展示的森林首页应显式使用 `forest`。
- 快照使用 `ecl-gui/build/ui-preview-appdata/`，不读取真实 ECL 配置；此目录会复用，已有测试实例可能出现在后续模式中。
- `createVisualProfile` 同时创建启动元数据与配置游戏根目录下的实例目录，保证示例可被实例扫描识别。
- 在线模式会多等待网络结果；普通离线快照只能验证布局与夹具状态，不能替代真实下载或启动验收。
- `content-server` 是旧模式名称，现在退回整合包分类；服务端安装入口为“游戏实例 → 服务端”。
- 新 PNG 是由实际 JavaFX 页面重新渲染生成的，示例实例与路径均属于隔离预览夹具。

## 2026-10-26 整改快照

本轮重新运行 `captureLauncherUi` 并逐张查看下列 1440×736 隔离快照，输出保留在生成目录，不提交 PNG：

| 模式 | 输出 | 核对内容 |
| --- | --- | --- |
| `forest` | `ecl-gui/build/visual-qa/repair-forest.png` | 首页实例选择、启动与升级入口 |
| `local-servers` | `ecl-gui/build/visual-qa/repair-local-servers.png` | 简体中文服务端表单首屏 |
| `local-servers-en` | `ecl-gui/build/visual-qa/repair-local-servers-en.png` | 英文服务端表单首屏 |
| `local-servers-zh-tw` | `ecl-gui/build/visual-qa/repair-local-servers-zh-tw.png` | 繁体中文服务端表单首屏 |

本地服务端页面下部仍按既有滚动布局展示。这些静态快照不覆盖等待 stop 时的动态状态；持续日志刷新、禁止重复 stop、继续浏览列表及成功/超时结果由 `ServerManagementPageTest` 验证。快照命令出现既有的 JavaFX unnamed module 配置警告，均正常生成图片并成功退出。

## 2026-10-29 实例与设置导航调整

以下隔离快照已重新生成并逐张查看，图片保留在 `ecl-gui/build/visual-qa/`，不提交 PNG：

| 模式 | 输出文件 | 核对内容 |
| --- | --- | --- |
| `local-versions` | `navigation-mod-management.png` | 实例菜单仅显示已安装模组和更新；新模组搜索仍在下载页 |
| `settings-page` | `navigation-global-settings.png` | 全局/实例范围标签，全局保留原五类设置 |
| `settings-page-instances` | `navigation-instance-settings.png` | 实例列表、概览及启动设置/检查修复/恢复升级/更多操作/打开目录 |
| `settings-page-instances-compact-en` | `navigation-instance-settings-en.png` | 英文范围标签、1180×720 紧凑实例列表；详情位于下方滚动区域 |

`settings-page-instances` 是本轮新增的实例设置预览模式；`local-versions` 现在预览实例菜单的模组管理。
快照使用复用的隔离夹具，可能展示其他预览模式留下的实例。草稿保护、范围切换、启动目标切换及
全局游戏目录在异步扫描期间变更后改回的场景由 headless 回归验证；空模组列表截图不证明在线更新或真实游戏启动结果。
