# 数据格式说明

本文列出 ECL 在数据目录、游戏目录和实例目录中写入的持久化文件，以及每个字段在源码中的来源。字段名一律取自对应的 Java record／DTO 或 `JsonObject.addProperty` 的键名，**没有把握的含义标注为“含义待确认”，不做猜测**。

目录约定：

- `<数据目录>`：默认 `%APPDATA%\.ecl`（`ECLConfig.getBaseDir`：优先 `APPDATA`，缺失时回退 `user.home`）。
- `<游戏目录>`：默认 `%APPDATA%\.minecraft`（`ECLConfig.getGameDir`），可在设置中覆盖。
- `<实例目录>`：用于读取实例元数据的目录（`<游戏目录>/versions/<实例 ID>`）；`<运行目录>` 是实际启动游戏的工作目录。未隔离实例的 `<运行目录>` 就是 `<游戏目录>` 本身（`DefaultGameRepository.runDirectory`）。
- 除特别注明外，所有 JSON 均为 UTF-8，写入时使用临时文件 + 原子替换（`HttpUtil.writeJson` → `FileUtil.moveReplacing`）。

## settings.json

位置：`<数据目录>/settings.json`。实现：`ecl-core/src/main/java/com/ecl/config/SettingsManager.java`，键定义集中在 `ecl-core/src/main/java/com/ecl/ECLConfig.java`（`KEY_*` 常量，均为 `SettingKey`）。

`SettingsManager.save` 使用同目录 `settings.json.lock` 的 `FileLockLease` 覆盖重读、合并和原子发布；只应用当前对象修改或删除的键。`savedSettings` / `dirtyKeys` 是内存基线与待保存集合，不是 JSON 键。成功后缓存与基线同步到合并结果；失败保留待保存内容，回滚为原值则撤销对应待保存键。损坏文件归档也在该锁内重新读取；锁忙时不归档、不假装加载成功，可稍后重试。锁文件释放后保留，不含设置值。

**存储细节：**`SettingsManager.set(SettingKey<T>)` 实际调用 `setString(key.key(), value.toString())`，因此整数和布尔值也以 JSON 字符串形式保存（例如 `"downloadMaxConcurrent": "8"`、`"backupOnLaunch": "true"`）；读取时按 `SettingKey.type()` 解析，解析失败回退默认值。历史 `versionCategory` / `versionCategory2` 已无消费者；加载设置不再生成新的 `versionCategory2` 键。敏感值使用 `_enc_` 前缀键保存 AES-GCM 密文（`_enc_microsoftRefreshToken` 等），明文键在启动时迁移后删除（`MainController.migrateLegacySecrets`），密钥文件为 `<数据目录>/.secret.key`（Windows 下由 DPAPI 保护，`CryptoKeyStore`）。

