# 前置模组机制与运行环境特性说明 (Environment Quirks & Specs)

本文档面向代码审查者与外部协作者，记录 `civil_adventure` 2.0 在与各前置模组交互时的关键特性、怪癖及架构约束。

---

### 1. 文礼 (Civillis 2.0.1+) 前置交互约束

1. **`ZoneTransitionHud` 文本封装怪癖**：
   * Civillis 的 `ZoneTransitionHud.onPayload(ZoneTransitionPayload)` 在内部直接使用 `Component.literal(payload.labelOverride())` 封装显示文本，而**不是** `Component.translatable`。
   * **影响与对策**：如果服务端直接下发数据包中的翻译键（如 `civil_adventure.faction.fungal.name`），HUD 上会直接显示未经翻译的原始键名。因此客户端在接收到 `AdventureTransitionPayload` 后，必须在客户端线程先调用 `net.minecraft.client.resources.language.I18n.get(rawLabel)` 完成本地化，再转交 Civillis HUD 渲染。
2. **`CivilMapTintPalette` 服务端地图色阶**：
   * 文礼通过 `CivilMapTintPalette.evaluateTintForChunk` 评估世界地图的区域染色（用于原版地图与文礼内置雷达）。
   * `CivilMapTintPaletteMixin` 拦截该方法，当显性统治派系为非文明时直接返回 `band 6`（冒险区色阶）。
3. **结构基础分接管**：
   * 文礼原版会对生成的遗迹结构赋予默认 500.0 分的结构基础分。`civil_adventure` 2.0 接管了该机制，由 `CivillisStructureTracker` 动态识别结构归属派系、按覆盖区块数分摊基础分，并在压制清零时记录到 `CleansedStructureStorage`（`SavedData`）。

---

### 2. Xaero's Minimap & World Map 客户端雷达约束

1. **256 元素定长数组要求**：
   * Xaero 的 `AbstractHighlighter.getColors(ResourceKey<Level> dimKey, int cx, int cz)` 要求返回一个正好为 256（16x16 方块像素）的 `int[]` 颜色数组。如果返回更小的数组或 null（当需要高亮时），会直接导致 Xaero 渲染管线抛出 `ArrayIndexOutOfBoundsException`。
   * 对策：`ClientClusterCache.getMinimapColors` 统一初始化 `new int[256]` 并使用 `Arrays.fill(store, xaeroColor)` 填充对应派系的 ARGB 颜色。
2. **纯客户端渲染与零服务端依赖**：
   * Xaero 的 `CivilMinimapMixin` 与 `CivilWorldMapMixin` 纯粹运行在客户端渲染线程。在多人联机（Dedicated Server）下，服务端对象不存在于客户端。因此地图高亮必须 100% 依赖 S2C 数据包 `AdventureClusterSyncPayload` 驱动的客户端缓存 `ClientClusterCache`。

---

### 3. GeckoLib 4 (4.8.0+) 动画与模型约束

1. **动画状态机连续性（时间戳与小数 Tick）**：
   * GeckoLib 的 `BlockEntity` 默认动画时间轴使用 `RenderUtils.getCurrentTick() = Blaze3D.getTime() * 20d`（连续递增的浮点帧）。
   * 严禁覆写为整数的 `level.getGameTime()`，否则同 tick 内多次渲染会被判定为 `isReRender` 从而跳过时间轴步进，引发 20Hz 动画跳变抽搐。
2. **动画控制器谓词早退防抖**：
   * 压制核心在 `animControllerPredicate` 中必须判断 `if (target == animTarget) { ... return PlayState.CONTINUE; }`。如果不做状态比较而每帧重复调用 `setAnimation`，动画时间轴会被反复重置到 0，导致动画卡死在第 1 帧。

---

### 4. Forge 47.1+ 物理端（Dist）与类加载隔离准则

1. **网络包注册（SimpleChannel）**：
   * 网络消息处理 lambda 中严禁直接静态引用客户端专属类（如 `ClientPayloadHandler`）。
   * 必须使用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPayloadHandler::handle)` 保证独立服务端（Dedicated Server）类加载器在校验方法签名时不触发客户端类的静态初始化，防止 `NoClassDefFoundError`。
2. **Mixin 目标环境隔离**：
   * 客户端专属 Mixin（如 Xaero 地图相关）必须严格放置在 `civil_adventure.mixins.json` 的 `"client"` 数组中，通用混入放在 `"mixins"` 数组中，严禁在服务端加载客户端渲染器 Mixin。
