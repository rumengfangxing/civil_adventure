package civil_adventure.client;

import civil_adventure.CivilAdventureMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import civil_adventure.client.profile.OverlayProfileRegistry;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端注册：方块实体渲染器及预设初始化。
 */
@Mod.EventBusSubscriber(modid = CivilAdventureMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            OverlayProfileRegistry.init();
        });
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
            CivilAdventureMod.SUPPRESSOR_BLOCK_ENTITY.get(),
            ctx -> new AdventureSuppressorBlockRenderer());
    }
}
