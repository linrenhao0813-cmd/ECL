# ECL 模组下载界面升级方案（对标 HMCL 设计思路）

> 落地状态（2026-08）：P0-1 至 P0-4、P1-1 至 P1-4、P2 CurseForge Provider 已完成；模组、光影、材质与整合包均已接入 Modrinth/CurseForge 双源。动态分类/多选筛选仍为可选后续项。

> 依据：HMCL 模组下载界面的设计思路（统一抽象 Repository + 实例上下文跟随 + 列表防抖/竞态 + 详情页推荐版本置顶 + 依赖分组与钻取 + 本地管理闭环）与本项目 ECL 现状盘点。
> 范围：ecl-gui 的 ModBrowserView / ModBrowserViewModel 及 ecl-core 的 com.ecl.modrinth 服务层。
> 原则：**后端能力已大体就位，本方案以"UI 体验补齐"为 P0，以"多源抽象落地"为 P1，以"增强与扩展"为 P2。**

---

## 1. 背景与目标

HMCL 模组下载页面的核心设计可浓缩为六点：① 统一抽象、UI 不感知数据源；② 上下文跟随实例（版本/加载器/安装目标自动取）；③ 列表防抖 + 竞态防护 + 分页；④ 推荐版本置顶；⑤ 依赖可见性与钻取；⑥ 与本地管理形成闭环。

对 ECL 现状盘点后发现：**②③④⑥ 后端已基本实现**（实例上下文 `VersionProfileModInstanceContext`、防抖/竞态已在 VM、推荐版本 `DefaultModVersionSelector.selectBestVersion`、依赖解析/事务/更新/本地管理均已闭环），真正的差距集中在 **UI 呈现层与多源抽象未接入**。

目标：在不破坏现有事务/校验/更新体系的前提下，补齐差距，形成与 HMCL 对齐的完整下载体验。

---

## 2. 现状盘点（能力矩阵）

| 能力 | 现状 | 位置 |
|---|---|---|
| 实例上下文跟随（MC 版本 + 加载器） | ✅ 已实现 | `instance/VersionProfileModInstanceContext` |
| 搜索防抖（400ms）+ 竞态防护 | ✅ 已实现 | `ModBrowserView` L193、`ModBrowserViewModel` requestGeneration |
| 推荐版本置顶 | ✅ 已实现 | `service/DefaultModVersionSelector.selectBestVersion` |
| 递归依赖解析 + 环检测 + 冲突 | ✅ 已实现 | `service/DefaultModDependencyResolver` |
| 事务化安装 + 备份回滚 + 崩溃恢复 | ✅ 已实现 | `transaction/FileModInstallationTransaction` |
| 下载哈希校验（SHA-512/1） | ✅ 已实现 | `download/ModFileDownloadService` + `HashVerifier` |
| 更新检查 / 批量更新 | ✅ 已实现（仅手动触发；Modrinth 按 SHA-1、CurseForge 按项目 ID） | `service/DefaultModUpdateService` |
| 本地管理（启停/卸载/导入） | ✅ 已实现 | `service/DefaultModManagementService` |
| 多源抽象（Provider + Registry） | ⚠️ 存在但闲置，VM 直接注入 `ModrinthApiClient` | `provider/`、`ModBrowserViewModel` L44 |
| 依赖分组展示 + 钻取 | ❌ 纯文本 `- type: projectId`，不可点击 | `ModBrowserView` L384-391 |
| 版本渠道 / 加载器标签 | ❌ 版本下拉仅 versionNumber·type·featured | `ModBrowserView` versionCell L730-740 |
| 变更日志格式化展示 | ❌ 原样 appendText | `ModBrowserView` L392-394 |
| 列表图标异步加载 + 失败占位 | ❌ 同步 `new Image(url)` | `ModBrowserView` ProjectCell L748-782 |
| 本地 jar 元数据解析 | ❌ 只校验完整性，名称/版本全靠 sha1 反查 | `service/DefaultLocalModScanner` |
| 更新提醒（角标/自动轮询） | ❌ 无 | — |
| 分类树 / 加载器与渠道筛选 UI | ❌ 分类硬编码 7 项 | `ModBrowserView` L171-186 |

