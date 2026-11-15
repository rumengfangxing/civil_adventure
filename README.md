# civil_adventure 2.0 (文礼冒险区拓展 / 多派系地缘对抗生态引擎)

> **目标平台**: Minecraft 1.20.1 | Forge 47.1.0+ | Java 17  
> **核心前置**: Civillis (文礼) 2.0.1+  
> **软联动**: Xaero's Minimap & World Map, KubeJS, Spore (真菌感染), Biomancy (血肉重铸)

---

## 1. 项目背景与设计哲学

在原始文礼模组（Civillis）中，“冒险区（Zone）”是一个静态、单一、单向对抗的概念：文礼仅内置了一套固定的原版结构冒险区逻辑，且所有冒险区均呈现单一的紫色边界墙与固定红色地图色块，无法支持多个不同异族派系之间的割据与领地争夺。

`civil_adventure` 2.0 对文礼的冒险区体系进行了**彻底重构与全面覆写**，将其升格为一个**开放、多派系、动态宏观领地割据与地缘政治对抗引擎**。核心设计哲学包括：
1. **多派系平等共存与竞争**：文明、灾厄、真菌感染乃至未来任意数据包派系，均拥有独立的危险方块、结构归属、专属生物白名单、专属颜色与优势增益。
2. **宏观连通域聚类（Macro Cluster & Winner-Takes-All）**：相连的危险地块通过 8-连通域泛洪算法融合成一个整体宏观领地，总分最高者通吃该连通域；不相连的领地各自独立裁决，在小地图上呈现异色割据态势。
3. **严格的威胁边界隔离（反全图导线效应）**：零危险分的荒野、海洋与安全区绝对不参与聚类连通，杜绝远方感染区通过荒野底噪把几千个区块连成一片。
4. **动态移除与防抖租约（Debounce Hysteresis）**：生物在领地边界游走时拥有 80 tick（4 秒）防抖缓冲期，租约到期后安全剥离暂态属性加成与标签，杜绝脏数据残留。
5. **双轨增益模型（派系统领 + 单生物精细化重载）**：支持派系全局兜底 Buff，同时完美兼容旧版针对特定实体的几十种精细化阶梯数值与药水配置。
6. **结构基础分分摊与永久肃清**：派系结构注入 500 基础分并按 Bounding Box 区块数平摊，被压制清零后永久肃清且不影响新方块重新滋生。

---

## 2. 核心架构与代码模块导览

