# Changelog

## 1.4.0 — 2026-11-15 架构重构与压制特化终审正式版

本轮主题：**全域宏观领地聚类 + 战争迷雾与 CoreShader 战术雷达 + 突击前哨波前扩散 + 形态学抗裂隙闭运算**。

### ✨ 新增核心机制

#### 1. 突击前哨站与同心圆波前扩散（Concentric Wavefront）
- **突击前哨战术定位**：中心区块（$dist = 0$）获得 **$3.0\times$ 压制聚焦倍率**（随距离向外平滑衰减），深入敌后插下即可迅速建立安全立足点。
- **波前扩张推进**：采用欧氏距离同心圆截断（$dist \le R_{wave}$），按 1B 阵地战节奏（每 20 秒外扩 1 个区块），历时 70~90 秒平滑推开展开至最大 4 区块安全庇护所。
- **全域肃清判定对齐**：波前达最大半径且全域无威胁时，才切换为 RUNNING 动画与比较器满格 15 信号。

#### 2. 空间形态学抗裂隙闭运算（Anti-Fissure Gap Infill）
- **根除平行结界墙对峙**：对自然低分河道/空地进行对向夹击或 8-邻域同派系环绕桥接，将其无缝桥接为一个宏观领地，消灭 1 区块宽峡谷裂隙与双重平行结界墙。
- **安全泡排他豁免**：活跃压制核心的安全空腔享有排他保护，严禁被桥接回填。

#### 3. 优雅倒灌回弹（Graceful Inward Rebound / DecayWave）
- **渐进倒灌**：断电、熄火、耗尽燃料或被挖掘拆除时，由服务端接管 `DecayWave`，每 2 秒自外向内平滑收缩 1 圈（历时 8~10 秒）。
- **无缝接管与账目 100% 守恒**：倒灌期重新开机时无缝中断倒灌，完整接管剩余压制量与波前半径，账目绝不泄漏。

#### 4. 战争迷雾与 Xaero 战术渲染联动
- **三层式战争迷雾**：未探明纯黑遮罩 + 动态外边框 + 已探明半透明战术标牌。
- **CoreShader 故障风动效**：自适应着色器驱动动态文字扰动、扫光与故障风效果。
- **宏观连通域聚类**：8-连通成王败寇裁决，众数投票防抖与领地统治更替事件。

### 🔧 性能与底层边界加固
- 底层 4 参数 `setBlock` 拦截与 `ThreadLocal<ArrayDeque>` 容器零分配复用。
- 纯荒野空 Region 短路优化，杜绝无意义渲染轮询。
- 完善区块卸载 (`onChunkUnloaded`) 与纯结构区独立压制。

---

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
