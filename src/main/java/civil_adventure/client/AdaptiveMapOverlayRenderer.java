package civil_adventure.client;

import civil_adventure.client.profile.OverlayProfile;
import civil_adventure.client.profile.OverlayProfileRegistry;
import civil_adventure.config.AdventureConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Xaero 大地图自适应 CoreShader 战术覆层渲染器。
 * 动态计算未勘明危险冒险区的几何质心，以自适应字号与 CoreShader 故障风动效渲染 "DANGER!" 战术标牌。
 */
public final class AdaptiveMapOverlayRenderer {

    private AdaptiveMapOverlayRenderer() {}

    public static void renderWorldMapOverlays(GuiGraphics guiGraphics, double cameraX, double cameraZ, double scale,
                                            int screenWidth, int screenHeight, float partialTicks) {
        if (AdventureConfig.WORLDMAP_SHADER_ENABLED != null && !AdventureConfig.WORLDMAP_SHADER_ENABLED.get()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ResourceLocation dim = mc.level.dimension().location();

        List<ClientClusterCache.ZoneCluster> clusters = ClientClusterCache.getUnscoutedDangerClusters(dim);
        if (clusters.isEmpty()) return;

        double guiScale = mc.getWindow().getGuiScale();
        if (guiScale <= 0.0) guiScale = 1.0;
        double effectiveScale = scale / guiScale;

        float baseGameTime = (float) ((System.currentTimeMillis() % 10000000L) / 1000.0);
        ShaderInstance shader = ClientShaderRegistry.getAdventureOverlayShader();

        List<float[]> renderedBadgeCenters = new ArrayList<>();

        for (ClientClusterCache.ZoneCluster cluster : clusters) {
            // 策略 2A: 面积门槛过滤 — 少于 4 个区块的边缘碎片保留地图黑底与红框，不生成 DANGER! 标牌，避免满屏文字打架
            if (cluster.chunkKeys != null && cluster.chunkKeys.size() < 4) {
                continue;
            }

            double cX = cluster.getCenterWorldX();
            double cZ = cluster.getCenterWorldZ();

            float screenX = (float) ((cX - cameraX) * effectiveScale + (screenWidth / 2.0));
            float screenY = (float) ((cZ - cameraZ) * effectiveScale + (screenHeight / 2.0));

            // 计算区域在屏幕上的视觉直径（像素）
            float radiusBlocks = Math.max(cluster.width, cluster.height) * 16.0f;
            float radiusPx = radiusBlocks * (float) effectiveScale;

            // 视锥剪裁：若中心远离屏幕可见区域或过于微小，跳过渲染
            if (screenX + radiusPx < 0 || screenX - radiusPx > screenWidth ||
                screenY + radiusPx < 0 || screenY - radiusPx > screenHeight ||
                radiusPx < 14.0f) {
                continue;
            }

            // 策略 2B: 视口防重叠去重 (若两标牌在屏幕上的物理间距小于 65 像素，避免挤在一起)
            boolean tooClose = false;
            for (float[] pos : renderedBadgeCenters) {
                float dx = screenX - pos[0];
                float dy = screenY - pos[1];
                if (dx * dx + dy * dy < 65.0f * 65.0f) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;
            renderedBadgeCenters.add(new float[]{screenX, screenY});

            // 文本大小取决于区域大小 (自适应缩放)
            float textScale = (float) Math.max(0.75, Math.min(3.2, radiusPx / 85.0));

            // 每个地块独立的非同步确定性周期（零内存分配哈希，超低 CPU 开销）
            long seed = ((long) cluster.minCx * 3129871L) ^ ((long) cluster.minCz * 618293L) ^ 0x9E3779B9L;
            float timeOffset = ((seed & 0xFFFF) / 65535.0f) * 12.0f;
            float speedMod = 0.85f + (((seed >>> 16) & 0xFF) / 255.0f) * 0.35f;
            float clusterGameTime = (baseGameTime + timeOffset) * speedMod;

            // 匹配预设 (方案二架构：OverlayProfile)
            String faction = cluster.getDominantFaction() != null ? cluster.getDominantFaction().nameKey() : null;
            OverlayProfile profile = OverlayProfileRegistry.findProfile(dim, faction, cluster.getEstimatedScore());
            if (profile != null && !profile.showOnWorldmap) {
                continue;
            }

            renderGlitchDangerBadge(guiGraphics, shader, mc, screenX, screenY, textScale, clusterGameTime, profile);
        }
    }

    private static void renderGlitchDangerBadge(GuiGraphics guiGraphics, ShaderInstance shader, Minecraft mc,
                                                float screenX, float screenY, float textScale, float gameTime,
                                                OverlayProfile profile) {
        PoseStack poseStack = guiGraphics.pose();

        // 战术故障风动画周期：总周期 ~3.4秒，其中 ~2.9秒常态稳定清晰，~0.5秒电子信号轻微故障扰动
        float cyclePos = gameTime % 3.4f;
        boolean isGlitch = cyclePos > 2.9f;

        String defaultText = "DANGER!";
        if (profile != null && profile.tooltipOverride != null && !profile.tooltipOverride.isEmpty()) {
            defaultText = profile.tooltipOverride;
        }

        String currentText = defaultText;
        if (isGlitch) {
            String[] variants = {"DANGER!", "D4NG3R!", "[DANGER]", "!DANGER!", "DANGER.", "D4NG3R."};
            int idx = (int) (Math.abs(Math.sin(gameTime * 22.0)) * variants.length) % variants.length;
            currentText = variants[idx];
        }

        // 稳定外框尺寸：按最大宽度计算，避免乱码字符置换时外框以 20Hz 剧烈缩放抖动
        int maxTextWidth = Math.max(mc.font.width(defaultText), mc.font.width("!DANGER!"));
        int textHeight = 9;

        float padX = 8.0f * textScale;
        float padY = 4.0f * textScale;
        float badgeW = maxTextWidth * textScale + padX * 2.0f;
        float badgeH = textHeight * textScale + padY * 2.0f;

        float x1 = screenX - badgeW / 2.0f;
        float x2 = screenX + badgeW / 2.0f;
        float y1 = screenY - badgeH / 2.0f;
        float y2 = screenY + badgeH / 2.0f;

        // 1. 若着色器可用，先在战术标牌底层渲染扫描线/故障噪点 Shader Quad (应用 Profile 参数)
        if (shader != null) {
            renderShaderBadgeBackdrop(poseStack, shader, x1, y1, x2, y2, badgeW, badgeH, gameTime, isGlitch, profile);
        } else {
            int bgCol = (profile != null) ? profile.backgroundColor : 0xEE080406;
            guiGraphics.fill((int) x1, (int) y1, (int) x2, (int) y2, bgCol);
            int borderCol = isGlitch ? 0xFFFF1111 : (profile != null ? profile.borderColor : 0xFFCC1122);
            guiGraphics.fill((int) x1, (int) y1, (int) x2, (int) y1 + 1, borderCol);
            guiGraphics.fill((int) x1, (int) y2 - 1, (int) x2, (int) y2, borderCol);
        }

        // 2. 文本色散/色差故障动画 (RGB Split Chromatic Aberration)
        // 紧凑克制的色差偏移行程（1.2 ~ 1.6 像素），确保在任何放大倍数下字形均清晰可辨
        float chromaticShift = isGlitch ? ((float) Math.sin(gameTime * 36.0) * 1.5f) : 0.0f;

        poseStack.pushPose();
        poseStack.translate(screenX, screenY, 0.0);
        poseStack.scale(textScale, textScale, 1.0f);

        int textWidth = mc.font.width(currentText);
        int drawX = -textWidth / 2;
        int drawY = -textHeight / 2 + 1;

        if (isGlitch) {
            // 青色鬼影偏移动画 (Cyan)
            guiGraphics.drawString(mc.font, currentText, (int) (drawX - chromaticShift), drawY, 0xAA00E5FF, false);
            // 玫红鬼影偏移动画 (Magenta)
            guiGraphics.drawString(mc.font, currentText, (int) (drawX + chromaticShift), drawY, 0xAAFF0055, false);
            // 主文本亮白高频微抖
            guiGraphics.drawString(mc.font, currentText, drawX, drawY, 0xFFFFFFFF, false);
        } else {
            // 常态：醒目高亮警告红 + 深红暗影
            int textCol = (profile != null && profile.borderColor != 0) ? profile.borderColor : 0xFFFF2222;
            guiGraphics.drawString(mc.font, currentText, drawX + 1, drawY + 1, 0xFF330005, false);
            guiGraphics.drawString(mc.font, currentText, drawX, drawY, textCol, false);
        }

        poseStack.popPose();
    }

    private static void renderShaderBadgeBackdrop(PoseStack poseStack, ShaderInstance shader,
                                                  float x1, float y1, float x2, float y2,
                                                  float badgeW, float badgeH, float gameTime, boolean isGlitch,
                                                  OverlayProfile profile) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(() -> shader);

        float configOpacity = (AdventureConfig.MAP_OVERLAY_OPACITY != null) ? AdventureConfig.MAP_OVERLAY_OPACITY.get().floatValue() : 1.0f;
        float opacity = (profile != null ? profile.overlayOpacity : 0.95f) * configOpacity;
        float jitterAmp = profile != null ? profile.scanlineJitterAmplitude : 0.08f;
        float scanCount = profile != null ? profile.scanlineCount : 24.0f;
        float scanSpeed = profile != null ? profile.scanlineSpeed : 8.0f;
        float chromOffset = profile != null ? profile.chromaticOffset : 0.02f;
        float pulseSpeed = profile != null ? profile.borderPulseSpeed : 2.0f;

        float bgR = profile != null ? ((profile.backgroundColor >> 16) & 0xFF) / 255.0f : 0.05f;
        float bgG = profile != null ? ((profile.backgroundColor >> 8) & 0xFF) / 255.0f : 0.02f;
        float bgB = profile != null ? (profile.backgroundColor & 0xFF) / 255.0f : 0.03f;
        float bgA = profile != null ? ((profile.backgroundColor >>> 24) & 0xFF) / 255.0f : 0.92f;
        if (bgA <= 0.0f) bgA = 0.92f;

        float borderR = profile != null ? ((profile.borderColor >> 16) & 0xFF) / 255.0f : 1.0f;
        float borderG = profile != null ? ((profile.borderColor >> 8) & 0xFF) / 255.0f : 0.15f;
        float borderB = profile != null ? (profile.borderColor & 0xFF) / 255.0f : 0.15f;
        float borderA = profile != null ? ((profile.borderColor >>> 24) & 0xFF) / 255.0f : 1.0f;
        if (borderA <= 0.0f) borderA = 1.0f;

        setUniformSafe(shader, "GameTime", gameTime);
        setUniformSafe(shader, "OverlayOpacity", opacity);
        setUniformSafe(shader, "Aspect", badgeW / badgeH);
        setUniformSafe(shader, "GlitchIntensity", isGlitch ? Math.max(0.8f, jitterAmp * 8.0f) : 0.0f);
        setUniformIntSafe(shader, "GlitchMode", isGlitch ? 3 : 0);
        setUniform2Safe(shader, "ScreenSize", badgeW, badgeH);

        setUniform4Safe(shader, "BackgroundColor", bgR, bgG, bgB, bgA);
        setUniform4Safe(shader, "BorderColor", borderR, borderG, borderB, borderA);
        setUniformSafe(shader, "BorderThickness", profile != null && profile.borderThickness > 0.0f ? profile.borderThickness : 0.06f);
        // [修复 C-03] 读取 Profile 配置的 BorderStyle 与 FillMode
        int borderStyle = (profile != null && profile.borderEnabled) ? profile.getBorderStyleId() : 0;
        setUniformIntSafe(shader, "BorderStyle", borderStyle);
        setUniformSafe(shader, "BorderPulseSpeed", pulseSpeed);

        int fillMode = profile != null ? profile.getFillModeId() : 0;
        setUniformIntSafe(shader, "FillMode", fillMode);
        setUniformSafe(shader, "VignettePower", profile != null ? profile.vignettePower : 1.0f);
        setUniformSafe(shader, "DecalScale", 0.0f); // 标牌背景严禁采样 Decal 贴图，彻底杜绝 UI 贴图泄漏与紫黑方块
        setUniformIntSafe(shader, "DecalAspectCorrection", 0);

        setUniformSafe(shader, "ScanlineCount", scanCount);
        setUniformSafe(shader, "ScanlineSpeed", scanSpeed);
        setUniformSafe(shader, "ScanlineJitter", jitterAmp);
        setUniformSafe(shader, "ChromaticOffset", chromOffset);
        setUniformSafe(shader, "CheckerSpeed", 0.0f);
        setUniform4Safe(shader, "CheckerColorA", 0.0f, 0.0f, 0.0f, 0.0f);
        setUniform4Safe(shader, "CheckerColorB", 0.0f, 0.0f, 0.0f, 0.0f);

        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);

        buffer.vertex(matrix, x1, y2, 0.0f).color(1.0f, 1.0f, 1.0f, 1.0f).uv(0.0f, 1.0f).endVertex();
        buffer.vertex(matrix, x2, y2, 0.0f).color(1.0f, 1.0f, 1.0f, 1.0f).uv(1.0f, 1.0f).endVertex();
        buffer.vertex(matrix, x2, y1, 0.0f).color(1.0f, 1.0f, 1.0f, 1.0f).uv(1.0f, 0.0f).endVertex();
        buffer.vertex(matrix, x1, y1, 0.0f).color(1.0f, 1.0f, 1.0f, 1.0f).uv(0.0f, 0.0f).endVertex();

        BufferUploader.drawWithShader(buffer.end());
        // [修复 C-04] 绘制完毕后恢复默认 PositionColor 着色器，防止污染后续 UI 渲染
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        RenderSystem.defaultBlendFunc();
    }

    private static void setUniformSafe(ShaderInstance shader, String name, float val) {
        var u = shader.safeGetUniform(name);
        if (u != null) u.set(val);
    }

    private static void setUniformIntSafe(ShaderInstance shader, String name, int val) {
        var u = shader.safeGetUniform(name);
        if (u != null) u.set(val);
    }

    private static void setUniform2Safe(ShaderInstance shader, String name, float v1, float v2) {
        var u = shader.safeGetUniform(name);
        if (u != null) u.set(v1, v2);
    }

    private static void setUniform4Safe(ShaderInstance shader, String name, float v1, float v2, float v3, float v4) {
        var u = shader.safeGetUniform(name);
        if (u != null) u.set(v1, v2, v3, v4);
    }
}
