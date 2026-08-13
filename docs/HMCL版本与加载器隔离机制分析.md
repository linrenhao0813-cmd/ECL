# HMCL 版本与模组加载器隔离机制分析报告

> 分析对象：HMCL-dev/HMCL 最新主干代码（2026-08 拉取，浅克隆于 `E:\ECL\.workbuddy\tmp\HMCL-src`，对应新式 `GameInstanceManifest` / `patches` 实例模型）
> 覆盖范围：版本隔离的实现机制、加载器隔离策略、多版本共存与冲突处理，以及由此提炼的技术方案。

---

## 1. 总体设计思路

HMCL 的隔离机制建立在一条核心原则上：

> **"一个实例 = 一个版本目录"（instance-centric），实例元数据、运行目录、实例级配置三者在文件系统上全部围绕 `versions/<id>/` 这一个目录展开。**

在此基础上，隔离与否**不是一个全局开关，而是一次"运行目录（Run Directory）决策"**：游戏的所有可变数据（`mods/`、`saves/`、`config/`、`logs/`、`resourcepacks/`、`shaderpacks/`、`options.txt` 等）都跟随运行目录走，启动器只负责计算"这次启动把游戏根目录定在哪里"，隔离自然成立。

整个体系分为四层：

```
┌───────────────────────────────────────────────────────────────┐
│  GameDirectory 层   多个游戏根（.minecraft / 自定义目录）         │
│  └─ HMCLGameRepository：按 GameDirectory 建仓库，管理实例        │
│       └─ 实例层  versions/<id>/  = 版本元数据 + 实例根            │
│            ├─ <id>.json     实例清单（元数据+patches）           │
│            ├─ <id>.jar      客户端 jar（可继承自其他实例）        │
│            ├─ .hmcl/config/ 实例级设置（运行目录/Java/参数覆盖）   │
│            ├─ natives-<os>  原生库解压目录（实例级）              │
│            └─ libraries/    实例私有库（"local" hint）           │
│       └─ 共享层  libraries/（全局库）、assets/（全局资源）        │
└───────────────────────────────────────────────────────────────┘
```

### 1.1 数据与运行分离

- **不可变、可跨实例共享**：客户端 jar、依赖库（`libraries/`）、资源（`assets/`）按内容寻址/按坐标寻址全局存放，所有实例复用。
- **可变、需按实例隔离**：mods、存档、配置、日志、运行目录。

### 1.2 从"继承"走向"补丁"

HMCL 演进过程中保留了两种实例模型：

| 模型 | 说明 | 适用场景 |
|---|---|---|
| `inheritsFrom` 继承链 | 子版本声明父版本 id，解析时沿链 `merge`（子覆盖父） | 旧版 / 外部安装器 / 老版本文件兼容 |
| `patches` 补丁列表 | 实例自身内嵌一组有序补丁，按 `priority` 合并 | HMCL 当前安装加载器的标准方式 |

新式加载器安装**不再生成 `inheritsFrom` 的独立版本**，而是把加载器写成实例清单里的一个 `GameInstancePatch`（`id`=加载器类型、`version`=加载器版本、`priority`=加载器优先级），与 vanilla 元数据在同一个 `<id>.json` 内。解析时 `DefaultGameRepository.Status.resolve()` 先合并继承链，再按 priority 升序应用补丁，产出两个视图：

- `launchManifest`：折叠完成、可直接用于启动的最终清单（无 inheritsFrom、无未决 patch）；
- `standaloneManifest`：保留补丁结构的独立视图（用于回写、升级加载器、`LibraryAnalyzer` 分析）。

### 1.3 隔离是"策略化"的

默认隔离策略通过 `DefaultIsolationType` 表达：

```java
public enum DefaultIsolationType {
    ALWAYS,   // 所有实例默认隔离
    MODDED,   // 仅带模组加载器的实例隔离（默认值）
    NEVER,    // 都不隔离
}
```

决策函数（`HMCLGameRepository.shouldIsolateNewInstance(modded)`）：`MODDED` 时由 `LibraryAnalyzer.isModded(resolved)`（按最终 mainClass 前缀判定：`net.minecraftforge` / `net.neoforged` / `net.fabricmc` / `org.quiltmc` / `cpw.mods` / `top.outlands`…）决定。

---

## 2. 核心数据结构