```
src/main/java/civil_adventure/
├── CivilAdventureMod.java         // 模组主入口、生命周期事件驱动、跨地缘边界 HUD 调度
├── api/
│   └── AdventureAPI.java          // 对外公开 API，供 KubeJS 与第三方模组安全调用
├── block/
│   ├── AdventureSuppressorBlock.java       // 冒险区压制核心方块
│   ├── AdventureSuppressorBlockEntity.java // 压制方块实体（全额并发压制脉冲、燃料消耗计算）
│   └── SuppressorBlockItem.java            // 方块物品
├── cluster/
│   ├── MacroCluster.java          // 宏观连通域领地实体（质心、总分仲裁、成王败寇胜负判定、开放CompoundTag槽）
│   └── MacroClusterManager.java   // 8-方向泛洪填充（Flood-Fill BFS）聚类管理器与统治权更替事件发布
├── config/
│   └── AdventureConfig.java       // Forge 配置规范（压制半径、冷却阈值、忽略派系名单等）
├── core/
│   └── ChunkCoordPacker.java      // 高性能 long <-> (int cx, int cz) 坐标无对象开销打包解包器
├── data/
│   ├── AdventureEntityLoader.java // 实体精细化数值数据包加载器 (data/<ns>/adventure_entities/*.json)
│   ├── AdventureFuelLoader.java   // 压制燃料配置加载器
│   └── EntityConfig.java          // 实体属性与药水定义 Record
├── entity/
│   └── EntityFactionTagManager.java // 生物【是 / 非】Tag 租约管理器（80-tick 防抖、暂态属性加成剥离）
├── faction/
│   ├── FactionDefinition.java     // 派系完整规格定义（索引、方块权重、结构列表、白名单、代表色、增益）
│   ├── FactionRegistry.java       // 派系注册表（出厂内置文明与灾厄，动态加载数据包派系）
│   └── event/
│       ├── EntityFactionTagEvent.java      // 生物打上/剥离地缘标签事件
│       └── FactionDominanceChangeEvent.java// 宏观领地统治权更替事件
├── mixin/
│   ├── AdventureBlockChangeMixin.java      // 监听方块放置破坏并触发防抖重算
│   ├── CivilMapTintPaletteMixin.java       // 覆写文礼服务端地图色阶评估为派系冒险区
│   ├── CivilMinimapMixin.java              // 拦截 Xaero 小地图雷达，赋予派系独立代表色 (RGB)
│   ├── CivilRegionClassifierMixin.java     // 接管文礼领地区域分类
│   ├── CivilWorldMapMixin.java             // 拦截 Xaero 大地图，显示具体派系名称 (Tooltip) 与独立色块
│   ├── CivilizationScoreMixin.java         // 压制方块提供文明分支持
│   ├── XaerosColorMixin.java               // Xaero 基础色阶兼容回退
│   └── ZonePolicyMixin.java                // 覆写文礼 ZonePolicy：肃清判定与非文明敌对区允许刷怪
├── network/
│   └── AdventureTransitionPayload.java    // 客户端/服务端地缘切换通信包
├── client/
│   └── ClientPayloadHandler.java           // 客户端接收地缘切换通知，调用文礼 ZoneTransitionHud (I18n 解析)
├── override/
│   ├── CivillisStructureTracker.java       // 派系结构追踪、基础分分摊与肃清判定
│   └── CleansedStructureStorage.java       // 基于 SavedData 的永久肃清持久化存储
└── score/
    ├── AdventureBlockLoader.java           // 旧版兼容方块加载器
    ├── AdventureScanner.java               // 区块多派系高性能扫描（LevelChunkSection Palette 调色板快筛）
    ├── AdventureScoreService.java          // 核心分值服务、并发压制调度、聚类触发与脏标记防抖
    └── AdventureZoneScanner.java           // 实体扫描兼容层
```

---

## 3. 关键算法与工程实现亮点

