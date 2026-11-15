package civil_adventure.client;

import civil_adventure.block.AdventureSuppressorBlockEntity;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 压制方块 GeckoLib 渲染器。
 */
public class AdventureSuppressorBlockRenderer
    extends GeoBlockRenderer<AdventureSuppressorBlockEntity> {

    public AdventureSuppressorBlockRenderer() {
        super(new AdventureSuppressorModel());
    }

    @Override
    public boolean shouldRenderOffScreen(AdventureSuppressorBlockEntity blockEntity) {
        // 允许方块中心刚出视锥边缘时依然完整渲染旋转光环，杜绝边缘视锥剔除导致的重载闪烁与抽搐
        return true;
    }
}
