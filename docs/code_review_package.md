# Civil Adventure (`1.3.0a`) 代码审查闭环与二次验收重构报告 (v2 Final)

> **版本**：`1.3.0a` (二次验收深度加固版)  
> **基线分支**：`origin/master`  
> **审查报告来源**：`civil_adventure_1_3_0a_v2_acceptance_report.md`  
> **构建状态**：离线增量编译与重混淆通过 (`BUILD SUCCESSFUL in 9s`)，已部署至 `mods/civil_adventure-1.3.0a.jar`  
> **打包产物**：`civil_adventure-1.3.0a-code-review-package.zip` (含全量工程源码、配置与最新构建产物)

---

## 一、 二次验收缺陷修复汇总矩阵

对照 `civil_adventure_1_3_0a_v2_acceptance_report.md` 所列出的 5 项回归/边界隐患，已全量实施闭环重构：

| 编号 | 严重度 | 涉及模块 / 文件 | 根因诊断与修复落地实现 | 闭环状态 |
| :--- | :---: | :--- | :--- | :---: |
| **REG-01** | **Major** | `AdventureBlockChangeMixin.java` | 1. 切换为拦截 4 参数底层 `setBlock(BlockPos, BlockState, int, int)`，完整覆盖 `Level.destroyBlock` 等破坏/挖掘路径。<br>2. 在 `onBlockSet` (`RETURN`) 中先判断 `ServerLevel`，彻底阻断 `ClientLevel` 线程触发 `ThreadLocal` 初始化开销。<br>3. 移除了每次 `isEmpty()` 时调用的 `oldStateStack.remove()`，保留 `ArrayDeque` 容器在主线程长效复用，彻底根除每秒数千次无意义堆分配。 | **已彻底修复** |
| **REG-02** | **Major** | `ClientClusterCache.java` | 1. 在 `getUnscoutedDangerClusters` 的候选区块构建逻辑中加入 `KNOWN_FACTION_MEMORY` 扫描，杜绝服务端区块卸载后出现“有底图黑盒却无 `DANGER!` 战术标牌”的脱节现象。<br>2. 采用 `rawMap.hashCode()` 替代单纯的 `size()` 校验，完整捕获文礼同尺寸下的区块 `band` 状态流转（如 5 荒野变 6 冒险区）。 | **已彻底修复** |
| **REG-03** | **Major** | `ClientClusterCache.java`<br>`CivilWorldMapMixin.java` | 1. 玩家移动探明 `(cx, cz)` 时，同时将 `(cx±1, cz±1)` 所在的所有相邻 Region 统一记入版本递增，确保跨 Region 边界的 4 邻接红框外框即时重绘。<br>2. 引入 `DIMENSION_SYNC_REVISION` 记录服务端同步版本，哈希计算为 `scoutRev * 31 + syncRev`，同尺寸易主或数据更替时 Xaero 即时重绘。<br>3. 彻底删除无人消费且持续累积的 `PENDING_SCOUT_REFRESH` 集合，消除内存泄漏隐患。 | **已彻底修复** |
| **REG-04** | **Warning** | `fungal.json` | 将真菌派系数据包中的代表色 `"color"` 由深红色 `"#D32F2F"` 调整为生化绿 `"#33CC55"`，与 `OverlayProfileRegistry` 中的 `fungal_spore` 战术预设保持视觉统一，消除探明前后色彩跳变。 | **已彻底修复** |
| **REG-05** | **Warning** | `gradle.properties` | 实际保存更新：移除了硬编码个人绝对路径 `C:/Java/jdk-17.0.12...`，启用 `org.gradle.daemon=true`，实现开箱即用的标准多端协同与 CI 构建。 | **已彻底修复** |

---

## 二、 核心重构实现代码