---

## 3. 目标架构

```
┌────────────────────────────────────────────────────────────────┐
│ UI 层（ecl-gui）                                               │
│   ModBrowserView（双 Tab：浏览 / 已安装）                       │
│     ├─ 搜索区：关键词(防抖) · 分类 · 排序 · 数据源切换(新)       │
│     ├─ 列表：图标(异步+占位) · 标题 · 描述 · 徽标               │
│     ├─ 详情面板：推荐版本置顶(已有) + 版本标签(新) + 变更日志(新)│
│     │            依赖分组列表(新) → 点击钻取 → showProject      │
│     └─ 已安装 Tab：启停/卸载/回滚(已有) + 更新角标(新)           │
│   ModBrowserViewModel：状态/竞态/分页(已有) + 依赖分组模型(新)   │
└──────────────┬─────────────────────────────────────────────────┘
               │ 依赖接口（不再直接依赖 ModrinthApiClient）
┌──────────────▼─────────────────────────────────────────────────┐
│ 内容源抽象层（ecl-core）                                       │
│   ModMetadataProvider（已存在，充实方法集）                     │
│     ├─ search / getProject / getVersions / getChangelog        │
│     ├─ resolveDependency / getCategories                       │
│     └─ getLatestVersionsFromHashes / getVersionByFile(可选)    │
│   ModMetadataProviderRegistry（源切换，已有骨架）               │
└──────────────┬─────────────────────────────────────────────────┘
┌──────────────▼─────────────────────────────────────────────────┐
│ 实现层                                                         │
│   ModrinthContentProvider（把现 ModrinthApiClient 映射迁入）    │
│   CurseForgeContentProvider（P2 可选）                         │
│   服务层（resolver / update / scanner 逐步解耦到 Provider）     │
└────────────────────────────────────────────────────────────────┘
```

---

## 4. 分模块设计

### 4.1 依赖分组展示与钻取（P0，改动最小收益最大）

**现状**：`renderDetails` 把依赖逐行 `type: projectId` 追加进 TextArea，无法分组、无法跳转。

**目标**：
- 详情面板的依赖区改为**按 `DependencyType` 分组的可点击列表**（必需/可选/内嵌/不兼容）。
- 每个依赖条目点击 → 复用现有 `showProject(projectId)` 逻辑加载该依赖项目的详情面板（钻取）。
- 不兼容依赖红字标注、跳过下载（与 resolver 语义一致）。

**改动点**：
- 新增 `ecl-gui` 内依赖条目组件（如 `DependencyItem`）：图标 + 标题 + 类型徽标，`onClick` 触发钻取；
- `ModBrowserViewModel` 新增 `CompletableFuture<List<DependencyGroup>> loadDependencyGroups(ModVersion version)`：
  ```java
  record DependencyGroup(DependencyType type, List<ModProject> projects) {}
  ```
  内部并行 `apiClient.getProject(dependency.projectId())`（带 in-flight 合并，复用 `DefaultModrinthApiClient` 缓存）；
- `ModBrowserView.renderDetails` 中依赖 TextArea 替换为分组列表容器，加载态/失败态走现有 Spinner 模式。

**验收**：详情面板依赖可点击钻取；必需/可选/内嵌/不兼容分组清晰；不兼容项不可安装。

### 4.2 版本下拉标签与渠道标识（P0）

**现状**：版本 ComboBox 只显示 `versionNumber · versionType · featured`。

**目标**：下拉项通过 cell factory 显示：版本号 + **渠道徽标**（Release 绿 / Beta 橙 / Alpha 红）+ **加载器 badge**（Fabric/Forge/NeoForge/Quilt）+ featured 星标；默认选中项即"推荐版本"（现有 `preferredVersion()`），并在详情区高亮提示"推荐"。

