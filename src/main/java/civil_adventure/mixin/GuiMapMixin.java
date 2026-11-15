package civil_adventure.mixin;

import civil_adventure.client.AdaptiveMapOverlayRenderer;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 挂载至 Xaero 大地图 GuiMap 视口：
 * 在 renderPreDropdown 阶段（地图底层渲染完毕、菜单/工具提示绘制之前）直接将 CoreShader Quad 投影绘制在地图上。
 */
@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public class GuiMapMixin {

    @Shadow
    private double cameraX;

    @Shadow
    private double cameraZ;

    @Shadow
    private double scale;

    @Inject(method = "renderPreDropdown", at = @At("HEAD"), require = 0)
    private void adventure$onRenderPreDropdown(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (guiGraphics != null) {
            AdaptiveMapOverlayRenderer.renderWorldMapOverlays(
                guiGraphics,
                this.cameraX,
                this.cameraZ,
                this.scale,
                guiGraphics.guiWidth(),
                guiGraphics.guiHeight(),
                partialTicks
            );
        }
    }
}
