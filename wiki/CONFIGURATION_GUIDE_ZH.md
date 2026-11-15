# Civil Adventure (文礼·地缘对抗生态引擎) 深度配置手册 (中文版)

> **适用版本**：`civil_adventure 1.4.0+`  
> **面向对象**：整合包作者 (Packmaker)、服务器管理员、数据包 (Datapack) 与 KubeJS 开发者  
> **English Version**: [CONFIGURATION_GUIDE_EN.md](./CONFIGURATION_GUIDE_EN.md)

---

## 目录
1. [Forge 主配置文件 (`civil_adventure-common.toml`)](#一forge-主配置文件)
2. [派系数据包规格 (`adventure_factions/*.json`)](#二派系数据包规格)
3. [独立实体数值数据包 (`adventure_entities/*.json`)](#三独立实体数值数据包)
4. [压制核心燃料数据包 (`adventure_suppressor_fuel/*.json`)](#四压制核心燃料数据包)
5. [战术大地图 CoreShader 覆层预设 (`OverlayProfile`)](#五战术大地图-coreshader-覆层预设)
6. [KubeJS 与开发者 API 联动](#六kubejs-与开发者-api-联动)

---

## 一、Forge 主配置文件

文件路径：`config/civil_adventure-common.toml`

### 1. `[detection]` — 冒险区检测范围
| 配置键 | 类型 | 默认值 | 范围 | 详细说明 |
| :--- | :---: | :---: | :---: | :--- |
| `radius_chunks` | 整数 | `3` | `1 ~ 16` | 以查询点为中心的检测半径（区块）。`3` 代表 $(2 \times 3 + 1) = 7 \times 7$ 个区块的视窗范围。 |

---

### 2. `[scoring]` — 威胁分与判定阈值
| 配置键 | 类型 | 默认值 | 范围 | 详细说明 |
| :--- | :---: | :---: | :---: | :--- |
| `normalization_divisor` | 浮点 | `5.0` | `0.1 ~ 1000.0` | 归一化除数：$\text{归一化分} = \frac{\text{原始威胁总分}}{\text{除数}}$。用于向后兼容文礼的 `0.0 ~ 1.0` 标量体系。 |
| `zone_threshold` | 浮点 | `0.7` | `0.01 ~ 1.0` | 冒险区触发阈值：归一化分 $\ge$ 该值时判定进入该派系冒险区。 |
| `civ_override_threshold` | 浮点 | `0.7` | `0.0 ~ 1.0` | 覆盖文礼文明区的阈值：当某个非文明派系的威胁分超过此阈值时，强行将文礼 HIGH 级别文明领地顶替为 ZONE 危险区。设为 `0` 代表任何方块存在即压制文明区。 |

---

### 3. `[suppressor]` — 突击前哨压制核心
控制方块 `civil_adventure:adventure_suppressor` 的削减力场与波前扩散行为：

| 配置键 | 类型 | 默认值 | 范围 | 详细说明 |
| :--- | :---: | :---: | :---: | :--- |
| `percent` | 浮点 | `0.01` | `0.0 ~ 1.0` | 每次脉冲削减的百分比（`0.01` 代表 $1\%$）。作用于结构基础分与多派系纯方块分。 |
| `fixed` | 浮点 | `3.0` | `0.0 ~ 1000.0` | 每次脉冲扣除的固定保底分数。中心区块（$dist = 0$）享有 $3.0\times$ 聚焦倍率（即扣除 $9.0$ 分/次），随距离平滑衰减至 $1.0\times$。 |
| `interval_ticks` | 整数 | `100` | `10 ~ 12000` | 压制脉冲动作周期（tick）。$20\text{ tick} = 1\text{ 秒}$，默认 $100\text{ tick} = 5\text{ 秒}$。 |
| `radius_chunks` | 整数 | `4` | `1 ~ 32` | 压制最大欧氏半径（区块）。默认 `4` 代表最大覆盖约 64 格半径的圆形庇护所。波前以每 20 秒外扩 1 区块的速度递增，约 70~90 秒推满。 |
| `ignored_factions` | 字符串列表 | `[]` | - | 压制核心豁免的派系白名单列表。被忽略的派系（如玩家友好的自定义自然派系）不会被压制扣分。示例：`["my_mod:friendly_nature"]`。 |

---

### 4. `[map_overlay]` — 战术大地图与 CoreShader 覆层
深度联动 Xaero's World Map 与战争迷雾引擎：

| 配置键 | 类型 | 默认值 | 范围 | 详细说明 |
| :--- | :---: | :---: | :---: | :--- |
| `enabled` | 布尔 | `true` | `true/false` | 是否开启基于 OpenGL CoreShader 的动态战争迷雾与故障风覆层渲染。 |
| `scout_radius_chunks` | 整数 | `4` | `1 ~ 16` | 玩家走动时探明战争迷雾的半径（区块）。`4` 代表玩家为中心的 $9 \times 9$ 区块视野。探明状态按维度独立记录，且跨 Region 边界自动实时重绘。 |
| `default_profile` | 字符串 | `"civil_adventure:cyber_warning"` | - | 默认未探明危险区或通用覆层的主题预设 ID。支持数据包扩展。 |

---

### 5. `[xaeros_color]` 与 `[xaeros_alpha]` — Xaero 地图雷达外观
| 配置键 | 类型 | 默认值 | 范围 | 详细说明 |
| :--- | :---: | :---: | :---: | :--- |
| `color` | 字符串 | `"F08252"` | 十六进制色 | 默认灾厄冒险区在小地图上的填充色（温润珊瑚橙）。真菌感染等数据包派系优先读取其专属 `color`。 |
| `alpha` | 整数 | `255` | `0 ~ 255` | 小地图未探明黑盒的不透明度。`255` 代表完全实心纯黑，遮蔽地表地形，营造沉浸式迷雾。 |
| `map_overlay_opacity` | 浮点 | `1.0` | `0.0 ~ 1.0` | 战术大地图 CoreShader 遮罩不透明度。`1.0` 代表实心不透明，消去地表地形可视性。 |

---

### 6. `[entity_scan]` 与 `[hud]` — 实体强化与地缘提示
| 配置键 | 类型 | 默认值 | 范围 | 详细说明 |
| :--- | :---: | :---: | :---: | :--- |
| `interval_ticks` | 整数 | `100` | `20 ~ 12000` | 生物地缘强化扫描频次（tick）。内部带 10-tick 节流步进。 |
| `buff_timeout_ticks` | 整数 | `200` | `20 ~ 12000` | 生物离开冒险区后属性暂态加成的保留时间（防抖租约），租约到期后安全剥离 Tag 与加成。 |
| `recalc_cooldown_ticks` | 整数 | `5` | `1 ~ 100` | 方块放置/破坏后的脏标记聚合防抖延迟（tick）。高频炸弹或连锁挖矿时，在冷却结束后仅执行一次统一重算。 |
| `hud.enabled` | 布尔 | `true` | `true/false` | 玩家跨越地缘边界（如从荒野进入真菌领地、或两派系领地交界）时，屏幕中央是否展示动态横幅警报。 |

---

## 二、派系数据包规格

派系是地缘生态系统的核心实体。所有派系定义均位于数据包：  
`data/<namespace>/adventure_factions/<faction_id>.json`

### 完整规格字段表
| 字段名 | 类型 | 必填 | 说明与示例 |
| :--- | :---: | :---: | :--- |
| `name` | String | 是 | 派系本地化翻译键。例如 `"civil_adventure.faction.fungal.name"`。 |
| `color` | String | 是 | 派系战术代表色（Hex ARGB/RGB）。例如 `"#33CC55"` 或 `"#D32F2F"`。用于地图渲染、边界墙与雷达。 |
| `min_dominance_score` | Double | 否 | 宣称领地统治权的最低总分门槛（默认 `1.0`）。聚类总分低于此值时退化为无统治荒野。 |
| `structures` | Array | 否 | 该派系旗下的结构列表。例如 `["spore:biomass_tower", "spore:lab"]`。 |
| `structure_base_score` | Double | 否 | 旗下结构的基础威胁分（默认 `500.0`）。该分值会自动按结构 Bounding Box 覆盖区块平摊。 |
| `entity_whitelist` | Object | 是 | 派系麾下生物白名单。支持 `tags`（实体标签）与 `entities`（实体注册名列表）。 |
| `is_tag` | String | 是 | 生物身处该派系领地时自动赋予的 Forge 实体 Tag。例如 `"civil_adventure:is_fungal"`。 |
| `not_tag` | String | 是 | 生物离开该派系领地时赋予的反向 Tag。例如 `"civil_adventure:not_fungal"`。 |
| `blocks` | Object/Array | 是 | 构成该派系威胁度的危险方块列表及其分值权重。 |
| `dominant_buffs` | Object | 否 | 该派系在自身统治领地内为麾下白名单生物提供的专属增益（包含属性修饰符与药水）。 |

---

### 标准范例 1：真菌感染派系 (`fungal.json`)
```json
{
  "name": "civil_adventure.faction.fungal.name",
  "color": "#33CC55",
  "min_dominance_score": 10.0,
  "structures": [
    "spore:biomass_tower",
    "spore:lab",
    "spore:mines"
  ],
  "structure_base_score": 500.0,
  "entity_whitelist": {
    "tags": [
      "#civil_adventure:fungal_entities"
    ],
    "entities": [
      "spore:inf_human",
      "spore:proto",
      "spore:verfall",
      "spore:vigil"
    ]
  },
  "is_tag": "civil_adventure:is_fungal",
  "not_tag": "civil_adventure:not_fungal",
  "blocks": {
    "spore:biomass": 2.0,
    "spore:mycelium": 1.0,
    "spore:hive_spawn": 15.0
  },
  "dominant_buffs": {
    "attributes": [
      {
        "attribute": "minecraft:generic.attack_damage",
        "modifier": 0.25,
        "operation": 2
      },
      {
        "attribute": "minecraft:generic.max_health",
        "modifier": 0.30,
        "operation": 2
      }
    ],
    "effects": [
      {
        "effect": "minecraft:regeneration",
        "duration": 200,
        "amplifier": 1
      }
    ]
  }
}
```

> [!TIP]
> **属性修饰符 Operation 操作符说明**：
> - `0` (ADD_VALUE)：基础累加（例如 $+5.0$ 攻击力）；
> - `1` (MULTIPLY_BASE)：基于基础值的百分比乘算（例如 $+20\%$ 基础生命）；
> - `2` (MULTIPLY_TOTAL)：在最终总值上复合乘算（推荐！例如 $\times 1.25$，兼容性最佳）。

---

## 三、独立实体数值数据包

除了派系全局兜底的 `dominant_buffs` 之外，若需要对特定 Boss、精英怪实现精细化的阶梯式属性强化，可配置：  
`data/<namespace>/adventure_entities/<entity_namespace>.<entity_path>.json`

### 范例：对特定真菌变异体 (`spore.inf_human.json`) 单独强化
```json
{
  "_comment": "真菌人类精英专属强化：大幅增强韧性与击退抗性",
  "attributes": [
    {
      "attribute": "minecraft:generic.knockback_resistance",
      "modifier": 0.5,
      "operation": 0
    },
    {
      "attribute": "minecraft:generic.armor",
      "modifier": 8.0,
      "operation": 0
    }
  ],
  "effects": [
    {
      "effect": "minecraft:speed",
      "duration": 300,
      "amplifier": 1
    }
  ]
}
```

---

## 四、压制核心燃料数据包

压制核心通过数据驱动支持任意模组物品作为燃料：  
`data/<namespace>/adventure_suppressor_fuel/*.json`

### 范例：自定义高能燃料配置 (`custom_fuels.json`)
```json
{
  "fuels": [
    {
      "item": "minecraft:emerald",
      "ticks": 1200
    },
    {
      "item": "minecraft:emerald_block",
      "ticks": 12000
    },
    {
      "item": "minecraft:nether_star",
      "ticks": 72000
    },
    {
      "tag": "forge:storage_blocks/diamond",
      "ticks": 24000
    }
  ]
}
```
*注：$20\text{ ticks} = 1\text{ 秒}$。$1200\text{ ticks} = 1\text{ 分钟}$；$12000\text{ ticks} = 10\text{ 分钟}$。*

---

## 五、战术大地图 CoreShader 覆层预设

大地图战术渲染预设注册于客户端（数据驱动），用于控制未探明区域的动效风格：

```json
{
  "id": "civil_adventure:fungal_spore",
  "base_color": "#0A0608",
  "border_color": "#E61919",
  "glitch_intensity": 0.08,
  "scanline_speed": 1.2,
  "noise_texture": "civil_adventure:textures/map/adventure_phase2.png"
}
```

---

## 六、KubeJS 与开发者 API 联动

模组内置完备的公开 API：`civil_adventure.api.AdventureAPI` 与 Forge 事件总线。

### 1. 监听地缘统治权更替事件 (`FactionDominanceChangeEvent`)
当一片宏观领地的统治派系发生更替（如玩家用压制核心将真菌推平易主，或灾厄被真菌反超）时触发：

```javascript
// kubejs/server_scripts/geopolitics_events.js
ForgeEvents.onEvent('civil_adventure.faction.event.FactionDominanceChangeEvent', event => {
    let level = event.getLevel();
    let chunkPos = event.getChunkPos();
    let oldDom = event.getOldDominantIndex();
    let newDom = event.getNewDominantIndex();
    let score = event.getDominantScore();

    if (newDom === -1) {
        // 领地被彻底肃清瓦解，回归自然荒野！
        level.tell(`§e[战报] 坐标 (${chunkPos.x * 16}, ${chunkPos.z * 16}) 的敌对生态领地已被彻底肃清！`);
    } else {
        // 领地易帜
        level.tell(`§c[警报] 坐标 (${chunkPos.x * 16}, ${chunkPos.z * 16}) 爆发地缘冲突，新统治者已确立！威胁分: ${score.toFixed(1)}`);
    }
});
```

### 2. 通过 `AdventureAPI` 运行时查询
```javascript
// 查询玩家脚下宏观领地状态
let api = Java.loadClass('civil_adventure.api.AdventureAPI');
let cluster = api.getClusterAt(player.level, player.blockPosition());

if (cluster != null && cluster.hasDominance()) {
    let faction = cluster.getDominantFaction();
    player.tell(`当前身处 [${faction.id()}] 领地，中心位于: ${cluster.getEpicenter()}`);
}
```