### 1. 方块变更 4 参底层拦截与容器复用 (`AdventureBlockChangeMixin.java`)
```java
@Inject(
    method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
    at = @At("HEAD")
)
private void civilAdventure$captureOldState(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                            CallbackInfoReturnable<Boolean> cir) {
    if ((Object) this instanceof ServerLevel level) {
        ArrayDeque<PosStateSnapshot> stack = civilAdventure$oldStateStack.get();
        if (stack.size() > 64) {
            stack.clear(); // 异常逃逸自愈保护
        }
        stack.push(new PosStateSnapshot(pos.immutable(), level.getBlockState(pos)));
    }
}

@Inject(
    method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
    at = @At("RETURN")
)
private void civilAdventure$onBlockSet(BlockPos pos, BlockState newState, int flags, int recursionLeft,
                                       CallbackInfoReturnable<Boolean> cir) {
    // 1. 先拦截非 ServerLevel，彻底杜绝 ClientLevel 线程触发 ThreadLocal 初始化
    if (!((Object) this instanceof ServerLevel level)) return;

    // 2. 弹出匹配的坐标快照，保留空 ArrayDeque 实例在 ThreadLocal 中复用，严禁每次 remove()
    ArrayDeque<PosStateSnapshot> stack = civilAdventure$oldStateStack.get();
    PosStateSnapshot matched = null;
    while (!stack.isEmpty()) {
        PosStateSnapshot top = stack.pop();
        if (top.pos().equals(pos)) {
            matched = top;
            break;
        }
    }

    if (!cir.getReturnValue() || matched == null) return;
    ...
}
```

### 2. 跨边界 Region 边框联动与网络版本哈希 (`ClientClusterCache.java`)
```java
// 玩家移动探明时，跨 Region 边界标记相邻 Region
for (int nrx = (cx - 1) >> 5; nrx <= (cx + 1) >> 5; nrx++) {
    for (int nrz = (cz - 1) >> 5; nrz <= (cz + 1) >> 5; nrz++) {
        touchedRegions.add((((long) nrx) << 32) | (nrz & 0xFFFFFFFFL));
    }
}

// 复合哈希驱动 Xaero 大地图毫秒级自动重绘
public static int getRegionHighlightHash(ResourceLocation dim, int rx, int rz) {
    if (dim == null) return 0;
    int scoutRev = 0;
    var revMap = REGION_SCOUT_REVISION.get(dim);
    if (revMap != null) {
        long rKey = (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
        scoutRev = revMap.getOrDefault(rKey, 0);
    }
    int syncRev = DIMENSION_SYNC_REVISION.getOrDefault(dim, 0);
    return scoutRev * 31 + syncRev;
}
```

---

## 三、 审查材料包文件清单

材料包已完整打包并更新至：  
**`civil_adventure-1.3.0a-code-review-package.zip`**

**压缩包结构**：
```text
civil_adventure-1.3.0a-code-review-package.zip
├── code_review_package.md                     # 本次代码审查与闭环重构报告 (v2 Final)
├── civil_adventure-1.3.0a.jar                 # 最新通过离线构建与重混淆的 Mod 文件
├── build.gradle                               # 构建脚本
├── gradle.properties                          # 属性配置文件 (标准开箱即用)
├── settings.gradle                            # 工程配置
└── src/                                       # 全量 Java 源码、GLSL 着色器与资源文件
    └── main/
        ├── java/civil_adventure/
        │   ├── CivilAdventureMod.java         # Mod 主入口 (已清理无用扫描器)
        │   ├── client/
        │   │   ├── AdaptiveMapOverlayRenderer.java # 战术 HUD、Shader 状态恢复与 Profile 驱动
        │   │   ├── ClientClusterCache.java         # 维度迷雾、带脏标记聚类与跨界 Region 哈希联动
        │   │   ├── ClientShaderRegistry.java       # CoreShader 着色器管线
        │   │   └── profile/
        │   │       ├── OverlayProfile.java         # 多主题覆层模型
        │   │       └── OverlayProfileRegistry.java # 零堆分配规则匹配器 (支持键名归一化)
        │   ├── cluster/MacroClusterManager.java    # 众数投票领地仲裁与原子更新
        │   ├── mixin/                             # Xaero 与方块变更安全拦截器 (4参底层拦截)
        │   └── override/CleansedStructureStorage.java # 并发安全已肃清结构存储
        └── resources/
            ├── assets/civil_adventure/shaders/core/ # adventure_overlay 核心着色器
            └── data/                                # 派系、方块与实体威胁分数据包 (真菌配色统一)
```