### 2.1 `GameInstanceManifest`（实例清单，record）

```java
public record GameInstanceManifest(
    GameInstanceID id,                // 实例唯一 id（即目录名）
    String minecraftArguments,        // 旧式参数（<1.13）
    Arguments arguments,              // 新式结构化参数
    String mainClass,                 // 入口类（原版/加载器改写）
    GameInstanceID inheritsFrom,      // 父版本（继承模型，新装实例为 null）
    GameInstanceID jar,               // 客户端 jar 来源（默认 = id）
    AssetIndexInfo assetIndex,        // 资源索引
    String assets,                    // 资源版本（legacy/1.7.10…）
    Integer complianceLevel,
    GameJavaVersion javaVersion,      // 所需 Java 大版本
    List<Library> libraries,          // 依赖库（含加载器库）
    List<CompatibilityRule> compatibilityRules, // 平台适用规则
    Map<DownloadType, DownloadInfo> downloads,  // client/server 下载
    Map<DownloadType, LoggingInfo> logging,
    ReleaseType type, Instant time, Instant releaseTime,
    Integer minimumLauncherVersion,
    Boolean root,                     // 是否为根版本
    Boolean hidden,                   // 是否隐藏（补丁视图内部标记）
    List<GameInstancePatch> patches,  // 内嵌补丁列表
    JsonObject rawJson                // 原始 JSON（无损回写）
) { ... }
```

关键方法：

- `merge(parent)`：继承合并语义——子字段为空则取父值，`libraries` 合并、`arguments` 合并、`compatibilityRules` 取并集、`minimumLauncherVersion` 取 max；
- `toPatch()` / `addPatch()`：清单与补丁互转；`addPatches` 按 `patch.id()` 去重；
- `getAssetIndex()`：兼容老版本无 assetIndex 时按 assets 值补内置 hash。

### 2.2 `GameInstancePatch`（加载器补丁）

补丁是加载器隔离的载体，携带与清单同构的字段子集，外加：

- `id`：加载器 patch id（`forge` / `fabric` / `quilt` / `neoforge` / `liteloader` / `cleanroom` / `optifine`…）；
- `version`：加载器版本（例如 `47.2.0`、`0.15.11`）；
- `priority`：合并顺序（加载器一般用 `PRIORITY_LOADER`）；
- `hidden`：`resolved.*` 内部补丁标记。

**隔离意义**：加载器的主类改写（mainClass）、额外库、参数（如 `--fml.forgeVersion`、`--fml.neoForgeVersion`）全部封装在补丁里，只作用于该实例，天然不污染其他版本。

### 2.3 `GameInstanceID`

实例 id 的强类型封装（内部 uuid/字符串），目录名与 json 名严格一致；`isValidInstanceId` 禁止 `modpack/minecraftinstance/manifest` 等与整合包配置文件冲突的名字。

### 2.4 `LibraryAnalyzer.LibraryType`（加载器识别表）

用 `group/artifact` 正则匹配最终清单库列表，识别加载器种类与版本：

```java
FABRIC   (net\.fabricmc,          fabric-loader)
QUILT    (org\.quiltmc,           quilt-loader)
FORGE    (net\.minecraftforge,    forge|fmlloader)   // 排除 neo
NEO_FORGE(net\.neoforged\.fancymodloader, core|loader)
LITELOADER(com\.mumfrey,          liteloader)
OPTIFINE ((net\.)?optifine, …)
LEGACY_FABRIC / CLEANROOM / FABRIC_API / QUILT_API / BOOTSTRAP_LAUNCHER …
```

- `isModded()`：mainClass 前缀判断（用于默认隔离策略）；
- `getModLoaders()`：返回实例实际携带的加载器集合（用于 Mod 管理兼容性判断）；
- `LibraryStatus`：`CLEAR`（patch 明确存在，结构清晰）vs `JUST_EXISTED`（仅靠正则猜测，来自外部安装器）。

### 2.5 `GameSettings`（预设 + 实例覆盖）

```
GameSettings（sealed）
├── Preset   : 全局/目录级设置预设（Java、内存、运行目录、默认隔离策略 defaultIsolationType…）
└── Instance : 实例级覆盖（存于 <实例根>/.hmcl/config/instance-game-settings.json）
     ├── parent            : 引用的预设 id
     ├── overrideProperties: 被本实例覆盖的属性名集合（如 PROPERTY_RUNNING_DIRECTORY）
     └── 具体属性           : runningDirectory / java / maxMemory / window …
```

