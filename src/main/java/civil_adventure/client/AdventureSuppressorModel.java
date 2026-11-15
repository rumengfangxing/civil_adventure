package civil_adventure.client;

import civil_adventure.block.AdventureSuppressorBlockEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

/**
 * 压制方块模型。覆写默认 render type：
 * 默认 entityCutoutNoCull 会丢弃 alpha<0.5 的半透明像素，
 * 而本模型纹理含 30% 半透明像素 → 改为 translucent 正确混合（Biomancy 同款做法）。
 */
public class AdventureSuppressorModel
    extends DefaultedBlockGeoModel<AdventureSuppressorBlockEntity> {

    /** 对照实验：true = 使用全不透明纹理副本（验证半透明是否为闪烁放大器），改后需重新构建 */
    public static final boolean USE_OPAQUE_TEXTURE = false;

    public AdventureSuppressorModel() {
        super(new ResourceLocation("civil_adventure", "adventure_suppressor"));
        if (USE_OPAQUE_TEXTURE) {
            withAltTexture(new ResourceLocation("civil_adventure", "adventure_suppressor_opaque"));
        }
    }

    @Override
    public RenderType getRenderType(AdventureSuppressorBlockEntity animatable, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }
}