| 键 | 类型 | 默认值 | 来源（`ECLConfig`） | 说明 |
| --- | --- | --- | --- | --- |
| `javaPath` | String | `""` | `KEY_JAVA_PATH` | 全局默认 Java 可执行文件路径；实例启动设置未覆盖时使用 |
| `gameDir` | String | `""` | `KEY_GAME_DIR` | 全局游戏根目录；空值表示使用默认 `%APPDATA%\.minecraft` |
| `jvmArgs` | String | `""` | `KEY_JVM_ARGS` | 旧版全局自定义 JVM 参数，仅作为新实例的迁移来源 |
| `maxMemoryMb` | Integer | `0` | `KEY_MAX_MEMORY_MB` | `0` 表示自动计算（`ECLConfig.AUTO_MEMORY_MB`），否则为 MiB |
| `microsoftRefreshToken` | String | `""` | `KEY_MICROSOFT_REFRESH_TOKEN` | Microsoft 刷新令牌，实际以 `_enc_` 键加密保存 |
| `microsoftAccessToken` | String | `""` | `KEY_MICROSOFT_ACCESS_TOKEN` | Microsoft 访问令牌，实际以 `_enc_` 键加密保存 |
| `microsoftAccessTokenExpiresAt` | Long | `0` | `KEY_MICROSOFT_ACCESS_TOKEN_EXPIRES_AT` | 访问令牌过期时间（epoch 毫秒） |
| `microsoftProfileName` | String | `""` | `KEY_MICROSOFT_PROFILE_NAME` | 当前 Microsoft 档案名（显示与皮肤选择用） |
| `microsoftProfileUuid` | String | `""` | `KEY_MICROSOFT_PROFILE_UUID` | 当前 Microsoft 档案 UUID |
| `selectedVersion` | String | `""` | `KEY_SELECTED_VERSION` | **启动目标**的实例 ID（实例栏切换的就是它） |
| `authType` | String | `"OFFLINE"` | `KEY_AUTH_TYPE` | `OFFLINE` / `MICROSOFT`；`YGGDRASIL` 只用于读取旧账号记录（`AuthType`） |
| `username` | String | `""` | `KEY_USERNAME` | 当前玩家名 |
| `modReleaseChannel` | String | `"RELEASE_AND_BETA"` | `KEY_MOD_RELEASE_CHANNEL` | 模组更新通道：`RELEASE_ONLY` / `RELEASE_AND_BETA` / `ALL`（`ReleaseChannel`） |
| `defaultIsolationType` | String | `"MODDED"` | `KEY_DEFAULT_ISOLATION_TYPE` | 新实例默认隔离策略：`ALWAYS` / `MODDED` / `NEVER`（`DefaultIsolationType`） |
| `gameWidth` | Integer | `1280` | `KEY_GAME_WIDTH` | 游戏窗口宽度 |
| `gameHeight` | Integer | `720` | `KEY_GAME_HEIGHT` | 游戏窗口高度 |
| `gameFullscreen` | Boolean | `false` | `KEY_GAME_FULLSCREEN` | 是否全屏启动 |
| `quickServer` | String | `""` | `KEY_QUICK_SERVER` | 下次启动的直连服务器地址 |
| `closeAfterLaunch` | Boolean | `false` | `KEY_CLOSE_AFTER_LAUNCH` | 启动游戏后最小化启动器 |
| `processorCount` | Integer | `0` | `KEY_PROCESSOR_COUNT` | `>0` 时向游戏追加 `-XX:ActiveProcessorCount`（`LaunchCommandBuilder`），`0` 表示不指定；界面上限为 `availableProcessors()` |
| `backupOnLaunch` | Boolean | `true` | `KEY_BACKUP_ON_LAUNCH` | 启动前自动备份（`GameLaunchCoordinator.createAutomaticBackupBeforeLaunch`） |
| `backupKeepCount` | Integer | `10` | `KEY_BACKUP_KEEP_COUNT` | 自动备份保留份数（界面范围 1–100，`GlobalGameSettingsPane.parseRangedInt`） |
| `backupIncludeMods` | Boolean | `false` | `KEY_BACKUP_INCLUDE_MODS` | 自动备份是否包含 `mods` 目录 |
| `downloadMaxConcurrent` | Integer | `DOWNLOAD_THREADS` | `KEY_DOWNLOAD_MAX_CONCURRENT` | 下载并行数；默认 `max(4, CPU 核数)` 且限制在 1–8，可用系统属性 `ecl.download.threads` 覆盖（最小 2） |
| `downloadRateLimitKb` | Integer | `0` | `KEY_DOWNLOAD_RATE_LIMIT_KB` | 单任务限速 KiB/s，`0` 表示不限速 |
| `language` | String | `"zh-CN"` | `KEY_LANGUAGE` | 界面语言：`zh-CN` / `zh-TW` / `en` |
| `firstRunCompleted` | Boolean | `false` | `KEY_FIRST_RUN_COMPLETED` | 是否已完成首次运行向导 |

示例（格式化后）：

```json
{
  "selectedVersion": "1.21.8",
  "authType": "OFFLINE",
  "username": "Steve",
  "language": "zh-CN",
  "downloadMaxConcurrent": "8",
  "downloadRateLimitKb": "0",
  "modReleaseChannel": "RELEASE_AND_BETA",
  "backupOnLaunch": "true",
  "_enc_microsoftRefreshToken": "…Base64…"
}
```

读取失败时（JSON 损坏、根不是对象等）：原文件被移动到同目录的 `settings.json.corrupt`，内存中重新从空设置开始（`SettingsManager.load`）。若该名字已被占用，则使用带时间戳的 `settings.json.corrupt-<yyyyMMdd-HHmmss-SSS>`（必要时再追加 `-<序号>`），不覆盖更早的损坏备份（`SettingsManager.uniqueCorruptBackup`）。