**隔离的关键就在这里**：`PROPERTY_RUNNING_DIRECTORY` 出现在 `overrideProperties` 中 = 该实例被标记为"使用独立运行目录"。

### 2.6 `GameDirectory` / `GameDirectories` / `GameDirectoryID`

- 游戏根目录是多实例根级实体（不止 `.minecraft` 一个），存储在 `config/game-directories.json`（工作区）与 `config/user-game-directories.json`（用户级），本地优先合并；
- 每个 `GameDirectory` 有稳定 UUID 与路径，`GameDirectoryManager` 按身份缓存 `HMCLGameRepository` 实例。

### 2.7 `DefaultGameRepository.Status` / `InstanceHolder`

- `Status`：仓库的不可变快照（baseDirectory + 实例索引 `TreeMap<GameInstanceID, InstanceHolder>`）；
- `InstanceHolder`：实例 + 惰性缓存（resolvedManifest、gameVersion）；
- `refresh()` 前发 `RefreshingInstancesEvent`，完成后发 `RefreshedGameInstancesEvent`，事件驱动 UI 与选择态刷新。

---

## 3. 目录布局（隔离的文件系统模型）

以游戏根目录 `G`（如 `.minecraft`）为例：

```
G/
├── versions/                    # 实例目录（版本隔离的核心）
│   ├── 1.20.1/                  # 原版实例
│   │   ├── 1.20.1.json
│   │   └── 1.20.1.jar
│   └── 1.20.1-Fabric/           # 加载器实例（同 MC 版本另一个实例）
│       ├── 1.20.1-Fabric.json   #   内含 fabric patch
│       ├── 1.20.1-Fabric.jar    #   客户端 jar（可复用原版 jar 引用）
│       ├── .hmcl/
│       │   └── config/instance-game-settings.json  # 实例级设置（隔离标记在此）
│       ├── natives-windows/     # 实例级原生库解压
│       ├── libraries/           # 实例私有库（"local" hint）
│       └── mods/ saves/ config/ logs/ …  # ← 隔离时运行目录 = 此处
├── libraries/                   # 全局共享依赖库（按 Maven 坐标寻址）
├── assets/                      # 全局共享资源（objects/indexes/virtual）
├── launcher_profiles.json       # 兼容 Forge/LiteLoader 安装器生成的兜底文件
└── mods/ saves/ …               # ← 未隔离实例的运行目录 = 此处
```

规则要点：

- **实例根 = `versions/<id>/`**，`getInstanceRoot()`；
- **运行目录 = `getRunDirectory(id)`**（见 §5 决策链）；
- **库目录 = 全局 `libraries/`**，但 `hint == "local"` 的库解析到实例根 `libraries/`（`getLibraryFile`），实现"仅本实例可见的私有库"；
- **原生库目录 = `versions/<id>/natives-<platform>`**（`getNativeDirectory`），按平台区分，实例级；
- **资源目录 = 全局 `assets/`**（`getAssetDirectory` 恒定返回 base），旧版 virtual 资源在 `assets/virtual/<id>/`，需要时按索引重建（`reconstructAssets`）。

---

## 4. 关键流程

### 4.1 实例扫描与加载（refresh）

```
refreshImpl()
  ├─ 兼容 Classic 老版本目录（bin/lwjgl.jar 等）
  ├─ 遍历 versions/ 下每个目录
  │    ├─ GameInstanceID 校验（非法 id 跳过）
  │    ├─ json 缺失时按"目录内唯一 json"自动重命名纠正（含 jar）
  │    ├─ 解析 GameInstanceManifest；损坏 json 触发 GameJsonParseFailedEvent（用户可修复重试）
  │    ├─ 目录名与 manifest.id 不一致时自动 moveInstanceFiles 纠正
  ├─ 逐实例 resolve()（继承链 + patches 合并）
  │    └─ 继承自不存在版本 → 忽略（NoSuchGameInstanceException）
  │    └─ compatibilityRules 不适配当前平台 → 不加载
  └─ 发布 RefreshedGameInstancesEvent
```

### 4.2 清单解析与合并（resolve）