**改动点**：`ModBrowserView` versionCell 自定义 ListCell（复用现有徽标样式）；仅展示，不改选择逻辑。

**验收**：版本下拉一眼可辨渠道与加载器，推荐版本默认选中并有标识。

### 4.3 变更日志视图（P0）

**现状**：changelog 原样拼入 TextArea（markdown 裸文本）。

**目标**：独立"更新日志"折叠区/Tab，做**最小化 markdown 渲染**（标题 `#`、列表 `-`、代码块、`\n` 分段），不做 HTML；空日志显示占位文案。**不引入新依赖**（评估过 markdown 库，为一条日志引入依赖不值）。

**改动点**：`ModBrowserView` 新增 changelog 区域 + `ecl-core util`（或 gui 内）加一个 60 行内的 `MinimalMarkdown` 格式化工具；`renderDetails` 拆分为 描述 / 依赖 / 更新日志 三区块（可折叠）。

**验收**：长 changelog 可读，代码块/列表不错位，空日志有兜底。

### 4.4 列表体验：图标异步加载 + 占位（P0）

**现状**：`ProjectCell` 同步 `new Image(url)`，网络慢时列表卡顿、失败无占位。

**目标**：异步加载 + 内存缓存 + 失败占位图（内置 `icon@4x.png` 风格占位）。

**改动点**：参考 HMCL `RemoteImageLoader` 思路，在 ecl-gui 新增 `RemoteImageLoader`（`CompletableFuture` 加载、`LoadingImage`/`BrokenImage` 占位、按 URL 缓存）；`ProjectCell` 与依赖条目共用。

**验收**：弱网下滚动不卡，图标加载失败显示占位不破版。

### 4.5 数据源抽象接入 UI（P1，架构关键项）

**现状**：`ModMetadataProvider` + `ModMetadataProviderRegistry` 存在但空转——VM 直接注入 `ModrinthApiClient`；`DefaultModDependencyResolver` / `DefaultModUpdateService` / `DefaultLocalModScanner` 也直依赖 `ModrinthApiClient`（hash 反查等）。

**目标**：VM 与相关服务只依赖 `ModMetadataProvider` 接口，UI 提供**数据源切换**入口（设置页或搜索栏下拉，`MODRINTH / CURSEFORGE`）。

**改动点（分三步，避免大爆炸式重构）**：

1. **充实接口**（`provider/ModMetadataProvider.java`）：
   ```java
   interface ModMetadataProvider {
       ContentSource source();
       CompletableFuture<SearchResult> search(SearchQuery query);            // 关键词/分类/排序/分页/兼容性
       CompletableFuture<ModProject> getProject(String id);
       CompletableFuture<List<ModVersion>> getVersions(String projectId, ModCompatibility compatibility);
       CompletableFuture<List<ModProject>> resolveDependencies(ModVersion v); // 依赖项目
       CompletableFuture<String> getChangelog(String projectId, String versionId);
       CompletableFuture<List<ModUpdate>> getLatestVersionsFromHashes(Set<String> sha1s, Set<ModLoader> loaders, String gameVersion);
       CompletableFuture<Optional<ModVersion>> getVersionByFile(Path file);   // 本地文件 hash 反查（可降级为空）
       CompletableFuture<List<Category>> getCategories();                     // 分类树
   }
   enum ContentSource { MODRINTH, CURSEFORGE }
   ```
2. **Modrinth 实现**：新增 `ModrinthContentProvider`，把现有 `ModrinthApiClient` 的 DTO→model 映射包一层（client 保留，做缓存/重试/限流职责；provider 做语义归一化：facet 搜索、渠道、加载器枚举、hash 反查）。
3. **接线**：`MainController` 装配处改为按选中 source 从 Registry 取 provider；`ModBrowserViewModel` 构造函数从 `ModrinthApiClient` 换成 `ModMetadataProvider`；`DefaultModDependencyResolver`/`DefaultModUpdateService`/`DefaultLocalModScanner` 的 hash 反查改调 provider（**不支持 hash 反查的源返回空，UI 降级显示 `local:` 而不报错**）。