## instances/display.json

位置：`<数据目录>/instances/display.json`。实现：`ecl-core/src/main/java/com/ecl/game/InstanceDisplayMetadataStore.java`、`InstanceDisplayMetadata.java`。只影响展示，不影响启动。

| 键 | 类型 | 来源 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | int | `InstanceDisplayMetadataStore.CURRENT_SCHEMA_VERSION` | 当前为 `1` |
| `instances` | object | `InstanceDisplayMetadataStore.save` | 键是实例 ID（profile ID）；值见下 |

`instances.<实例 ID>` 的字段：

| 键 | 类型 | 来源（`InstanceDisplayMetadata` 组件） | 说明 |
| --- | --- | --- | --- |
| `displayName` | String | `displayName` | 自定义名称，最大 64 字符（`MAX_DISPLAY_NAME_LENGTH`） |
| `favorite` | boolean | `favorite` | 收藏标记（实例列表置顶） |
| `cover` | String | `coverImage` | 封面图片路径；**注意 JSON 键名是 `cover`，record 组件名是 `coverImage`** |

写回规则：`isMeaningful()` 为假的条目（无自定义名称、未收藏、无封面）会被丢弃；所有条目都被丢弃时直接删除该文件，而不是留下空文档。

```json
{
  "schemaVersion": 1,
  "instances": {
    "1.21.8-fabric": {
      "displayName": "生存世界",
      "favorite": true,
      "cover": "C:\\Pictures\\cover.png"
    }
  }
}
```

## `<实例目录>/.ecl/config/launch-profile.json`

位置：相对实例目录的 `.ecl/config/launch-profile.json`（`InstanceLaunchProfileStore.PROFILE_RELATIVE_PATH`）。实现：`InstanceLaunchProfileStore.java`、`InstanceLaunchProfile.java`。

| 键 | 类型 | 来源（record 组件） | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | int | `schemaVersion` | 当前仅接受 `1`（`InstanceLaunchProfile.CURRENT_SCHEMA_VERSION`），其他值在加载时抛错 |
| `javaMode` | String | `javaMode` | `AUTO` 或 `CUSTOM` |
| `javaPath` | String | `javaPath` | `CUSTOM` 时必须非空；`AUTO` 时必须为空 |
| `memoryMode` | String | `memoryMode` | `AUTO` 或 `CUSTOM` |
| `maxMemoryMb` | int | `maxMemoryMb` | `AUTO` 时必须为 `0`；`CUSTOM` 时允许 512–65536（`ECLConfig.MIN_GAME_MEMORY_MB`/`MAX_GAME_MEMORY_MB`） |
| `customJvmArguments` | String[] | `customJvmArguments` | 每项一个参数；保存前经 `JvmArgumentPolicy.requireSafe` 过滤 |

文件缺失时：用旧全局 `javaPath`／`maxMemoryMb`／`jvmArgs` 迁移一次并立即写回（`InstanceLaunchProfileStore.migrate`），旧全局设置保持不变。

```json
{
  "schemaVersion": 1,
  "javaMode": "AUTO",
  "javaPath": "",
  "memoryMode": "CUSTOM",
  "maxMemoryMb": 4096,
  "customJvmArguments": ["-XX:+UseG1GC"]
}
```

## `<实例目录>/.ecl/config/instance-game-settings.json`

位置：`.ecl/config/instance-game-settings.json`（`InstanceGameSettingsStore.SETTINGS_RELATIVE_PATH`）。记录该实例是否覆盖运行目录策略。

| 键 | 类型 | 来源（record 组件） | 说明 |
| --- | --- | --- | --- |
| `overrideRunningDirectory` | boolean | `overridesRunningDirectory` | 是否覆盖全局默认隔离策略 |
| `runningDirectory` | String | `runningDirectory` | 自定义运行目录；为空时使用实例目录本身 |

文件不存在等价于 `InstanceGameSettings.inherited()`（两项分别 `false` / `""`）。

## `<运行目录>/.ecl/game-process.json`

位置：`.ecl/game-process.json`（`GameProcessMarker.MARKER_RELATIVE_PATH`）。用于启动器退出后仍能判断游戏是否在运行。