```
resolve(manifest, resolvedSoFar)
  ├─ 无 inheritsFrom → launchManifest = manifest（补丁单独应用）
  ├─ 有 inheritsFrom →
  │    ├─ 环检测（resolvedSoFar 防循环）
  │    ├─ 递归 resolve 父实例
  │    └─ launchManifest = manifest.merge(parentLaunchManifest)
  │       standaloneManifest = parent.standalone + 追加子 patch
  ├─ patches 按 priority 升序逐 patch merge
  └─ 产出 Resolved(unresolved, launchManifest, standaloneManifest)
```

合并语义（`merge`）是"**子覆盖父，集合取并**"：主类、参数、库、资源索引、Java 版本、下载信息、logging 由子实例优先；`libraries` 与 `arguments` 合并。

### 4.3 加载器安装（版本生成）

两类安装流，最终殊途同归——**在目标实例的 json 里挂一个加载器 patch**：

| 加载器 | 安装方式 | patch 内容来源 |
|---|---|---|
| Fabric / Quilt / LegacyFabric | 拉取 `launcherMeta.json`，纯 JSON 拼装库 | `FabricInstallTask` 等直接 `new GameInstancePatch(FABRIC, loaderVersion, PRIORITY_LOADER, args, mainClass, libs)` |
| LiteLoader | 拉取 `litemod.json` / 版本描述，同 Fabric 模式 | `LiteLoaderInstallTask` |
| Forge / NeoForge | 下载官方 `installer.jar`（SHA-1 校验）→ 运行 `--installClient` / 自解包 | `ForgeNewInstallTask`：把 installer 内 `maven/` 库与 universal jar 复制进仓库 `libraries/`，用处理器产物生成 `GameInstancePatch.fromManifest(...)`；旧版走 `ForgeOldInstallTask` 解 universal jar |
| Cleanroom / OptiFine | 混用上述两种模式 | 见 `DefaultDependencyManager.installLibraryAsync` |

流程：

```
UI（InstallersPage）确认 游戏版本 + 加载器
  ├─ 版本名 = 游戏版本 + "-" + 加载器名（如 1.20.1-Fabric），用户可改
  ├─ instanceIdConflicts() 冲突校验（Windows 大小写不敏感）
  └─ DefaultGameBuilder.buildAsync()
       ├─ 下载原版/父版本元数据
       ├─ 挂加载器 patch（addPatch 按 id 去重）
       ├─ MaintainTask.maintainPreservingPatches()：patch 合并进顶层字段，同时保留 patches
       ├─ repository.saveAsync() → 写 <id>.json → 注册 InstanceHolder
       └─ 失败回滚 removeInstanceFromDisk()
```

### 4.4 启动（游戏目录传递链）

```
LauncherHelper.launch0
  └─ repository.getLaunchOptions(id, java, baseDirectory, …)   // gameDir 参数仅用于 -Duser.home 等
      └─ DefaultLauncher.launch
           ├─ getConfigurations(): ${game_directory} → repository.getRunDirectory(manifest.id())
           ├─ 进程工作目录 directory() = 同一 runDirectory
           ├─ natives 解压至 getNativeDirectory()（实例根 natives-<platform>）
           ├─ classpath：全局 libraries/ + 实例本地库（local hint）
           └─ 环境变量 INST_DIR=实例根、INST_MC_DIR=运行目录
```

> 结论：**`--gameDir` 与进程 cwd 的唯一事实来源就是 `getRunDirectory()`**；隔离实例跑在 `versions/<id>/`，非隔离实例跑在游戏根。mods/saves 等一切跟随运行目录的目录自动隔离。

### 4.5 实例复制（duplicateInstance）

- 复制实例根（排除 `<src>.jar/json`、可选排除 `saves`、以及整合包黑名单）；
- 新清单 `withId(dst).withJar(dst)`，复制 jar/json 并改名；
- 复制实例设置，强制加 `PROPERTY_RUNNING_DIRECTORY` 覆盖并清空 runningDirectory（保证副本隔离）；
- 若源实例原本非隔离（运行目录 != 实例根），额外复制其运行目录内容到新运行目录。

### 4.6 实例删除（removeInstanceFromDisk）

- 事件前置（可被插件 DENY）；
- 目录改名为 `<id>_removed` → 优先移入回收站（`FileUtils.moveToTrash`），失败则清理 json 后删目录；
- 完成后异步 refresh。

