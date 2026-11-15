# 交接笔记:Biomancy(血肉重铸)Geo 方块模式研究结论

> 来源:另一会话(1ee4aff5)研究 Biomancy 源码(分支 mc1.20.1/prod),为解决压制方块
> "静止完整模型 + 动画模型叠加闪烁"问题。用户已确认路线:**"那我们就和他们一样"**
> (即照 Biomancy 的模式做)。
>
> Biomancy 源码已下载到本地:`C:\Users\20855\AppData\Local\Temp\biomancy-src\`
> 关键文件:PrimordialCradleRenderer.java / PrimordialCradleBlockEntity.java /
> PrimordialCradleModel.java / CustomGeoBlockRenderer.java / ClientSetupHandler.java

## 核心结论:Biomancy 方块如何避免"静止 + 动画叠加"

Biomancy 的 PrimordialCradle 是 **geo renderer 全权接管渲染**:

1. 方块(`RenderShape.ENTITYBLOCK_ANIMATED`)没有任何静态模型参与渲染——
   blockstate 指向空模型或仅占位,所有视觉部件都在 geo 模型里。
2. Renderer(`PrimordialCradleRenderer extends CustomGeoBlockRenderer extends GeoBlockRenderer`)
   在 `preRender` 中按状态用 **`setHidden` 显隐骨骼**:
   ```java
   AnimationProcessor<?> processor = getGeoModel().getAnimationProcessor();
   CoreGeoBone boneFillLevel = processor.getBone("_fill_level");
   CoreGeoBone boneToppings = processor.getBone("_toppings");
   boneFillLevel.setHidden(true);
   boneToppings.setHidden(true);
   float biomassPct = animatable.getBiomassPct();
   if (biomassPct > 0) { boneFillLevel.setHidden(false); ... }
   ```
   → 一个模型、一套骨骼,静态部分和动画部分互斥显示,天然不叠加。
3. 模型类 `DefaultedBlockGeoModel` 自动推导路径:
   `geo/block/<id>.geo.json`、`animations/block/<id>.animation.json`、`textures/block/<id>.png`
4. 动画:`registerControllers` 建 `AnimationController`,空闲回放靠
   `if (controller.getAnimationState() == STOPPED) setAnimation(idle)`;动画 key 精确匹配
   `thenPlay("cradle.idle")` 对应 animation.json 里的 key。
5. 客户端绑定:`EntityRenderersEvent.RegisterRenderers` → `registerBlockEntityRenderer`。

## 对压制方块"叠加闪烁"的排查方向(按可能性排序)

1. **blockstate / models 残留静态模型**:确认
   `assets/civil_adventure/blockstates/adventure_suppressor.json` 与
   `models/block/adventure_suppressor.json` 是否存在、是否还被渲染。
   若存在,`RenderShape.ENTITYBLOCK_ANIMATED` 下 GeckoLib 已接管,但物品栏模型
   (`models/item/adventure_suppressor.json` = builtin/entity)与方块渲染是两套——
   叠加可能发生在**放置的方块**:动画模型里若含"完整静止模型"骨骼 + 动画骨骼同时显示。
2. **geo 模型本身有静止部分**:检查 geo.json 是否有未参与动画的骨骼(如外壳),
   如果 animation.json 没有动画它的骨骼,它就是"静止的完整模型",与动画骨骼叠加 →
   应当像 Biomancy 一样,把外壳骨骼在 preRender 里按状态 setHidden。
3. **半透明纹理(30%)**:半透明 + 深度测试问题会放大闪烁感,建议先换不透明纹理验证。
4. **物品栏 BEWLR 与方块渲染叠加**:分别在物品栏和放置状态单独验证,确定叠加发生在哪边。

## 已确认事实(避免重复排查)

- KubeJS 2001.6.5 jar **没有** BlockEntityRendererRegistry / BE 渲染器 API →
  此路不通,必须 Java(civil_adventure 本身就是 Java mod,不受影响)。
- GeckoLib 4.8.4 已装;`thenPlay` 精确匹配 key;visible_bounds 已扩 32×20×32;
  关键帧已拍平;状态机只变化时 setAnimation —— 这些都已做过,别回退。
- 纹理 PNG 已分析:30% 半透明、32% 全透明。
