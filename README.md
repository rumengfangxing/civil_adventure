# Civil Adventure

> English · [中文](#chinese)

A **Civilization** addon that adds **Adventure Zones** — the counterpart to civilization zones. Place blocks to generate adventure scores, which create adventure zones on the map, buff mobs inside them, and override civilization effects.

---

## <span id="chinese">中文</span>

# Civil Adventure — 文礼冒险区拓展

**Civil Adventure** 是 [文礼 (Civillis)](https://modrinth.com/mod/civillis) 的附属模组。放置冒险区方块产生冒险区分数，触发冒险区后在地图上显示特殊颜色，区域内怪物自动获得属性加成，离开后超时回收，同时顶掉文礼的文明区判定。

---

## Features / 功能

### 🗺️ Adventure Zone / 冒险区

**EN:** Place adventure blocks to generate chunk scores via **full-height scan + regional aggregation**:

- Intercepts `Level.setBlock` on block change, scans all 16 Y-layers
- Auto-rescans on chunk load — scores persist across unloads
- Aggregates all chunks within **detection radius** (default 3 chunks = 7×7 area)
- Normalized: `min(1.0, raw / 5.0)`, **≥ 0.7** triggers adventure zone
- Breaking blocks reduces score; zone fades below threshold

**CN:** 放置冒险方块产生区块分数，方块变化时全高度 16 层扫描，区块加载自动重算。查询时聚合检测半径内所有区块，归一化分 ≥ 0.7 触发冒险区，破坏方块消退。

### 🏛️ Civil Native Zone Compatibility / 兼容文礼原生冒险区

**EN:** Mobs are also buffed inside Civil's **ZONE** (structure policies) and **SHRINE** (farm shrine) regions, no extra config needed.

**CN:** 兼容文礼 `civil_zone_policies` 定义的结构冒险区（ZONE）和祭坛（SHRINE），实体同样触发强化。

### ⚔️ Adventure vs Civilization / 冒险区 vs 文明区

**EN:** When active, adventure zones override Civil's civilization score to **zero**, achieving full mutual exclusion:

- Mobs **do not flee** (FleeCivilizationGoal intercepted)
- Mobs **spawn normally** (SpawnPolicy sees civ score = 0)
- Civilization spread does NOT cover adventure zones
- Xaeros minimap shows configurable adventure zone color

**CN:** 冒险区活跃时文明分被覆写为 0，怪物不逃逸、正常生成，文明扩散不覆盖冒险区。

### 👹 Entity Buffs / 实体强化

**EN:** Mobs inside adventure zones get configurable attribute modifiers and potion effects (supports both custom and Civil native zones).

- **TTL refresh**: entities in zone keep `touch()`-ing their entry; attributes auto-retract after timeout
- **Configurable** scan interval (default 100 ticks = 5s) and buff timeout (200 ticks = 10s)
- **Memory-safe**: `TimestampedEntry` + periodic `cleanupExpired()`, plus `LivingDeathEvent` cleanup

**CN:** 冒险区内怪物获得配置的属性加成和药水效果。TTL 续期，离区超时回收，死亡即时清理。

### 🎯 Detector Integration / 文明探测器联动

**EN:** Adventure zone boundaries are visible on Civil's detector:

- `SonarScanMixin` bi-directional BFS override (adventure → LOW in civ, → HIGH in wilderness)
- Sonar boundary walls + particles work for adventure zones
- Xaeros minimap color is configurable (with alpha)

**CN:** 文明探测器上可见冒险区边界墙和粒子效果，Xaeros 颜色可配置。

### 🔌 API

```java
AdventureAPI.getScoreAt(level, pos);          // Normalized score [0, 1]
AdventureAPI.getZoneSize(level, pos);         // Adventure chunk count in radius
AdventureAPI.getBuffedEntityUUIDs();          // Currently buffed entity UUIDs
```

---

## Config / 配置

**EN:** Auto-generated at `config/civil_adventure-common.toml` on first launch.

**CN:** 首次启动自动生成。

```toml
[detection]
# Adventure zone detection radius (chunks). 3 = 7×7 area
# 冒险区判定半径（区块），3 = 7×7 区块区域
radius_chunks = 3

[scoring]
normalization_divisor = 5.0
zone_threshold = 0.7
# Civilization override threshold. 0 = any block triggers override
# 覆盖文礼文明区的阈值，0=激进模式
civ_override_threshold = 0.7

[xaeros_color]
# Adventure zone color (hex RGB)
# 冒险区色 (hex RGB)
color = "CC1111"

[xaeros_alpha]
# Opacity (0-255)
# 不透明度 (0-255)
alpha = 180

[hud]
# Show HUD on enter/leave
# 进出冒险区时显示提示
enabled = true

[entity_scan]
# Scan interval in ticks (20 = 1s)
# 实体检测间隔（tick）
interval_ticks = 100
# Buff retention after leaving zone (ticks)
# 离开冒险区后属性保留时间（tick）
buff_timeout_ticks = 200
```

---

## Data Packs / 数据包

### Adventure Blocks / 冒险区方块

`data/civil_adventure/adventure_blocks/*.json` — supports block IDs and tags (`#` prefix):

```json
{
  "replace": false,
  "entries": [
    { "block": "minecraft:soul_campfire", "weight": 8.0 }
  ]
}
```

### Entity Buffs / 实体强化

`data/civil_adventure/adventure_entities/<entity_id>.json`

Dots replace colons: `minecraft.zombie.json` → entity `minecraft:zombie`

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

| Field / 字段 | Description / 说明 |
|---|---|
| `attribute` | Attribute ID (e.g. `minecraft:generic.attack_damage`) |
| `modifier` | Modifier value / 修饰值 |
| `operation` | 0 = addition / 加算, 1 = multiply_base / 基础乘算, 2 = multiply_total / 独立乘算 |
| `effect` | Effect ID / 药水效果 ID |
| `duration` | Ticks (20 = 1s) / 持续 tick |
| `amplifier` | Level (0 = I) / 效果等级 |

---

## Project Structure / 项目结构

```
civil_adventure/
├── CivilAdventureMod.java           # Entry point / 主入口
├── api/AdventureAPI.java            # Public API
├── client/ClientPayloadHandler.java # Client HUD handler / 客户端 HUD
├── config/AdventureConfig.java      # ForgeConfigSpec
├── data/
│   ├── EntityConfig.java
│   └── AdventureEntityLoader.java
├── mixin/
│   ├── AdventureBlockChangeMixin.java   # Intercept Level.setBlock
│   ├── CivilMapTintPaletteMixin.java    # Map tint band 6 / 地图染色
│   ├── CivilizationScoreMixin.java      # Override civ score to 0 / 文明分覆写
│   ├── CivilRegionClassifierMixin.java  # Override civ classification / 分类覆写
│   ├── SonarScanMixin.java              # Detector BFS override / 探测器覆写
│   └── XaerosColorMixin.java            # Xaeros minimap color
├── network/
│   └── AdventureTransitionPayload.java
└── score/
    ├── AdventureBlockLoader.java
    ├── AdventureScoreService.java   # Core scoring + aggregation / 核心结算
    ├── AdventureZoneScanner.java    # Entity scan + TTL cache
    └── ChunkKey.java
```

## Build / 构建

```bash
./gradlew build
# Output: build/libs/civil_adventure-<version>.jar
```

---

## Community / 社区友好

**EN:** Issues and PRs welcome — describe your reasoning and suggested changes.

**CN:** 欢迎在 Issues 中提出修改建议，请附上理由。

## AI Assistance / AI 辅助声明

**EN:** This mod was developed with AI (Claude) assistance; all code was reviewed before release.

**CN:** 本模组由 AI（Claude）辅助开发，代码经人工审核后发布。

## License & Credits / 授权与致谢

**EN:** Published with permission from the original Civillis author **MaoXinZ**.

**CN:** 本模组已获得文礼作者 MaoXinZ 的发布许可。

**Scoring Engine / 算分引擎:** Civillis's high-performance scoring engine was independently developed by MaoXinZ over hundreds of hours. The aggregation and normalization logic in this mod references Civil's design under authorization. For reuse, please credit the source and contact MaoXinZ for permission.

**文礼的高性能算分引擎由 MaoXinZ 独立原创开发。本模组中涉及区块分数聚合与归一化判定的逻辑借鉴了文礼的设计思路，并在授权范围内使用。如需复用请标注技术出处并联系作者获取授权。**

### Copyright / 版权

- **Civillis (文礼)** © MaoXinZ — All Rights Reserved
- **Civil Adventure** © Contributors — All Rights Reserved (ARR)