---

## 5. 隔离决策链（getRunDirectory 全链路）

这是整份方案最核心的一张图：

```
getRunDirectory(instanceId)
 ├─ ① 实例是整合包（isModpack / beingModpackInstances）→ 返回 实例根（永远隔离）
 ├─ ② 实例级设置存在且 overrideProperties 含 PROPERTY_RUNNING_DIRECTORY
 │     └─ runningDirectory 非空 → 返回该自定义路径
 │        runningDirectory 为空   → 返回 实例根（隔离）
 └─ ③ 否则 → 父级 Preset 的 runningDirectory
       ├─ 非空 → 返回预设路径
       └─ 空   → 返回 游戏根 baseDirectory（不隔离）
```

配合安装时机的默认策略应用：

```
applyDefaultIsolationSettingForNewInstance(id, modded)
  └─ shouldIsolateNewInstance(modded)
       ├─ ALWAYS → true
       ├─ MODDED → LibraryAnalyzer.isModded(resolved)   // 默认
       └─ NEVER  → false
  └─ 需要隔离 → overrideProperties += PROPERTY_RUNNING_DIRECTORY（runningDirectory 留空 = 实例根）
```

**隔离的判定/落点全部收敛在这两个方法**：安装时打标记，启动时读标记。UI、Mod 管理、崩溃分析、日志导出、整合包导出均通过 `getRunDirectory()` 拿到同一路径，不会出现"目录判断与启动参数不一致"的分叉。

---

## 6. 模组加载器隔离策略小结

1. **版本级隔离（文件系统）**：每个加载器组合 = 一个独立实例（独立 `versions/<id>/`），id 冲突检测防覆盖；加载器 patch 只作用于本实例。
2. **元数据级隔离（patch）**：加载器主类、库、参数封装在 `GameInstancePatch`，按 priority 与 vanilla 元数据合并；加载器版本不进入实例名（存在 patch.version）。
3. **库级隔离（分层）**：全局 `libraries/` 共享 + 实例 `libraries/` 私有（`hint=local`）；Forge 的 installer 产物按 Maven 坐标落全局库，universal 等实例相关产物放实例目录。
4. **原生库隔离**：`natives-<platform>` 在实例根下，互不覆盖。
5. **运行时级隔离（classpath/启动参数）**：不同加载器的 mainClass 与 JVM 参数由各自 manifest/patch 决定；**类加载器隔离不发生在启动器层**——同一 JVM 进程内，类加载器/模块化（如 ModLauncher 的 layer 机制、Fabric 的 DiscoveryService）由加载器自身实现，启动器只保证"目录与 classpath 正确"。
6. **Mod 管理兼容性**：`ModManager` 目录 = `getRunDirectory().resolve("mods")`（随隔离走）；扫描 jar 内 `fabric.mod.json` / `META-INF/mods.toml` / `neoforge.mods.toml` / `mcmod.info` / `quilt.mod.json` / `litemod.json`，用 `LibraryAnalyzer.getModLoaders()` 与实例加载器匹配选择 reader；下载侧（Modrinth/CF）按 `targetLoaders ∩ addonVersion.loaders()` 过滤版本。

---

## 7. 多版本共存与冲突处理

| 场景 | HMCL 处理方式 |
|---|---|
| 同一 MC 版本多个加载器 | 各自独立实例（`1.20.1-Fabric`、`1.20.1-Forge`…），共享 client jar/库/资源，运行目录隔离 |
| 同一实例二次装加载器 | 安装页按"基础版本 + 加载器"组合；patch 按 id 去重，同 id 补丁被替换 |
| 实例 id 冲突 | UI 层 `instanceIdConflicts`（Windows 大小写不敏感）+ 安装失败回滚 |
| 继承链循环 / 父缺失 | `resolvedSoFar` 环检测 + 父缺失时跳过（`NoSuchGameInstanceException`） |
| 全局库版本冲突 | Maven 坐标（group/artifact/version）定位路径，不同版本可共存；classpath 按 manifest 顺序 |
| 实例名与目录名不一致 | 扫描时 `moveInstanceFiles` 自动纠正 |
| 删除/重装 | 删除走"改名 → 回收站"双保险；重装重建元数据与库 |
| 外部安装器兼容 | 生成 `launcher_profiles.json` 兜底；对无法识别的库标记 `JUST_EXISTED`，卸载/升级时降级为保守处理 |
| 平台差异 | `compatibilityRules` 过滤不适配实例；Windows 大小写不敏感特判 |