| 键 | 类型 | 来源（`GameProcessMarker.Marker` 组件） | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | int | `schemaVersion` | 当前为 `1` |
| `pid` | long | `pid` | 游戏进程 PID，必须 `> 0` |
| `startedAtEpochMillis` | long | `startedAtEpochMillis` | 进程启动时间（epoch 毫秒）；用于排除 PID 复用 |

校验规则：文件不超过 16 KiB；缺少任一字段、`schemaVersion` 不符或 `pid <= 0` 视为损坏并抛 `IOException`；进程启动时间无法读取时（`0`）只按 PID 匹配。

## upgrade-recovery/<实例 ID>/active|pending/recovery.json

位置：`<数据目录>/upgrade-recovery/<实例 ID>/`，清单文件名固定为 `recovery.json`（`InstanceUpgradeRecoveryService.MANIFEST_FILE`，`SCHEMA_VERSION = 1`）。实现：`ecl-core/src/main/java/com/ecl/modrinth/service/InstanceUpgradeRecoveryService.java`。

目录结构：

| 路径 | 含义 |
| --- | --- |
| `staging-<uuid>/` | 正在收集快照的临时目录，启动/下次操作时清理 |
| `pending/` | 快照已收集但本次升级结果尚未确定；存在即表示“中断待恢复” |
| `active/` | 已发布的恢复点（每个实例只保留最近一次实际产生文件变化的升级） |
| `previous/` | 发布 `active` 时的换名中转目录，异常时用于回滚换名 |
| `active/payload/` | 旧文件内容快照，文件名形如 `<序号>.bin` |

清单字段：

| 键 | 类型 | 来源（`Manifest` 组件） | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | int | `SCHEMA_VERSION` | 当前为 `1` |
| `profileId` | String | `profileId` | 实例 ID；必须与当前解析结果一致 |
| `instanceId` | String | `instanceId` | 实例稳定 UUID（`VersionProfileModInstanceContext` 生成） |
| `instanceRoot` | String | `instanceRoot` | 实例根目录绝对路径；不一致时拒绝加载 |
| `metadataRoot` | String | `metadataRoot` | 实例元数据（`versions`）根目录绝对路径 |
| `gameRoot` | String | `gameRoot` | 游戏根目录绝对路径 |
| `createdAt` | String | `createdAt` | ISO-8601 时间戳（`Instant.toString()`） |
| `pack` | boolean | `pack` | 本次升级是否为整合包升级（决定回退范围） |
| `state` | String | `state` | `PREPARING` / `ACTIVE` / `RESTORING` / `RESTORED` |
| `conflictsConfirmed` | boolean | `conflictsConfirmed` | 用户是否已确认覆盖升级后被修改的文件 |
| `entries` | object[] | `entries` | 快照条目，见下表 |

`entries[]` 字段（`Entry` 组件）：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `scope` | String | `INSTANCE`（相对实例根）或 `METADATA`（版本 JSON，路径规则为 `<路径>/<路径>.json`） |
| `path` | String | 相对路径，统一使用 `/` 分隔 |
| `beforeExists` | boolean | 升级前文件是否存在 |
| `beforeSha256` | String | 升级前 SHA-256；`beforeExists=false` 时为空 |
| `payload` | String | `payload/` 下的备份文件名（`<n>.bin`）；无备份时为空 |
| `afterExists` | boolean | 升级后文件是否存在（`pending` 阶段为 `false`，`finalizePending` 时补齐） |
| `afterSha256` | String | 升级后 SHA-256，用于回退前冲突检测 |
| `restored` | boolean | 该条目是否已回退（逐条写回以支持中断续做） |

```json
{
  "schemaVersion": 1,
  "profileId": "1.21.8-fabric",
  "instanceId": "0f2d…",
  "instanceRoot": "C:\\Users\\me\\AppData\\Roaming\\.minecraft\\versions\\1.21.8-fabric",
  "metadataRoot": "C:\\Users\\me\\AppData\\Roaming\\.ecl\\versions",
  "gameRoot": "C:\\Users\\me\\AppData\\Roaming\\.minecraft",
  "createdAt": "2026-10-18T05:00:00Z",
  "pack": false,
  "state": "ACTIVE",
  "conflictsConfirmed": false,
  "entries": [
    {
      "scope": "INSTANCE",
      "path": "mods/example-mod-1.2.3.jar",
      "beforeExists": true,
      "beforeSha256": "…64 位十六进制…",
      "payload": "0.bin",
      "afterExists": true,
      "afterSha256": "…64 位十六进制…",
      "restored": false
    }
  ]
}
```

