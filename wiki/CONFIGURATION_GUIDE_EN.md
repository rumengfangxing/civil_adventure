# Civil Adventure — In-Depth Configuration Guide (English)

> **Target Version**: `civil_adventure 1.4.0+`  
> **Audience**: Modpack Creators, Server Administrators, Datapack & KubeJS Developers  
> **中文版本 (Chinese Version)**: [CONFIGURATION_GUIDE_ZH.md](./CONFIGURATION_GUIDE_ZH.md)

---

## Table of Contents
1. [Forge Main Configuration (`civil_adventure-common.toml`)](#1-forge-main-configuration)
2. [Faction Datapack Specification (`adventure_factions/*.json`)](#2-faction-datapack-specification)
3. [Independent Entity Attribute Datapack (`adventure_entities/*.json`)](#3-independent-entity-attribute-datapack)
4. [Suppressor Fuel Datapack (`adventure_suppressor_fuel/*.json`)](#4-suppressor-fuel-datapack)
5. [Tactical World Map CoreShader Overlay Profiles](#5-tactical-world-map-coreshader-overlay-profiles)
6. [KubeJS & Developer API Integration](#6-kubejs--developer-api-integration)

---

## 1. Forge Main Configuration

File path: `config/civil_adventure-common.toml`

### 1.1 `[detection]` — Adventure Zone Query Range
| Key | Type | Default | Range | Description |
| :--- | :---: | :---: | :---: | :--- |
| `radius_chunks` | Integer | `3` | `1 ~ 16` | Search radius in chunks centered around the query coordinate. `3` represents a window of $(2 \times 3 + 1) = 7 \times 7$ chunks. |

---

### 1.2 `[scoring]` — Threat Scores & Determination Thresholds
| Key | Type | Default | Range | Description |
| :--- | :---: | :---: | :---: | :--- |
| `normalization_divisor` | Double | `5.0` | `0.1 ~ 1000.0` | Normalization divisor: $\text{Normalized Score} = \frac{\text{Raw Threat Score}}{\text{Divisor}}$. Used for backward compatibility with Civillis's `0.0 ~ 1.0` scalar domain. |
| `zone_threshold` | Double | `0.7` | `0.01 ~ 1.0` | Adventure Zone trigger threshold: A chunk is classified as an Adventure Zone when normalized score $\ge$ this threshold. |
| `civ_override_threshold` | Double | `0.7` | `0.0 ~ 1.0` | Civilization suppression threshold: When an adversarial faction's threat score exceeds this value, it forcefully replaces a Civillis HIGH civilization region with an adversarial adventure ZONE. Setting to `0` suppresses civilization if any hostile block is present. |

---

### 1.3 `[suppressor]` — Outpost Suppression Core Mechanics
Controls the behavior of the `civil_adventure:adventure_suppressor` block, including its wavefront radius and pulse deduction:

| Key | Type | Default | Range | Description |
| :--- | :---: | :---: | :---: | :--- |
| `percent` | Double | `0.01` | `0.0 ~ 1.0` | Percentage of score deducted per pulse (`0.01` = $1\%$). Applies to both structure base scores and raw faction block scores. |
| `fixed` | Double | `3.0` | `0.0 ~ 1000.0` | Base flat score deducted per pulse. The epicenter chunk ($dist = 0$) receives a $3.0\times$ focal multiplier (deducting $9.0$ pts/pulse), which smoothly tapers off to $1.0\times$ at the outer edge. |
| `interval_ticks` | Integer | `100` | `10 ~ 12000` | Action pulse interval in game ticks ($20\text{ ticks} = 1\text{ second}$, default $100\text{ ticks} = 5\text{ seconds}$). |
| `radius_chunks` | Integer | `4` | `1 ~ 32` | Maximum Euclidean suppression radius in chunks. Default `4` covers a circular sanctuary of $\approx 64$ blocks radius. The wavefront expands outward at 1 chunk per 20 seconds, taking 70–90 seconds to fully deploy. |
| `ignored_factions` | String List | `[]` | - | Whitelist of faction IDs exempt from suppression. Ignored factions (e.g. friendly nature spirits) will never lose threat score to suppressors. Example: `["my_mod:friendly_nature"]`. |

---

### 1.4 `[map_overlay]` — Tactical World Map & CoreShader Overlay
Seamless integration with Xaero's World Map and OpenGL CoreShader dynamic fog:

| Key | Type | Default | Range | Description |
| :--- | :---: | :---: | :---: | :--- |
| `enabled` | Boolean | `true` | `true/false` | Enables OpenGL CoreShader dynamic war fog and glitch scanline rendering over hostile zones. |
| `scout_radius_chunks` | Integer | `4` | `1 ~ 16` | Reconnaissance clearing radius (in chunks) as the player explores. `4` provides a $9 \times 9$ chunk vision cone. Scouted states persist per dimension and cross Region boundaries smoothly. |
| `default_profile` | String | `"civil_adventure:cyber_warning"` | - | Default visual profile ID for unscouted danger zones or fallback overlays. Extendable via datapacks. |

---

### 1.5 `[xaeros_color]` & `[xaeros_alpha]` — Minimap Radar Appearance
| Key | Type | Default | Range | Description |
| :--- | :---: | :---: | :---: | :--- |
| `color` | String | `"F08252"` | Hex RGB | Default Illager adventure zone fill color on minimaps (Warm Coral Orange). Custom datapack factions override this using their declared `color`. |
| `alpha` | Integer | `255` | `0 ~ 255` | Opacity of unscouted minimap fog-of-war tiles. `255` produces solid black to obscure terrain and create immersive tactical fog. |
| `map_overlay_opacity` | Double | `1.0` | `0.0 ~ 1.0` | Fullscreen tactical map CoreShader mask opacity. `1.0` represents a fully opaque mask, occluding underlying terrain. |

---

### 1.6 `[entity_scan]` & `[hud]` — Entity Buffs & Geopolitical HUD
| Key | Type | Default | Range | Description |
| :--- | :---: | :---: | :---: | :--- |
| `interval_ticks` | Integer | `100` | `20 ~ 12000` | Frequency of entity geopolitical buff scans in ticks (throttled in 10-tick micro-steps). |
| `buff_timeout_ticks` | Integer | `200` | `20 ~ 12000` | Debounce hysteresis lease (ticks). When a mob leaves its home territory, its transient stat modifiers and tags persist for this duration before safe detachment. |
| `recalc_cooldown_ticks` | Integer | `5` | `1 ~ 100` | Debounce cooldown delay for block dirty-marking. Under high-frequency explosions or vein mining, cluster recalculation runs only once after this delay. |
| `hud.enabled` | Boolean | `true` | `true/false` | Displays a dynamic center-screen warning banner when crossing geopolitical boundaries (e.g. entering Fungal Hive territory from the Wild). |

---

## 2. Faction Datapack Specification

Factions are the core entities of the geopolitical ecosystem. Faction definitions reside in:  
`data/<namespace>/adventure_factions/<faction_id>.json`

### Complete Schema Specification
| Field | Type | Required | Description & Example |
| :--- | :---: | :---: | :--- |
| `name` | String | Yes | I18n localization translation key. Example: `"civil_adventure.faction.fungal.name"`. |
| `color` | String | Yes | Tactical hex color (`#RRGGBB` or `#AARRGGBB`). Example: `"#D32F2F"`. Used for minimap tiles, boundary shaders, and radars. |
| `min_dominance_score` | Double | No | Minimum total score required to claim territorial dominance (default `1.0`). If total cluster score is below this, it remains unclaimed wilderness. |
| `structures` | Array | No | Structure template IDs associated with this faction. Example: `["spore:biomass_tower", "spore:lab"]`. |
| `structure_base_score` | Double | No | Base threat score injected by structures (default `500.0`). Amortized across bounding box chunks automatically. |
| `entity_whitelist` | Object | Yes | Whitelisted mob pool. Supports entity tags (`tags`) and direct registry names (`entities`). |
| `is_tag` | String | Yes | Forge Entity Tag applied to mobs while occupying this faction's sovereign territory. E.g. `"civil_adventure:is_fungal"`. |
| `not_tag` | String | Yes | Counter-tag applied to mobs outside this territory. E.g. `"civil_adventure:not_fungal"`. |
| `blocks` | Object / Array | Yes | Dangerous blocks generating threat score for this faction, along with their weight values. |
| `dominant_buffs` | Object | No | Territorial buffs (Attribute Modifiers & Mob Effects) granted to whitelisted mobs inside sovereign territory. |

---

### Standard Example 1: Fungal Infestation Faction (`fungal.json`)
```json
{
  "name": "civil_adventure.faction.fungal.name",
  "color": "#D32F2F",
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
> **Attribute Modifier Operation Guide**:
> - `0` (`ADD_VALUE`): Add flat value directly (e.g. $+5.0$ attack damage);
> - `1` (`MULTIPLY_BASE`): Add percentage of base value (e.g. $+20\%$ base health);
> - `2` (`MULTIPLY_TOTAL`): Compound multiplier on final value (Recommended! e.g. $\times 1.25$, highest compatibility across mods).

---

## 3. Independent Entity Attribute Datapack

Beyond the macro-level fallback buffs in `dominant_buffs`, packmakers can define specialized stat multipliers for bosses or elite mobs:  
`data/<namespace>/adventure_entities/<entity_namespace>.<entity_path>.json`

### Example: Fungal Elite Hunter Override (`spore.inf_human.json`)
```json
{
  "_comment": "Elite Fungal Human: Heavy Armor and Knockback Resistance",
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

## 4. Suppressor Fuel Datapack

The Outpost Suppression Core supports fully data-driven custom fuels:  
`data/<namespace>/adventure_suppressor_fuel/*.json`

### Example: High-Energy Catalyst Fuels (`custom_fuels.json`)
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
*Note: $20\text{ ticks} = 1\text{ second}$. $1,200\text{ ticks} = 1\text{ minute}$; $12,000\text{ ticks} = 10\text{ minutes}$; $72,000\text{ ticks} = 1\text{ hour}$.*

---

## 5. Tactical World Map CoreShader Overlay Profiles

Tactical visual presets are registered on the client (data-driven) to style unscouted or hostile territories on Xaero's World Map:

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

## 6. KubeJS & Developer API Integration

`civil_adventure` provides a clean public Java API at `civil_adventure.api.AdventureAPI` alongside Forge Event Bus integration.

### 6.1 Listening to `FactionDominanceChangeEvent`
Fires whenever a macro territory changes rulers (e.g. players cleanse a hive with a suppressor, or spores overtake an illager fortress):

```javascript
// kubejs/server_scripts/geopolitics_events.js
ForgeEvents.onEvent('civil_adventure.faction.event.FactionDominanceChangeEvent', event => {
    let level = event.getLevel();
    let chunkPos = event.getChunkPos();
    let oldDom = event.getOldDominantIndex();
    let newDom = event.getNewDominantIndex();
    let score = event.getDominantScore();

    if (newDom === -1) {
        // Territory completely cleansed and returned to nature!
        level.tell(`§e[SITREP] Hostile ecological zone at (${chunkPos.x * 16}, ${chunkPos.z * 16}) has been completely cleansed!`);
    } else {
        // Sovereignty flipped
        level.tell(`§c[ALERT] Geopolitical clash at (${chunkPos.x * 16}, ${chunkPos.z * 16})! New ruler established with Threat Score: ${score.toFixed(1)}`);
    }
});
```

### 6.2 Querying via `AdventureAPI`
```javascript
// Query macro territory status beneath the player
let api = Java.loadClass('civil_adventure.api.AdventureAPI');
let cluster = api.getClusterAt(player.level, player.blockPosition());

if (cluster != null && cluster.hasDominance()) {
    let faction = cluster.getDominantFaction();
    player.tell(`Currently inside [${faction.id()}] territory. Epicenter at: ${cluster.getEpicenter()}`);
}
```
