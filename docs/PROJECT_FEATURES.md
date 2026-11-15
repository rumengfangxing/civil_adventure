# Civil Adventure (文礼冒险区拓展) — 模组功能与技术架构全景梳理

> **文档版本**: 1.0.0  
> **适用模组版本**: 1.3.0a  
> **Minecraft 版本**: 1.20.1 | **Forge 推荐**: 47.2.0+  
> **最后整理日期**: 2026-10-13  

---

## 目录
1. [项目概况与定位](#1-项目概况与定位)
2. [核心功能清单](#2-核心功能清单)
3. [核心子系统详解](#3-核心子系统详解)
   - [3.1 冒险区分数引擎 (Scoring Engine)](#31-冒险区分数引擎-scoring-engine)
   - [3.2 文礼生态深度兼容与覆写 (Civilization Integration)](#32-文礼生态深度兼容与覆写-civilization-integration)
   - [3.3 实体强化与生命周期管理 (Entity Buff System)](#33-实体强化与生命周期管理-entity-buff-system)
   - [3.4 玩家状态追踪与 HUD 交互 (Zone Transition & HUD)](#34-玩家状态追踪与-hud-交互-zone-transition--hud)
   - [3.5 冒险区压制方块与动画系统 (Adventure Suppressor)](#35-冒险区压制方块与动画系统-adventure-suppressor)
   - [3.6 开发者开放 API (Developer API)](#36-开发者开放-api-developer-api)
4. [配置体系 (Config)](#4-配置体系-config)
5. [数据驱动体系 (Data Packs)](#5-数据驱动体系-data-packs)
6. [项目代码与资源结构图](#6-项目代码与资源结构图)
7. [当前进行中与待展开工作 (Work In Progress)](#7-当前进行中与待展开工作-work-in-progress)

---

## 1. 项目概况与定位

**Civil Adventure** 是 Minecraft 1.20.1 Forge 模组，作为知名文明模拟模组 **[文礼 (Civillis)](https://modrinth.com/mod/civillis)** 的官方授权附属模组运行。

### 核心设计理念
文礼构建了以聚落、安全、繁荣为特征的“文明区”；而 **Civil Adventure** 构建了与其完全对立的**“冒险区” (Adventure Zone)**。
- 玩家或地图作者可以通过放置特定“冒险方块”产生冒险区分数；
- 当区域内冒险区分数达到阈值，将激活冒险区：在地图上显示专属颜色、在文礼文明探测器上显现声呐边界与空间包络；
- 区域内怪物自动获得属性加成与增益效果，离开后平滑回收；
- 冒险区具有最高优先级，激活时**强制覆盖文礼文明分（覆写为 0）**，使怪物免受文明逃逸限制，阻断文明扩散。

### 前置与依赖
- `minecraft`: 1.20.1
- `forge`: [47.2.0, )
- `civil` (文礼): [2.0.0, ) — 提供核心文明引擎、探测器及 HUD 框架
- `geckolib` (GeckoLib): [4.8.0, ) — 驱动压制方块与手持物品的模型与骨骼动画

---

## 2. 核心功能清单

| 模块分类 | 功能点 | 简要说明 | 关联文件 |
| :--- | :--- | :--- | :--- |
| **冒险区判定** | 方块权重加载 | 支持方块 ID 与 Tag 配置权重，数据包热重载 | `AdventureBlockLoader.java` |
| | 方块变更捕获 | 拦截 `Level.setBlock`，自动识别冒险方块变更 | `AdventureBlockChangeMixin.java` |
| | 批量冷却重算 | 变更延迟合并（防卡顿批处理），扫描全 16 层 Sections | `AdventureScoreService.java` |
| | 区块持久与漫游 | 区块加载全量扫描、卸载释放内存、持久键管理 | `AdventureScoreService.java`, `ChunkKey.java` |
| | 区域聚合与归一化 | 7×7 区块半径卷积聚合，除数归一化算法（≥0.7 触发） | `AdventureScoreService.java` |
| **文礼兼容覆写** | 文明分清零 | 冒险区内强行将文礼文明分返回 0.0，怪物不逃逸且正常生成 | `CivilizationScoreMixin.java` |
| | 区域类型改判 | HIGH(文明) 转为 ZONE(冒险)，文礼探测器效果全面适配 | `CivilRegionClassifierMixin.java` |
| | 声呐 BFS 覆写 | 探测器在文明区中切出冒险区边界，在荒野中凸显冒险区 | `SonarScanMixin.java` |
| | 探测器空间包络 | 反射合并冒险区单元格，在探测器上显示 3D 边界墙 | `ZonePolicyMixin.java` |
| | 地图与小地图染色 | 注入 Civil Band 6，Xaero 小地图显示自定义颜色与透明度 | `CivilMapTintPaletteMixin.java`, `XaerosColorMixin.java` |
| **实体强化** | 数据驱动强化 | JSON 定义怪物的属性修饰器与药水效果 | `AdventureEntityLoader.java`, `EntityConfig.java` |
| | 定时扫描与续期 | 仿文礼 TTL 缓存机制，活跃怪物持续 `touch()` 续期 | `AdventureZoneScanner.java` |
| | 离开平滑回收 | 离开冒险区超时后跨维度安全剥离修饰器 | `AdventureZoneScanner.java` |
| | 死亡即时清理 | 实体死亡事件触发清理，防内存泄漏 | `CivilAdventureMod.java`, `AdventureZoneScanner.java` |
| | 原生冒险区兼容 | 文礼原生的结构区(ZONE)与祭坛(SHRINE)同样生效 | `AdventureZoneScanner.java` |
| **交互与 HUD** | 进出边界检测 | 服务端 Tick 监听玩家归一化分，状态切换带 1 秒去抖 | `CivilAdventureMod.java` |
| | 文礼 HUD 对接 | 发送 S2C 网络包，触发文礼原生警报与荒野 HUD 动效 | `AdventureTransitionPayload.java`, `ClientPayloadHandler.java` |
| **冒险区压制方块** | 压制方块本体 | 3/4 格碰撞箱、防下表面黑面、支持红石控制与破坏掉落 | `AdventureSuppressorBlock.java` |
| | 燃料与计时 | 绿宝石/绿宝石块充能，计时器倒计时与状态冻结 | `AdventureFuelLoader.java`, `AdventureSuppressorBlockEntity.java` |
| | 分数压制与回弹 | 周期性削减周围冒险分；停用/被拆时 100% 立即回弹原始分 | `AdventureScoreService.java`, `AdventureSuppressorBlockEntity.java` |
| | GeckoLib 动画 | 状态机自动切换 `idle`/`overload`/`running`，包含开关过渡 | `AdventureSuppressorBlockEntity.java`, `AdventureSuppressorModel.java` |
| | 物品栏与手持渲染 | `SuppressorBlockItem` 接入 `GeoItem`，支持 GUI/手持动态渲染 | `SuppressorBlockItem.java` |
| **对外 API** | 公开 API 接口 | 提供给其他模组/KubeJS 查询冒险分、中心坐标、强化实体等 | `AdventureAPI.java` |

---

## 3. 核心子系统详解

### 3.1 冒险区分数引擎 (Scoring Engine)
* **方块权重与数据驱动 (`AdventureBlockLoader`)**:
  - 数据包路径: `data/<namespace>/adventure_blocks/*.json`。
  - 支持普通方块 ID（如 `minecraft:soul_campfire`）和方块标签（`#minecraft:campfires`）。
  - 支持 `"replace": true` 覆写上层定义，使用高效的 `IdentityHashMap<Block, Double>` 缓存。
* **变更拦截与批处理 (`AdventureBlockChangeMixin` & `AdventureScoreService`)**:
  - 拦截 `Level.setBlock`：使用 `ThreadLocal<ArrayDeque<BlockState>>` 保证状态栈平衡及服务端多线程安全。
  - 脏标记延迟批处理：方块变更时不立即全局重算，而是记录入 `dirtyChunks` 并开启冷却计时（配置项 `recalc_cooldown_ticks`，默认 5 tick），冷却期内合并多次变更，避免频繁炸服。
* **区块全高度扫描与生命周期**:
  - 扫描全 Section：扫描该区块所有 16 个 Y 轴层级 (Section 0~15)，按 16×16×16 方块累加权重。
  - 存储键自包含：`dimension:cx,cz,0`，不依赖文礼内部私有类。
  - 内存与生命周期：服务端启动/重载时装载配置；区块加载时全量扫描 (`recalculateFullChunk`)；区块卸载时移除缓存；停机时清理所有集合。
* **区域聚合与归一化结算**:
  - **区域聚合半径**: 默认 3 区块（覆盖 7×7 区块范围）。
  - **有效分算法**: `effectiveScore = max(0.0, 原始扫描分 - 压制方块贡献的临时压制量)`。
  - **归一化公式**: `normalizedScore = min(1.0, 区域有效分之和 / 5.0)`。
  - **激活判定**: 归一化分 ≥ 0.7 时判定为活跃冒险区。

---

### 3.2 文礼生态深度兼容与覆写 (Civilization Integration)
* **文明分清零 (`CivilizationScoreMixin`)**:
  - 拦截文礼 `ScalableCivilizationService.getScoreAt` 和 `getCScoreAt`。
  - 当坐标点归一化冒险分 ≥ `civ_override_threshold` (0.7) 时，返回值强行覆写为 0.0。
  - **效果**:
    1. 怪物不会逃逸（文礼的 `FleeCivilizationGoal` 获取到的文明分为 0）。
    2. 怪物正常刷怪自然生成（文礼 `SpawnPolicy` 见到的文明分为 0）。
    3. 文明扩散逻辑无法吞并冒险区。
* **区域类型转判与声呐探测器联动 (`CivilRegionClassifierMixin`, `SonarScanMixin`, `ZonePolicyMixin`)**:
  - **类型转判**: 若文礼原定为 `HIGH` (文明)，冒险区激活时强制转判为 `ZONE`。
  - **声呐 BFS**: 文明区内覆写为 LOW（在文明城墙中“挖出”冒险区）；荒野中覆写为 HIGH（在荒原中“显露”冒险区）。
  - **探测器空间包络**: 拦截 `SonarScanManager.computeZonePolicyData`，反射合并冒险区区块单元格，让文礼文明探测器手持时显示声呐边界墙及紫色粒子效果。
* **小地图高亮与染色 (`CivilMapTintPaletteMixin`, `XaerosColorMixin`)**:
  - 注入文礼 `CivilMapTintPalette`，将冒险区区块标为 `band 6` (ZONE)。
  - 注入 `CivilXaeroChunkBandHighlightSupport`，在 Xaero 小地图上为 ZONE 区域填充配置颜色（默认 `#CC1111`）与透明度（默认 180）。

---

### 3.3 实体强化与生命周期管理 (Entity Buff System)
* **数据驱动 (`AdventureEntityLoader`)**:
  - 配置文件路径: `data/<namespace>/adventure_entities/<entity_name>.json`（以 `.` 代替 `:`，如 `minecraft.zombie.json` 对应僵尸）。
  - 支持属性修改 (`attributes`): 指定 `attribute`、数值 `modifier`、计算模式 `operation` (0=加算, 1=基础乘算, 2=总乘算)。
  - 支持药水效果 (`effects`): 指定 `effect`、时长 `duration`、等级 `amplifier`。
* **定时扫描与 TTL 续期机制 (`AdventureZoneScanner`)**:
  - 间隔扫描：默认每 100 ticks (5 秒) 扫描一次所有实体。
  - 双重判定：不仅覆盖本模组的冒险区，同时兼容文礼原生由政策定义的结构冒险区 (`ZONE`) 和祭坛区 (`SHRINE`)。
  - 活跃续期：符合条件的活体实体被施加瞬态属性修饰器（UUID 基于实体与属性 ID 哈希绑定，防重复），并调用 `touch(currentTick)` 续期。
* **平滑回收与死亡即时释放**:
  - 超时回收：若实体离开冒险区超过 `buff_timeout_ticks` (默认 200 ticks = 10 秒)，在 `cleanupExpired()` 中跨维度查找该实体，彻底剥离添加的属性修饰器。
  - 死亡清理：监听 `LivingDeathEvent`，实体死亡时立刻清理内存映射，防止内存泄漏。

---

### 3.4 玩家状态追踪与 HUD 交互 (Zone Transition & HUD)
* **服务端监听 (`CivilAdventureMod.onServerTick`)**:
  - 遍历所有在线玩家，对比其上一 tick 的冒险区状态 (`prevZoneState`)。
  - 内置 20 ticks (1 秒) 的切换冷却，防止在边界反复横跳时网络包泛滥。
* **网络与客户端对接 (`AdventureTransitionPayload` & `ClientPayloadHandler`)**:
  - 发送轻量网络包至客户端。
  - 客户端直接调用文礼的 `ZoneTransitionHud.onPayload()`：
    - 进入冒险区：触发状态 2 (`CAUTION`，橙色警示 HUD)。
    - 离开冒险区：触发状态 1 (`WILDERNESS`，荒野蓝色 HUD)。

---

### 3.5 冒险区压制方块与动画系统 (Adventure Suppressor)
* **方块特性 (`AdventureSuppressorBlock`)**:
  - 碰撞箱与形状：定制 3/4 格高度 (`Shapes.box(0, 0, 0, 1.0, 0.75, 1.0)`)；`getOcclusionShape()` 为空，保证下表面平整无黑面。
  - 红石控制：检测邻近红石信号，红石信号跳变时自动启停；放置时若已有信号则立即激活；玩家右键手动干预优先。
  - 拆除与掉落：正确配置 Loot Table，破坏掉落本体；被活塞推动或破坏时立即触发 `releaseSuppression()` 释放压制。
* **燃料与倒计时机制 (`AdventureFuelLoader` & `AdventureSuppressorBlockEntity`)**:
  - 燃料数据包配置：默认绿宝石 (+1200 ticks)、绿宝石块 (+12000 ticks)。
  - 手持燃料右键：充能加时，在 Actionbar 弹出绿字提示。
  - 空手/非燃料右键：切换启用/停用；停用时计时冻结，剩余时间不丢失；计时归零时无法启用并自动释放。
* **分数动态压制与 100% 回弹算法**:
  - 每隔 `suppress.interval_ticks` (100 ticks)，在半径 `suppress.radius_chunks` (4 区块) 范围内进行压制。
  - 削减公式: 增加压制量 `original * percent + fixed` (默认 1% + 3.0 分)，上限为区块剩余分数。
  - 记录明细: 方块实体本地存储 `localSuppression` 字典。
  - **无损回弹**: 当方块停用、燃料耗尽或被破坏时，将所有已施加的压制量原原本本返还给 `AdventureScoreService`，冒险分立刻恢复。
* **GeckoLib 4 动画与渲染**:
  - 状态机设计:
    - `IDLE` (停用待机)
    - `OVERLOAD` (启用中但未完全压制，快速旋转)
    - `RUNNING` (启用中且已完全压制周围，平缓旋转)
    - 开关过渡: 停用→启用播放 `start`，启用→停用播放 `close`。
  - 物品与方块共用模型: `SuppressorBlockItem` 继承 `GeoItem`，绑定 `GeoItemRenderer`，物品栏及手持时展示模型；渲染类型设为 `RenderType.entityTranslucent` 以兼容半透明纹理。

---

### 3.6 开发者开放 API (Developer API)
位于 `civil_adventure.api.AdventureAPI`：
```java
// 获取指定世界坐标的归一化冒险区分数 [0.0, 1.0]
double score = AdventureAPI.getScoreAt(serverLevel, blockPos);

// 获取指定世界坐标检测半径内的区域原始分数和（无上限）
double raw = AdventureAPI.getRawScoreAt(serverLevel, blockPos);

// 获取指定位置检测半径内被判定为冒险区的区块总数量
int count = AdventureAPI.getZoneSize(serverLevel, blockPos);

// 计算检测半径内冒险区的几何中心点 BlockPos
BlockPos center = AdventureAPI.getZoneCenter(serverLevel, blockPos);

// 获取当前所有被冒险区 Buff 加持的实体 UUID 集合
Set<UUID> uuids = AdventureAPI.getBuffedEntityUUIDs();
```

---

## 4. 配置体系 (Config)

配置文件位于: `config/civil_adventure-common.toml`

```toml
[detection]
# 冒险区判定半径（区块），3 代表 7×7 区块范围
radius_chunks = 3

[scoring]
# 归一化除数：原始分 ÷ 此值 = 归一化分
normalization_divisor = 5.0
# 冒险区触发阈值（归一化分 ≥ 此值即视为冒险区）
zone_threshold = 0.7
# 覆盖文礼文明区的阈值（归一化分），达到此分即顶掉文明区
civ_override_threshold = 0.7

[xaeros_color]
# Xaeros 小地图冒险区填充颜色 (HEX RGB)
color = "CC1111"

[xaeros_alpha]
# Xaeros 小地图不透明度 (0-255)
alpha = 180

[hud]
# 进出冒险区时是否显示文礼风格 HUD 提示
enabled = true

[entity_scan]
# 实体强化检测扫描间隔（tick，20 tick = 1 秒）
interval_ticks = 100
# 离开冒险区后属性 Buff 保留时间（tick）
buff_timeout_ticks = 200
# 方块变化批量重算冷却（tick），合并批处理
recalc_cooldown_ticks = 5

[suppressor]
# 压制方块每次压制削减的百分比 (0.01 = 1%)
percent = 0.01
# 每次压制削减的固定分数
fixed = 3.0
# 压制动作间隔时间（tick）
interval_ticks = 100
# 压制影响半径（区块）
radius_chunks = 4
```

---

## 5. 数据驱动体系 (Data Packs)

### 5.1 冒险方块配置 (`adventure_blocks`)
路径: `data/<namespace>/adventure_blocks/*.json`
```json
{
  "replace": false,
  "entries": [
    { "block": "minecraft:soul_campfire", "weight": 8.0 },
    { "block": "#minecraft:campfires", "weight": 2.0 }
  ]
}
```

### 5.2 怪物强化配置 (`adventure_entities`)
路径: `data/<namespace>/adventure_entities/<entity_id>.json`（点号代替冒号）
例如 `data/civil_adventure/adventure_entities/minecraft.zombie.json`:
```json
{
  "attributes": [
    { "attribute": "minecraft:generic.attack_damage", "modifier": 0.2, "operation": 1 }
  ],
  "effects": [
    { "effect": "minecraft:fire_resistance", "duration": 300, "amplifier": 0 },
    { "effect": "minecraft:regeneration", "duration": 100, "amplifier": 0 }
  ]
}
```
* `operation`: `0` = 加算 (ADDITION), `1` = 基础乘算 (MULTIPLY_BASE), `2` = 独立乘算 (MULTIPLY_TOTAL)。

### 5.3 压制方块燃料配置 (`adventure_suppressor_fuel`)
路径: `data/<namespace>/adventure_suppressor_fuel/*.json`
```json
{
  "replace": false,
  "entries": [
    { "item": "minecraft:emerald", "ticks": 1200 },
    { "item": "minecraft:emerald_block", "ticks": 12000 }
  ]
}
```

---

## 6. 项目代码与资源结构图

```
civil_adventure/
├── src/main/java/civil_adventure/
│   ├── CivilAdventureMod.java                    # 模组主类，生命周期与事件监听
│   ├── api/
│   │   └── AdventureAPI.java                     # 公开给外部与附属的 API
│   ├── block/
│   │   ├── AdventureSuppressorBlock.java         # 压制方块：方块行为、红石、交互、碰撞箱
│   │   ├── AdventureSuppressorBlockEntity.java   # 压制方块实体：计时器、压制/回弹、GeckoLib 动画
│   │   └── SuppressorBlockItem.java              # 压制方块物品：GeckoLib BEWLR 渲染
│   ├── client/
│   │   ├── AdventureSuppressorBlockRenderer.java # GeckoLib 方块渲染器
│   │   ├── AdventureSuppressorModel.java         # 渲染模型类（半透明与纹理配置）
│   │   ├── ClientDiagnostics.java                # GeckoLib 模型烘焙与资源诊断监听
│   │   ├── ClientPayloadHandler.java             # 客户端 HUD 数据包响应处理
│   │   └── ClientSetup.java                      # 客户端方块实体渲染器注册
│   ├── config/
│   │   └── AdventureConfig.java                  # Forge ConfigSpec 配置定义
│   ├── data/
│   │   ├── AdventureEntityLoader.java            # 数据包：怪物强化配置加载器
│   │   ├── AdventureFuelLoader.java              # 数据包：压制燃料配置加载器
│   │   └── EntityConfig.java                     # 实体强化数据定义 Record
│   ├── mixin/
│   │   ├── AdventureBlockChangeMixin.java        # 拦截 Level.setBlock 触发分数更新
│   │   ├── CivilizationScoreMixin.java           # 强行覆写文礼文明分为 0
│   │   ├── CivilMapTintPaletteMixin.java         # 地图色调覆写为冒险区 Band 6
│   │   ├── CivilRegionClassifierMixin.java       # 区域类型 HIGH 转 ZONE 覆写
│   │   ├── SonarScanMixin.java                   # 文礼声呐探测器 BFS 边界覆写
│   │   ├── XaerosColorMixin.java                 # Xaero 小地图冒险区颜色与透明度注入
│   │   └── ZonePolicyMixin.java                  # 探测器 3D 边界包络单元格反射注入
│   ├── network/
│   │   └── AdventureTransitionPayload.java       # 玩家进出冒险区 S2C 网络数据包
│   └── score/
│       ├── AdventureBlockLoader.java             # 数据包：冒险方块权重加载器
│       ├── AdventureScoreService.java            # 核心算分、区块批处理、聚合与压制引擎
│       ├── AdventureZoneScanner.java             # 实体定时扫描、TTL 续期与 Buff 回收
│       └── ChunkKey.java                         # 自包含区块坐标 Record
└── src/main/resources/
    ├── civil_adventure.mixins.json               # Mixin 配置文件
    ├── assets/civil_adventure/
    │   ├── animations/block/                     # GeckoLib 动画 (idle, overload, running, start, close)
    │   ├── blockstates/                          # 方块状态定义（指向空模型由 GeckoLib 接管）
    │   ├── geo/block/                            # GeckoLib 模型定义文件
    │   ├── lang/zh_cn.json                       # 语言文本
    │   ├── models/block/ & models/item/          # 方块与物品模型描述
    │   └── textures/block/                       # 方块纹理贴图（透明与不透明版本）
    └── data/civil_adventure/
        ├── adventure_blocks/                     # 默认冒险方块权重配置
        ├── adventure_entities/                   # 默认怪物 Buff 配置（僵尸、骷髅）
        ├── adventure_suppressor_fuel/            # 默认压制燃料配置（绿宝石、绿宝石块）
        └── loot_tables/blocks/                   # 压制方块掉落物表
```

---

## 7. 当前进行中与待展开工作 (Work In Progress)

1. **压制方块模型渲染优化（参考 Biomancy 模式）**:
   - **历史问题**: 渲染时曾出现“静止完整模型 + 动画模型”叠加闪烁的现象。
   - **已实施措施**:
     - 清空了 `models/block/adventure_suppressor.json` 的静态 elements；
     - 扩大了 geo 模型的 `visible_bounds` (32×20×32) 防视野剔除；
     - 拍平 Blockbench 导出的动画关键帧通道；
     - 规范了状态机触发逻辑与时间轴获取。
   - **下阶段工作**:
     - 参考 Biomancy 模组的 `setHidden` 骨骼分层动态显隐方案；
     - 测试完全不透明纹理与半透明纹理在各种光影和深度测试下的渲染表现；
     - 彻底消除模型在不同状态切换时的残影与叠层。
2. **音效补全**:
   - 压制方块的状态机音效（`idle`/`overload`/`running`/`start`/`close`）预设位置已留好，待配置音频资源与注册。
3. **扩展功能与后续规划**:
   - 视后续需求展开（如更多冒险区专属方块、KubeJS 深度事件绑定、更多与文礼聚落互动的玩法机制等）。
