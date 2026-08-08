# Changelog

## 1.3.0a — 2026-08-09 开发轮次

本轮主题：**冒险区压制方块（Adventure Suppressor）+ GeckoLib 动画集成**。

### ✨ 新增

#### 冒险区压制方块
- 新方块 `civil_adventure:adventure_suppressor`（冒险区压制方块），手持燃料右键充能、空手右键切换启用/停用
- **燃料系统**（`AdventureFuelLoader`，数据驱动）：
  - 绿宝石 → 1200 tick
  - 绿宝石块 → 12000 tick
  - 燃料表由 `adventure_suppressor_fuel/defaults.json` 配置，支持数据包扩展
- **红石控制**：仅信号变化时覆盖启用状态（右键切换优先，避免反复翻转）；放置时已有信号则立即启用
- **计时器**：启用后倒计时，归零自动停用（计时保留，不丢失）
- **分数压制**：启用期间每隔 `SUPPRESS_INTERVAL`(100) tick 对半径 `SUPPRESS_RADIUS`(4) 区块内的冒险区原始分进行压制（削减 1% + 固定 3.0 分）
- **释放回弹**：停用/方块被移除时，立即回弹该方块贡献的所有压制量（按区块键追踪 `localSuppression`）
- 3/4 格高碰撞箱（对应模型形状）；`getOcclusionShape` 返回空以保留底下方块上表面渲染
- 方块被破坏时掉落本体（loot table），移除时自动释放压制

#### GeckoLib 动画
- 集成 GeckoLib 4.8.4（`build.gradle` + `mods.toml` 前置依赖声明）
- 动画状态机（`AnimTarget`）：
  - 停用 → `idle`（待机）
  - 启用未完全压制 → `overload`（超负荷，快速旋转）
  - 启用已完全压制 → `running`（运转，慢速旋转）
  - 停用→启用：`start` + `running` 过渡动画后进入目标状态
  - 启用→停用：`running` + `start` 过渡动画后回到 `idle`
- 仅状态变化时调用 `setAnimation`（防止每 tick 重置导致抽搐）；`getTick` 在 level 为空时返回缓存值（防止动画时间跳回 0 反复重播）
- 物品栏渲染：`SuppressorBlockItem` 实现 `GeoItem` + BEWLR，方块与物品共用同一动画
- 音效事件占位注册（idle/overload/running/start/close，ogg 待美术提供）

#### 配置项（`suppressor` 分组）
- `percent`（每次压制削减百分比，默认 0.01）
- `fixed`（每次压制削减固定分数，默认 3.0）
- `interval_ticks`（压制动作间隔，默认 100）
- `radius_chunks`（压制影响半径，默认 4）

### 🔧 修复

- 动画文件路径修正：GeckoLib `DefaultedBlockGeoModel` 要求 `animations/block/` 子目录
- 动画键名改为短名（`idle`/`overload`/`running`/`start`/`close`），匹配 GeckoLib `thenPlay` 的精确键匹配
- 关键帧格式扁平化：Blockbench 导出的 `{"post":[...],"lerp_mode":"catmullrom"}` 与 GeckoLib 不兼容，已拍平为纯坐标数组（33 个通道）
- 模型 `visible_bounds` 扩大到 32×20×32，修复视野剔除（frustum culling）导致的闪烁
- 移除 `DetectorItemMixin`（探索器兼容逻辑已由 `AdventureZoneScanner`/`ZonePolicy` 注入取代）

### 🐛 已知问题

- 渲染时出现「静止完整模型 + 动画模型」叠加闪烁：已排查可见包围盒、关键帧格式、动画时间缓存、状态机重置，疑似与模型纹理半透明（约 30% 半透明像素）有关，待用不透明纹理验证