**验收**：切换 source 后搜索/详情/依赖/更新全链路可用；Modrinth 行为与现状完全一致（回归测试重点）。

### 4.6 本地 jar 元数据解析增强（P1）

**现状**：`DefaultLocalModScanner.validateJar` 只做完整性/尺寸校验，本地 mod 名称/版本全靠 sha1→Modrinth 反查，离线不可识别。

**目标**：解析 jar 内元数据，离线也能识别名称/版本/加载器；hash 反查作为兜底。

**改动点**：参考 HMCL `ModManager.READERS` 思路，在 `DefaultLocalModScanner` 增加按文件选择的元数据读取器：
```java
record LocalModMeta(String id, String name, String version, ModLoader loader, boolean modded) {}
```
- `fabric.mod.json` → Fabric；`quilt.mod.json` → Quilt；`META-INF/mods.toml` → Forge；`neoforge.mods.toml` → NeoForge；`mcmod.info` → Forge(旧)；
- 解析失败/非 mod 文件回退现状（sha1 反查 → `local:`）；
- 扫描缓存 `launcher-mod-scan.json` 中同步缓存元数据，失效条件不变。

**验收**：断网环境下导入本地 mod 仍能显示正确名称/版本/加载器。

### 4.7 更新提醒（P1）

**现状**：更新仅手动触发"检查更新"。

**目标**：进入**已安装 Tab**时自动异步检查一次（TTL 30 分钟缓存，防每次切换都请求）；有更新时列表项显示"可更新"徽标 + Tab 标题角标（如 `已安装 (3)`）；保留手动"全部更新"。整合包实例沿用现有确认弹窗保护。

**改动点**：`ModBrowserViewModel` 增加 `ensureUpdatesChecked()`（幂等 + TTL）；`ModBrowserView` Tab 标题绑定更新计数；复用 `installedHealth`/`updates` 现有 Map。

**验收**：切到已安装 Tab 自动出角标；30 分钟内不重复请求；更新流程与手动一致。

### 4.8 CurseForge 源（P2，可选）

在 4.5 完成后实现 `CurseForgeContentProvider`（对照 Modrinth 实现映射：CF 的 addon/file API、gameVersion 筛选、loader 判定；本地文件使用官方 Murmur2 指纹接口识别，更新检查使用安装索引中的项目 ID）。**前置依赖：4.5 必须完成**，否则无从谈起。

### 4.9 分类树动态化 + 加载器/渠道筛选 UI（P2，可选）

- 分类：`getCategories()` 拉取分类树替代硬编码 7 项（Modrinth 提供 facets 分类）；
- 筛选：搜索区增加"加载器"（Fabric/Forge/…）与"发布渠道"（Release/Beta/Alpha）多选筛选（当前仅按实例上下文隐式过滤）。

---

## 5. 关键数据流（目标态）

### 5.1 浏览 → 安装（不变 + 增强）

```
搜索(防抖400ms) → searchResults → 点击项目 → showProject(详情+版本)
  → 版本下拉（推荐置顶 + 渠道/加载器徽标）
  → 依赖分组加载（并行 getProject，带缓存/in-flight 合并）
  → 安装按钮 → preparePlan（DefaultModDependencyResolver 递归+环检测）
  → previewPlan 预览（主模组/必需依赖/可选依赖 CheckBox/冲突警告）
  → FileModInstallationTransaction 提交 → HashVerifier 校验
  → 写 launcher-mods.json 索引 → 已安装列表刷新
```

### 5.2 更新（新增自动提醒）

```
进入已安装 Tab → ensureUpdatesChecked（TTL 30min）
  → DefaultModUpdateService.checkUpdates（sha1 批量反查）
  → updates/installedHealth → 角标 + 可更新徽标
  → 更新选中/全部 → SequentialBatchRunner → 依赖重解析 → 事务安装
```

