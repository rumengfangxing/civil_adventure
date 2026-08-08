package civil_adventure.client;

import civil_adventure.block.AdventureSuppressorBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 压制方块 GeckoLib 渲染器。
 */
public class AdventureSuppressorBlockRenderer
    extends GeoBlockRenderer<AdventureSuppressorBlockEntity> {

    public AdventureSuppressorBlockRenderer() {
        super(new DefaultedBlockGeoModel<>(
            new ResourceLocation("civil_adventure", "adventure_suppressor")));
    }
}
