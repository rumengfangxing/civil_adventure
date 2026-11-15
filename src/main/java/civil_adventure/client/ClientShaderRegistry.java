package civil_adventure.client;

import civil_adventure.CivilAdventureMod;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

/**
 * 客户端核心着色器注册器 (Core Shader Registry)
 * 注册专属冒险区自适应故障覆层 Shader
 */
@Mod.EventBusSubscriber(modid = CivilAdventureMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientShaderRegistry {

    private static ShaderInstance adventureOverlayShader;

    private ClientShaderRegistry() {}

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    new ResourceLocation(CivilAdventureMod.MOD_ID, "adventure_overlay"),
                    DefaultVertexFormat.POSITION_COLOR_TEX
                ),
                shader -> adventureOverlayShader = shader
            );
            CivilAdventureMod.LOGGER.info("[CivilAdventure] Registered adventure_overlay core shader.");
        } catch (IOException e) {
            CivilAdventureMod.LOGGER.error("[CivilAdventure] Failed to load adventure_overlay shader!", e);
        }
    }

    public static ShaderInstance getAdventureOverlayShader() {
        return adventureOverlayShader;
    }
}