### 5.3 依赖钻取（新增）

```
详情面板依赖分组 → 点击依赖项 → showProject(dep.projectId)
  → 依赖项目自身详情面板（可继续钻取，复用同一链路，天然支持环状引用展示但解析时已防环）
```

---

## 6. 实施拆分

| 阶段 | 任务 | 主要改动 | 工作量级 |
|---|---|---|---|
| P0-1 | 依赖分组 + 钻取 | `ModBrowserView` + `ModBrowserViewModel` | 中 |
| P0-2 | 版本下拉渠道/加载器徽标 | `ModBrowserView` versionCell | 小 |
| P0-3 | 变更日志最小化渲染 | `ModBrowserView` + 新 `MinimalMarkdown` | 小 |
| P0-4 | 图标异步加载 + 占位 | 新 `RemoteImageLoader` + `ProjectCell` | 小 |
| P1-1 | Provider 接口充实 + Modrinth 实现 + VM 接线 | `provider/` + `ModrinthContentProvider` + `ModBrowserViewModel` + `MainController` | 大 |
| P1-2 | 服务层解耦到 Provider（resolver/update/scanner） | `service/` 三个 Default* | 大 |
| P1-3 | 本地元数据解析 | `DefaultLocalModScanner` + `LocalModMeta` | 中 |
| P1-4 | 更新提醒角标 | `ModBrowserViewModel` + `ModBrowserView` Tab | 小 |
| P2-1 | CurseForge 实现 | 新 `CurseForgeContentProvider` | 大 |
| P2-2 | 分类树动态化 + 筛选 UI | `ModBrowserView` 搜索区 | 中 |

**建议顺序**：P0 四件套先行（纯 UI 改动、无回归风险、体验立竿见影）→ P1-1/P1-2 架构落地（期间保持 Modrinth 行为不变，作为回归基线）→ P1-3/P1-4 → P2 视需要推进。

---

## 7. 风险与注意事项

1. **多源抽象回归面大**：P1-1/P1-2 动到 VM 与服务层构造链（`MainController` 组合根）。对策：先加 provider 适配层、**保留 `ModrinthApiClient` 不删**，VM 切接口后以"Modrinth 行为与现状逐项一致"为回归基线；hash 反查等能力允许 provider 降级返回空，UI 不崩溃。
2. **changelog 渲染**：不引入 markdown 库（依赖体积/许可成本 > 收益），用 60 行内最小渲染器；特殊字符（代码块内的 `<` 等）注意转义。
3. **更新检查频率**：自动提醒必须 TTL 限流 + 幂等，避免频繁切 Tab 打爆 API（Modrinth 429 已有退避，但 UI 侧仍要控流）。
4. **依赖钻取的性能**：依赖项目详情加载走现有缓存与 in-flight 合并，避免重复请求；深度钻取时注意 UI 栈管理（返回路径）。
5. **整合包实例**：更新/卸载 mod 的确认保护（现有 `isModpack` 逻辑）必须保留。
6. **本地元数据解析的安全**：读取 jar 内 json 一律走防路径穿越的 Zip 读取（复用现有 `ZipUtil`），解析失败静默降级，不阻断安装。

---

## 8. 附录：与 HMCL 六条思路的对应关系

| HMCL 思路 | ECL 现状 | 本方案动作 |
|---|---|---|
| 统一抽象、多源可插拔 | Provider 闲置 | 4.5 接入 UI，4.8 落地第二源 |
| 上下文跟随实例 | ✅ 已有 | 无需改动，沿用 |
| 防抖 + 竞态 + 分页 | ✅ 已有 | 无需改动 |
| 推荐版本置顶 | ✅ 已有（selectBestVersion） | 4.2 补充 UI 可见性 |
| 依赖可见性与钻取 | ❌ 纯文本 | 4.1 分组 + 钻取 |
| 本地管理闭环 | ✅ 已有 | 4.6/4.7 补元数据解析与自动提醒 |