## `<运行目录>/.ecl-pack-manifest.json`

位置：实例运行目录下的 `.ecl-pack-manifest.json`（`PackManifest.FILE_NAME`）。记录某一次 Modrinth 整合包版本“拥有”的文件。实现：`ecl-core/src/main/java/com/ecl/modrinth/pack/PackManifest.java`。

| 键 | 类型 | 来源 | 说明 |
| --- | --- | --- | --- |
| `formatVersion` | int | `FORMAT_VERSION` | 当前为 `1`；其他值直接报“不支持的格式” |
| `packVersion` | String | `packVersion` | 整合包版本号（`modrinth.index.json` 的 `versionId`，缺失时用来源版本 ID） |
| `files` | object[] | `files` | 受管理文件列表，见下 |

`files[]` 字段：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `path` | String | 相对实例目录的路径；禁止以 `/`、盘符、`\` 开头或包含 `..` |
| `sha512` | String | 文件内容的 SHA-512（128 位十六进制）；读取时校验格式 |

生成规则：`PackManifest.capture` 遍历目录时跳过清单自身；除清单外的所有普通文件都会被记录（先按路径排序写入）。

```json
{
  "formatVersion": 1,
  "packVersion": "1.4.0",
  "files": [
    { "path": "mods/example-1.0.0.jar", "sha512": "…128 位十六进制…" },
    { "path": "config/example.toml", "sha512": "…128 位十六进制…" }
  ]
}
```

## `<运行目录>/.ecl-mod-transactions/<事务 ID>/journal.json`

位置：运行目录下 `.ecl-mod-transactions/<UUID>/journal.json`（`FileModInstallationTransaction.TRANSACTIONS_DIRECTORY` / `JOURNAL_FILE`）。Mod 文件安装、启用、禁用、卸载的事务日志。Jackson 序列化 `Journal` record；恢复前验证状态、条目结构及路径，验证失败保留整个事务与备份，不能把缺失条目当成空回滚后清理。

| 键 | 类型 | 来源（记录组件） | 说明 |
| --- | --- | --- | --- |
| `status` | String | `Journal.status` | 必须为 `PREPARED` / `APPLYING` / `APPLIED`；`APPLIED` 表示已提交，日志验证通过后才清理目录 |
| `entries` | object[] | `Journal.entries` | 必须是非空数组；零条目的事务不生成日志 |

`entries[]`（`JournalEntry`）：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `stagedFile` | String | 暂存文件相对运行目录的路径（位于事务目录内） |
| `finalFile` | String | 目标文件相对运行目录的路径 |
| `oldFile` | String/null | 需要一并移走的旧文件路径（无则为 `null`） |
| `finalBackup` | String | 目标文件被覆盖前的备份路径（`backups/<n>-final.bak`） |
| `oldBackup` | String | `oldFile` 的备份路径（`backups/<n>-old.bak`） |
| `finalFileExisted` | Boolean | 必需布尔值，表示事务开始前目标文件是否存在；回滚时据此决定是否删除新建文件 |

同目录还有 `backups/` 备份目录和 `<事务 ID>.lock` 锁文件；没有可读日志的事务目录会被保留而不是删除，以便人工检查。

## `<实例目录的父目录>/.ecl-pack-transactions/<实例 ID>-<事务 UUID>/journal.json`

位置：实例目录**父目录**下的 `.ecl-pack-transactions/<实例 ID>-<事务 UUID>/journal.json`（`PackUpdateTransaction.TRANSACTIONS_DIRECTORY`）。整合包安装/更新的事务日志，作用域比 Mod 事务更大：可同时改动实例目录、版本 JSON 和共享 `libraries`。

| 键 | 类型 | 来源（记录组件） | 说明 |
| --- | --- | --- | --- |
| `status` | String | `Journal.status` | `PREPARED` / `APPLYING` / `APPLIED` |
| `entries` | object[] | `Journal.entries` | 事务条目，见下 |

`entries[]`（`JournalEntry`）：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `operation` | String | `REPLACE` 或 `DELETE` |
| `targetScope` | String | `INSTANCE` / `PROFILE` / `VERSIONS` / `LIBRARIES`（`PackTransactionPaths.Scope`） |
| `targetPath` | String | 相对该作用域根的路径；`PROFILE` 作用域必须为空串 |
| `stagedFile` | String/null | 事务目录内的暂存文件相对路径（`staged/…`），`DELETE` 时为 `null` |
| `backupFile` | String | 事务目录内的备份相对路径（`backups/<n>.bak`） |
| `targetExisted` | Boolean | 应用前目标是否存在 |

配套路径：事务目录内的 `staged/`、`backups/`、`<事务目录>.lock`；实例目录旁的 `<实例 ID>.pack.lock` 作为跨进程操作锁。恢复规则：`APPLIED` 直接清理；其余按条目逆序回滚；没有 journal 的事务目录保留待人工检查。

## `<运行目录>/.ecl/world-restore/journal.json`

来源：`backup/WorldBackupRestorer.writeJournal` / `readJournal`；操作期间由实例生命周期锁保护。

| JSON 键 | 类型 | 来源与含义 |
| --- | --- | --- |
| `schemaVersion` | Integer | `writeJournal` 固定为 `1`，读取时必须匹配 |
| `instanceRoot` | String | 实际运行目录的绝对规范化路径，读取时必须匹配当前实例 |
| `phase` | String | `APPLYING` 表示需回滚，`COMMITTED` 表示已成功还原，仅需清理 |
| `entries[].name` | String | `RestoreState.name`；必须属于 `BackupEntry.Content.directoryName()`，不得重复，必须包含 `saves` |
| `entries[].originalExists` | Boolean | `RestoreState.originalExists`；记录替换前目录是否存在 |

同目录的 `content/` 存放校验通过的备份内容，`rollback/` 保存替换前的原目录。日志在第一次移动目录前原子写入并强制落盘；日志最后删除。中断后根据原目录、暂存目录是否存在逆序回滚，支持恢复过程再次中断。日志损坏、身份不匹配或丢失但仍有回滚数据时保留现场并报错，不能自动删除原始文件。

升级恢复点的 `<数据目录>/upgrade-recovery/<profile ID>/operation.lock` 是稳定的跨进程锁文件，不存业务字段；`Draft.lease` 仅为当前进程持有的 OS 锁，不写入 `recovery.json`。

`ecl-core` JAR 内的 `ecl-build.properties` 含 `launcher.version`，由 Gradle 根据根目录 `gradle.properties` 的 `version` 生成，不是用户持久化设置。

## servers/<服务端 ID>/profile.json

位置：`<数据目录>/servers/<UUID>/profile.json`（`LocalServerManager`）。同一目录还包含：`server.jar`（导入副本）、`eula.txt`（`eula=true|false`）、`console.log`（追加写入的控制台输出）、`process.json`（运行标记）、`manager.lock`（目录操作锁）。

| 键 | 类型 | 来源（`LocalServerProfile` 组件） | 说明 |
| --- | --- | --- | --- |
| `id` | String | `id` | 服务端 UUID（小写、带连字符的 36 字符） |
| `name` | String | `name` | 显示名，1–100 个可打印字符 |
| `javaPath` | String | `javaPath` | Java 可执行文件绝对路径 |
| `memoryMb` | int | `memoryMb` | 最大内存 MiB，允许 256–65536 |
| `eulaAccepted` | boolean | `eulaAccepted` | 用户是否明确接受 EULA |

`process.json` 是私有 record（`RunningProcess`），字段为 `pid` 与 `startedAtEpochMillis`；只有 PID 与进程启动时间同时匹配才认为该服务端仍在运行，用于识别启动器重启后的“脱离控制”状态。

```json
{
  "id": "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
  "name": "Minecraft 1.21.1 · ECL QA",
  "javaPath": "C:\\Program Files\\Eclipse Adoptium\\jdk-21\\bin\\java.exe",
  "memoryMb": 2048,
  "eulaAccepted": true
}
```

## 其他持久化文件（不展开字段）

以下文件同样写在数据目录或实例目录中，字段定义见对应源码，本文不逐一列出，避免与实现脱节：

| 路径 | 源码 | 用途 |
| --- | --- | --- |
| `<数据目录>/accounts.json` | `auth/DefaultAccountService`（`AuthAccount`、编码见 `encode`/`decode`） | 统一账户列表；`accessToken`/`refreshToken` 字段保存 AES-GCM 密文 |
| `<数据目录>/microsoft-accounts.json.migrated` | `auth/MicrosoftAccountStore.archiveMigratedStore` | 旧版 Microsoft 账号文件迁移后的存档 |
| `<数据目录>/skins.json` 与 `skins/<sha1>.png` | `auth/OfflineSkinStore` | 离线皮肤索引与图片 |
| `<数据目录>/cache/project-icons/` | `ecl-gui` `modrinth/ui/RemoteImageLoader` | Modrinth 图标磁盘缓存 |
| `<数据目录>/cache/public-server-directory.json` | `ecl-gui` `server/ServerDirectoryService` | 公开服务器目录缓存（含 `fetchedAtEpochMillis`、`locale`、`data`） |
| `<数据目录>/runtimes/temurin-<特性版本>-windows-<架构>/` | `util/JavaRuntimeDownloader` | 下载的 Temurin JRE；完整安装标记 `.ecl-runtime-complete` |
| `<数据目录>/authlib-injector.jar` 与 `.sha256` | `auth/offline/AuthlibInjectorManager` | 离线皮肤注入代理及其校验和 |
| `<数据目录>/.ecl-download-locks/`、`<版本目录>/.ecl-version-locks/` | `util/ManagedLockPaths` | 跨进程文件发布锁与版本下载锁 |
| `<运行目录的父目录>/.ecl-instance-locks/<sha256>.lifecycle.lock` | `util/ManagedLockPaths.instanceOperation` | 实例生命周期锁（不在数据目录里） |
| `<运行目录>/launcher-mods.json` | `modrinth/repository/FileInstalledModRepository`（`IndexDto`/`InstalledModDto`） | 已安装模组索引 |
| `<运行目录>/launcher-mod-scan.json` | `modrinth/service/LocalModScanCache`（`ScanCache`/`Entry`） | 本地模组扫描缓存 |
| `<数据目录>/backups/<实例 ID>/backup-*.zip` 与同名 `.json` | `backup/WorldBackupService`、`WorldBackupMetadata` | 世界备份压缩包与元数据（`schemaVersion`、`profileId`、`sourceVersion`、`createdAt`、`includedContent`、`uncompressedSize`、`files`） |

## 账户文件读取规则

`accounts.json` 的根节点必须为数组（`DefaultAccountService.read`）；对象、标量、`null` 或空文件均报错并保留原内容。
`DefaultAccountService.mutate` 使用同目录 `accounts.json.lock` 的 `FileLockLease` 覆盖重读、账户修改与原子发布，首次离线账户的默认值判断也在锁内。锁忙则拒绝修改；锁文件释放后保留，不含账户或凭据。JSON 字段没有变化。
GUI 读取降级不会改写该文件；新增账户与迁移仍拒绝无效库。离线皮肤索引实际位于 `<数据目录>/skins.json`（`OfflineSkinStore.indexFile`），读坏库时不写入；下次导入前将原始字节复制到唯一的 `skins.json.corrupt-*.json`，备份失败停止导入，既有备份不覆盖。

恢复点清单字段保持 `SCHEMA_VERSION = 1`。`Draft.session` / `Draft.ownership` 和 `UpdatePreview.preparedSha512` 都是进程内状态，不是 JSON 键；后者以 `files/`、`versions/`、`libraries/` 相对路径及归档为键记录准备时的 SHA-512。payload 与 `recovery.json` 写入后强制刷新，新 active 身份/摘要验证成功才移除 previous。pending 校验失败保留两个恢复点，不退回旧点继续写入。
文件不存在时可创建新账户列表；数组内无法解码的条目仍按现有规则保留。

## 游戏下载断点

`GameDownloadBatchExecutor.downloadSingleVerified` 使用 `<目标根目录>/.ecl-downloads/<sha256>.lock.download` 作为稳定暂存路径；
`sha256` 来自 `ManagedLockPaths.filePublication` 对规范化目标路径的摘要（Windows 下先转小写）。
`.ecl-downloads` 是保留目录，不接受正式下载目标。续传器保存暂存文件对应的 `.part` 和 `.part.meta`；
网络中断后保留，完成并校验后发布目标；成功后可能留下空的 `.ecl-downloads/` 目录。
无可用校验标记或来源改变时从头下载；已完成但大小或 SHA-1 校验失败的暂存文件被删除。
升级前版本遗留的 `<正式目标>.part` / `.part.meta` 没有稳定的来源关联，不迁移、不复用，也不自动删除；新重试仅使用 `.ecl-downloads/` 下的受校验断点。客户端日志配置使用随机临时文件，失败、取消或完成后清理其 `.part` / `.part.meta`，不会将它们作为下一次续传来源。

| `.part.meta` JSON 键 | 来源（`ResumableFileDownloader.PartialDownloadMetadata` record 组件） | 含义 |
| --- | --- | --- |
| `source` | `source` | 实际下载候选 URL，仅向相同来源请求续传 |
| `etag` | `etag` | 响应 ETag，优先用作 If-Range 校验标记 |
| `lastModified` | `lastModified` | 没有 ETag 时使用的 Last-Modified 校验标记 |

## 旧版资源与客户端日志配置

资源布局来源是 `<数据目录>/assets/indexes/<id>.json`，由 `LegacyAssetMaterializer.readIndex` 读取并校验。
版本 JSON 中的 `assetIndex` 只描述索引位置，`AssetIndex` record 组件为 `id`、`url`、`sha1`、`totalSize`。

| 索引 JSON 键 | 源码 | 含义及生成路径 |
| --- | --- | --- |
| `virtual` | `LegacyAssetMaterializer.isVirtual` | 命名资源生成于 `<数据目录>/assets/virtual/<id>/<资源名>` |
| `map_to_resources` | `LegacyAssetMaterializer.mapsToResources` | 命名资源生成于 `<实际运行目录>/resources/<资源名>` |
| `objects.<资源名>.hash` | `LegacyAssetMaterializer.requireHash` | SHA-1；对象位于 `assets/objects/<前两位>/<hash>` |
| `objects.<资源名>.size` | `GameDownloader.downloadAssets` | 下载对象的预期字节数 |

虚拟资源与 `resources/` 是对象缓存的硬链接或副本；同大小文件仍比较内容，替换时先准备临时文件。
启动准备会补齐旧安装的视图，自定义运行目录不会写入默认 `.minecraft/resources/`。

版本 JSON 的 `logging.client` 由 `ClientLoggingConfig.parse` 读取：

| 元数据 JSON 键 | record 组件或解析位置 | 含义 |
| --- | --- | --- |
| `logging.client.argument` | `ClientLoggingConfig.argument` | 含 `${path}` 的安全 JVM 参数 |
| `logging.client.file.id` | `ClientLoggingConfig.parse` → `DownloadObject.fileName` | 单段文件名；生成 `ClientLoggingConfig.relativePath = log_configs/<id>` |
| `logging.client.file.url` | `ClientLoggingConfig.file` → `DownloadObject.url` | 配置下载地址 |
| `logging.client.file.sha1` | `ClientLoggingConfig.file` → `DownloadObject.sha1` | 配置 SHA-1 |
| `logging.client.file.size` | `ClientLoggingConfig.file` → `DownloadObject.size` | 正数；下载上限和预期字节数 |

配置保存在 `<数据目录>/assets/log_configs/<id>`，内容由版本元数据指定，并非启动器设置 JSON。
安装、修复和启动准备均可补齐；大小与 SHA-1 正确时离线复用，损坏文件仅在新内容校验成功后替换。

## 日志

`ecl-core/src/main/resources/logback.xml` 通过 `LogDirectoryProperty.getPropertyValue()` 获取 `ECLConfig.getBaseDir()` 下的 `logs/`，写入 `ecl.log`，并按 `ecl.<日期>.<序号>.log.gz` 滚动（单文件 5 MB、保留 7 天）。通常为 `%APPDATA%\.ecl\logs`；`APPDATA` 缺失或为空时与数据目录一起回退到 `${user.home}/.ecl/logs`。旧版用户主目录下的历史日志不自动迁移或删除。

模组与整合包事务的 journal.json 缺失且 backups/ 仍有内容时会保留现场并报错，未知事务内容也不会在显式恢复中删除。不要直接清理这些目录；它们可能包含唯一的升级前文件。
