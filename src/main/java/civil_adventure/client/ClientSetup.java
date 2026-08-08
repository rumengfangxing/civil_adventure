package civil_adventure.client;

import civil_adventure.CivilAdventureMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端注册：方块实体渲染器。
 */
@Mod.EventBusSubscriber(modid = CivilAdventureMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
            CivilAdventureMod.SUPPRESSOR_BLOCK_ENTITY.get(),
            ctx -> new AdventureSuppressorBlockRenderer());
    }
}