---

## 8. 局限性与风险

1. **目录级隔离 ≠ 运行时隔离**：两个隔离实例若引用了同一全局库的不同版本，classpath 拼接顺序可能引入冲突（加载器自身处理）；启动器无法阻止 mod 之间的 API 兼容问题。
2. **共享命名空间冲突**：非隔离实例（`NEVER` 策略）共享游戏根，mods/saves 互相可见，只能靠用户自觉；`MODDED` 默认策略缓解了大部分场景。
3. **`libraries/` 全局复用带来的"脏库"问题**：Forge installer 把产物直接复制进全局库，版本升级/降级时旧版本残留；`MaintainTask` 只清理 patch 可见的库。
4. **补丁模型与外部安装器的兼容裂缝**：`LibraryStatus.JUST_EXISTED`（靠正则猜测的库）无法精确卸载/升级；`launcher_profiles.json` 只是兜底，TLauncher 等非标准格式需特判（`tlauncherVersion`）。
5. **`assets/` 全局共享**：版本间资源互相引用，删除某版本不会回收资源；`reconstructAssets` 虚拟资源重建在超大索引下有性能开销。
6. **Windows 文件系统约束**：实例 id 大小写不敏感、保留字（如 `modpack`、`minecraftinstance`）被禁用；长路径与非法字符受 `isValidInstanceId` 限制。
7. **patch 合并顺序耦合**：多个补丁（如 Forge + OptiFine）的合并依赖 priority 约定，非 HMCL 安装的组合可能出现顺序错误。
8. **多游戏根增加心智负担**：`GameDirectory` 概念使"版本在哪个根"由全局选中态决定，切换根后实例列表完全不同，迁移/导入需额外处理。

---

## 9. 附录：ECL 落地状态

本报告提出的优先项已在 ECL 中落地，继承链继续作为现有版本元数据的兼容模型；加载器信息在不破坏标准版本 JSON 的前提下使用 ECL 扩展字段结构化保存。

| 维度 | ECL 落地实现 |
|---|---|
| 隔离标记 | `<实例根>/.ecl/config/instance-game-settings.json`；支持跟随策略、独立实例目录和自定义目录 |
| 默认策略 | `DefaultIsolationType.ALWAYS / MODDED / NEVER`，默认 `MODDED`；整合包始终隔离 |
| 单一目录入口 | `DefaultGameRepository.runDirectory()`；GUI、CLI、Mod 管理、内容导入、备份与启动共用该结果 |
| 加载器元数据 | 保留 `inheritsFrom`，新增 `eclModLoaderVersion`；`ModLoaderInfo` 暴露加载器、版本与识别来源 |
| 加载器识别 | `LibraryAnalyzer` 按显式字段、Maven 坐标和最终 `mainClass` 分层识别 |
| 实例私有库 | `hint=local` / `eclHint=local` 从 `<实例根>/libraries/` 解析 |
| 原生库隔离 | 解压到 `<实例根>/natives-<platform>/`，并向游戏进程提供 `INST_DIR` 与 `INST_MC_DIR` |

---

*附：源码关键位置（相对 HMCL 仓库根）*

- 仓库/扫描/合并：`HMCLCore/.../game/DefaultGameRepository.java`、`game/GameInstanceManifest.java`、`game/GameInstancePatch.java`
- 隔离决策/实例设置：`HMCL/.../game/HMCLGameRepository.java`、`setting/GameSettings.java`、`setting/DefaultIsolationType.java`
- 多游戏根：`HMCL/.../setting/GameDirectory*.java`、`setting/GameDirectories.java`
- 加载器安装：`HMCLCore/.../download/{fabric,quilt,forge,neoforge,liteloader,legacyfabric}/`、`download/DefaultDependencyManager.java`、`download/MaintainTask.java`
- 加载器识别：`HMCLCore/.../download/LibraryAnalyzer.java`
- 启动：`HMCLCore/.../launch/DefaultLauncher.java`
- Mod 管理：`HMCLCore/.../addon/mod/ModManager.java`、`addon/mod/LocalAddonManager.java`