### 3.1 调色板纳秒级快筛（Palette Section Fast-Screening）
在 [`AdventureScanner.java`](file:///e:/McPack/civil_adventure/src/main/java/civil_adventure/score/AdventureScanner.java) 中扫描区块方块时：
- 不会盲目遍历 16×16×384（近 10 万个）方块；
- 预先利用 `FactionRegistry::isAnyFactionBlock` 编译全部派系方块集合；
- 调用 Minecraft 内部 `LevelChunkSection.maybeHas(...)` 检查该 16×16×16 Section 调色板，若不含任何派系方块则直接跳过，耗时从毫秒级降至纳秒级。

### 3.2 反全图导线效应机制（Danger Isolation）
在 [`AdventureScoreService.java`](file:///e:/McPack/civil_adventure/src/main/java/civil_adventure/score/AdventureScoreService.java) 的 `rebuildClusters` 中：
- 文礼原生文明分在玩家经过的荒野中常残留微量底噪（0.01~1.0）；
- 若直接将“任意非零分区块”加入泛洪网络，几千个荒野/铁轨/海洋区块会被连为一体，导致远处遗迹的数千分通吃全图；
- 解决方案：**宏聚类连通图严格仅纳入实质危险分 $\ge 0.5$ 的威胁区块**。荒野作为天然绝缘体，将不同派系的冒险区完美阻隔在各自的实际生成范围内。

### 3.3 空间 8-连通域泛洪填充与成王败寇（Winner-Takes-All BFS）
在 [`MacroClusterManager.java`](file:///e:/McPack/civil_adventure/src/main/java/civil_adventure/cluster/MacroClusterManager.java) 中：
- 利用 BFS 算法沿 8 个方向（含对角线）扩散搜索相连的活跃危险区块；
- 每个宏观连通域聚类内统计各派系的总威胁分；
- 若最高分为非文明派系且高于阈值，则整个宏观连通域统一划归该派系统治；若两派系领地不相连，则各自独立裁决、小地图异色显示。

### 3.4 暂态属性修饰符与防抖租约（Debounce Hysteresis）
在 [`EntityFactionTagManager.java`](file:///e:/McPack/civil_adventure/src/main/java/civil_adventure/entity/EntityFactionTagManager.java) 中：
- 生物赋予的属性加成全部采用 `AttributeInstance.addTransientModifier`，绝不落盘保存，重启或离开领地后零残留；
- 修饰符 UUID 基于 `(entityUUID + ":" + attrId)` 确定性哈希生成，防止重复叠加；
- 生物离开领地拥有 80 tick（4秒）滞后防抖缓冲，杜绝生物在边界徘徊时高频增删 Tag 导致卡顿或事件风暴。

### 3.5 结构基础分分摊与持久化肃清
在 [`CivillisStructureTracker.java`](file:///e:/McPack/civil_adventure/src/main/java/civil_adventure/override/CivillisStructureTracker.java) 中：
- 结构的基础分（默认 500）按其 Bounding Box 覆盖的区块数平均分摊，避免大型结构造成总分超量膨胀；
- 当压制核心将其剩余分压制至 0 时，触发永久肃清存入 `CleansedStructureStorage`，后续即使重进存档该结构也不再产生冒险区基础分，但未来若真菌在此蔓延生长，仍可正常诞生新冒险区。

---

## 4. 数据包规格与配置示例

### 4.1 派系定义 JSON (`data/<namespace>/adventure_factions/<faction_id>.json`)
```json
{
  "name": "civil_adventure.faction.fungal.name",
  "color": "#7B1FA2",
  "min_dominance_score": 10.0,
  "structures": [
    "spore:biomass_tower",
    "spore:lab",
    "spore:mines"
  ],
  "structure_base_score": 500.0,
  "entity_whitelist": {
    "tags": ["#civil_adventure:fungal_entities"],
    "entities": ["spore:inf_human", "spore:proto", "spore:verfall"]
  },
  "is_tag": "civil_adventure:is_fungal",
  "not_tag": "civil_adventure:not_fungal",
  "blocks": {
    "spore:biomass": 2.0,
    "spore:mycelium": 1.0
  },
  "dominant_buffs": {
    "attributes": [
      { "attribute": "minecraft:generic.attack_damage", "modifier": 0.25, "operation": 2 }
    ],
    "effects": [
      { "effect": "minecraft:regeneration", "duration": 200, "amplifier": 1 }
    ]
  }
}
```

> [!NOTE]
> `FactionRegistry` 已对上述字段进行全向宽容度兼容解析：
> - `blocks`: 同时支持字典键值映射 `{"block_id": weight}` 与对象数组 `[{"block": "id", "weight": w}]`；
> - `tags`: 同时支持根节点平铺（`is_tag`/`not_tag`）与 `"tags": {"is_tag": "...", "not_tag": "..."}` 嵌套结构；
> - `attributes`: 同时兼容 `"modifier"` 与 `"amount"` 键名。

### 4.2 独立实体数值 JSON (`data/<namespace>/adventure_entities/<entity_id>.json`)
```json
{
  "_comment": "灾厄级真菌-虚空吞噬者",
  "attributes": [
    { "attribute": "minecraft:generic.attack_damage", "modifier": 0.3, "operation": 2 },
    { "attribute": "minecraft:generic.max_health", "modifier": 0.3, "operation": 2 },
    { "attribute": "goety_revelation:resistance", "modifier": 0.3, "operation": 0 }
  ],
  "effects": [
    { "effect": "fib:frost_resistance", "duration": 600, "amplifier": 3 },
    { "effect": "minecraft:regeneration", "duration": 300, "amplifier": 3 }
  ]
}
```

---

## 5. 建议的代码审查重点 (Review Checklist for Gemini 4)

1. **并发与线程安全 (Concurrency & Thread Safety)**：
   - 检查 `AdventureScoreService`、`MacroClusterManager`、`EntityFactionTagManager` 中各类 `ConcurrentHashMap` 与同步块在多玩家高频加载卸载区块下的安全性，是否存在死锁或竞态条件。
2. **生命周期与内存泄漏 (Lifecycle & Memory Leaks)**：
   - 检查区块卸载（`onChunkUnload`）、玩家退出（`onPlayerLogout`）、实体死亡/移除（`onEntityRemoved`）时，缓存和租约是否被彻底清理，是否有废弃引用滞留。
3. **算法边界条件 (Algorithmic Edge Cases)**：
   - 验证 `MacroClusterManager` 8-连通 BFS 在地图像限跨越（负坐标 cx/cz）、孤立单区块领地、以及多派系分值完全平局时的仲裁确定性。
4. **性能损耗与开销 (Performance & Hotpaths)**：
   - 审查 `AdventureScanner` 调色板快筛、`EntityFactionTagManager.tick` 遍历存活实体逻辑是否有优化空间。
5. **Mixin 侵入性与健壮性 (Mixin Robustness)**：
   - 审查与 Civillis、Xaero 交互的各个 Mixin 注入点，是否可能因目标前置版本变动而发生注入失败或未捕获异常。

---

## 6. 二审专属说明（一审缺陷闭环与回归验证指引）

针对 Gemini 4 第一轮审查报告中指出的全部 14 项缺陷（C-01 ~ K-02）以及提出的 3 个二轮回归边界点，修复细节与代码索引对齐如下：

### 6.1 一审全部 14 项缺陷修复对照表
| 编号 | 修复动作与实现手段 | 涉及文件与关键代码 |
| :--- | :--- | :--- |
| **C-01** | 方块变化 mixin 接入 2.0 `FactionRegistry.isAnyFactionBlock`，支持新派系方块动态重算 | `AdventureBlockChangeMixin.java:51-60` |
| **C-02** | 剥离地图 mixin 对服务端的直读，新增 `AdventureClusterSyncPayload` 网络包与 `ClientClusterCache` 客户端缓存，`MacroClusterManager` 使用 volatile 原子替换 | `ClientClusterCache.java`, `AdventureClusterSyncPayload.java`, `MacroClusterManager.java:24,80` |
| **C-03** | 移除主类顶层 `ClientPayloadHandler` 静态导入，消息回调改用 `DistExecutor.unsafeRunWhenOn`；客户端 Mixin 移入 `"client"` | `CivilAdventureMod.java:111-135`, `civil_adventure.mixins.json:18-23` |
| **C-04** | 压制脉冲按 `rootKey` 对多区块结构去重；结构基础分从 `AdventureScanner` 静态扫描剥离，改为在 `rebuildClusters` 动态叠加，彻底消除已肃清结构的“僵尸残留” | `AdventureScoreService.java:175-270`, `AdventureScanner.java:40-70` |
| **M-01** | 移除 `onServerTick` 中残留的 1.x `zoneScanner.tick`，统一由 2.0 `EntityFactionTagManager` 管理；白名单通用开放精细化配置；API 对接新管理器 | `CivilAdventureMod.java:202-210`, `EntityFactionTagManager.java:170-250`, `AdventureAPI.java:79-84` |
| **M-02** | 将全局标量 `cooldownEnd` 改为按维度独立的 `ConcurrentHashMap<ResourceLocation, Long> cooldownEnds`，杜绝非主世界饿死 | `AdventureScoreService.java:45-120` |
| **M-03** | 领地常驻白名单生物每 60 tick 自动续期药水效果；施加 `generic.max_health` 时等比例抬升当前血量，剥离时安全钳制 | `EntityFactionTagManager.java:125-215` |
| **M-04** | 删除 `rootKey == 0L` 误杀世界原点 `(0, 0)` 的判定；在 `CleansedStructureStorage`（`SavedData`）中新增 `DamagedScores` NBT 列表，持久化未彻底清零的结构受损分 | `CivillisStructureTracker.java:115-160`, `CleansedStructureStorage.java:20-80` |
| **M-05** | 修复 `timerTicks <= 0` 早退导致 `ENABLED=true` 卡死问题；红石状态存盘；区块卸载保留 `chunkSuppression`；重载监听器对接 `onReload` 刷新旧区块缓存 | `AdventureSuppressorBlockEntity.java:68-90,265-285`, `AdventureScoreService.java:365-385` |
| **W-01** | `EntityFactionTagManager.tick` 增加 10 tick 步进节流，`cleanupExpiredLeases` 增加 `lastActiveTick == currentTick` 早退；Section 内层循环增加 `state.isAir()` 跳过 | `EntityFactionTagManager.java:65,147`, `AdventureScanner.java:97` |
| **W-02** | 领地彻底瓦解时正常广播 `FactionDominanceChangeEvent`（`newDom == -1`）；派系数据包读取按键名字典序排序保证确定性 | `MacroClusterManager.java:36,87`, `FactionRegistry.java:114-118` |
| **W-03** | `FactionRegistry` 全向兼容 `blocks`（数组与字典）、`tags`（根节点与子对象）、`attributes`（`modifier` 与 `amount`） | `FactionRegistry.java:150-215` |
| **W-04** | 运行期高频 `LOGGER.info` 全面降级为 `debug`，静默 `ClientDiagnostics` 全模型扫描 | 多处文件 |
| **K-01** | `civil_adventure_compat.js` 补齐 `player.isFake()` 假人防御、20 tick 节流与 IIFE 作用域 | `kubejs_scripts/civil_adventure_compat.js` |
| **K-02** | `infect_detector_info.js` 类加载提升至顶层、补齐假人防御、动态读取派系翻译名并展示领地区块大小 | `kubejs_scripts/infect_detector_info.js` |

---

### 6.2 二轮审查重点验证的 3 个边界点落实说明

1. **客户端缓存生命周期清理**：
   * 在 `AdventureScoreService.rebuildClusters` 中，领地被彻底压制瓦解时，下发的 `AdventureClusterSyncPayload` 携带的是当前维度的最新映射；
   * 客户端 `ClientClusterCache.applySync` 直接以原子方式整张替换该维度的缓存，被瓦解的区块直接消失，小地图下一渲染帧即判定为荒野；
   * 并在 `ClientClusterCache.java` 注册了 Forge 客户端事件 `@SubscribeEvent public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)`，玩家登出/断开服务器时全量清除缓存，跨服/跨存档 100% 杜绝幽灵色块。
2. **动态结构分与零方块区块的激活条件**：
   * 在 `AdventureScoreService.java` 中，`onChunkLoaded` 与 `recalculateFullChunk` 均评估：
     ```java
     boolean hasDanger = AdventureScanner.hasDangerScore(rawScores);
     boolean hasCivil  = rawScores.length > 0 && rawScores[0] >= 0.5f;
     boolean hasStruct = !CivillisStructureTracker.getActiveStructures(level, cx, cz).isEmpty();

     if (hasDanger || hasCivil || hasStruct) {
         dimMap.put(key, rawScores); // 零方块纯结构区块成功注入！
         if (hasDanger || hasStruct) markDirty(dim, cx, cz);
     }
     ```
   * 随后在 `rebuildClusters` 阶段，该区块动态叠加未肃清结构分使得 `eff[1] >= 0.5f` 成立，被顺利纳入 `effectiveMap`，完全不会被纯方块分 0 误杀。
3. **10-tick 实体扫描节流与租约时间戳对齐**：
   * `EntityFactionTagManager.tick(ServerLevel level)` 顶层统一校验 `if (level.getGameTime() % 10 != 0) return;`；
   * 实体遍历扫描与 `cleanupExpiredLeases(level, currentTick)` 共享完全相同的单次执行上下文与 `currentTick`；
   * 领地内生物在 Step 1 必被 `touch(currentTick)`，进入 Step 2 时 `if (lease.lastActiveTick == currentTick) continue;` 立即早退，逻辑严格闭环。

---

## 7. v2 二审深度复审缺陷闭环与配色规范落实说明

针对 Gemini 4 二轮审查报告中指出的全部新发现缺陷（R2-M01 ~ R2-I01）以及领地地图配色统合需求，实现与代码对齐如下：

### 7.1 v2 新发现缺陷修复对照表
| 编号 | 级别 | 修复动作与实现手段 | 涉及文件与关键代码 |
| :--- | :--- | :--- | :--- |
| **R2-M01** | 🟠 严重 | `cleanupExpiredLeases` 增加 `lease.entity.level() != level` 跳过防线，严禁在遍历当前世界时清理其他维度的租约，彻底保全 80-tick 防抖与跨维度稳定性 | `EntityFactionTagManager.java:150-163` |
| **R2-M02** | 🟠 严重 | 新增 `AdventureScoreService.hasRemainingThreatInRadius` 综合评估纯结构分与方块分；`AdventureSuppressorBlockEntity` 增加 `fullySuppressed` 状态并网络同步，纯结构区肃清后正确进入 `RUNNING` 动画并输出比较器 15 信号 | `AdventureScoreService.java:335-375`, `AdventureSuppressorBlockEntity.java:42-185, 275-310` |
| **R2-W01** | 🟡 警告 | 领地彻底瓦解时杜绝硬编码 `(0, 0)` 与 `(byte) 1`，按原聚类真实质心与旧派系广播事件；多聚类局部铲除时对比 `newClusterByChunk` 补发消失聚类的瓦解事件 | `MacroClusterManager.java:33-43, 99-118` |
| **R2-W02** | 🟡 警告 | 玩家跨维度时清理 `prevPlayerFaction` 缓存并立即重算同步；`rebuildClusters` 遍历中自动移除结构已肃清且威胁分为 0 的空壳区块 Key | `CivilAdventureMod.java:297-303`, `AdventureScoreService.java:204-209` |
| **R2-I01** | 🟢 建议 | 将 1.x 遗留的 `zoneScanner` 标记为 `@Deprecated`，主文档全量升级为 2.0 完整规范并同步至根目录 `README.md` | `CivilAdventureMod.java:319`, `README.md` |

### 7.2 派系地图与 HUD 配色统合落实
* **默认冒险区 / 灾厄派系 (`civil_adventure:adventure`)**：
  * 对齐文礼前置默认冒险区色阶（`CivilMapTintPalette.ZONE` / `CivilConfig.chunkBandColorZone`）：**`0xF08252` (`#F08252`，温润珊瑚橙)**；
  * `AdventureConfig.ADVENTURE_FILL_COLOR` 默认值同步对齐为 `"F08252"`；
  * `FactionRegistry.DEFAULT_ADVENTURE` 配色明确为 `0xF08252`。
* **真菌感染派系 (`civil_adventure:fungal`)**：
  * 配色明确为**猩红 (`#D32F2F`)**，与灾厄派系形成鲜明视觉对比；
  * 数据包定义 `adventure_factions/fungal.json` 中的 `color` 统一更新为 `"#D32F2F"`。
* **文明检测器与终端交互 (KubeJS)**：
  * `infect_detector_info.js` 适配三色派系高亮：真菌感染派系显示为 `§c[真菌生态领地]`（鲜红），灾厄冒险区显示为 `§6[灾厄冒险区]`（金橙），人类文明显示为 `§9[人类文明秩序]`（湛蓝）。


