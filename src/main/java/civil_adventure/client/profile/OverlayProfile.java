package civil_adventure.client.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 战术地图覆层多主题预设配置模型 (Overlay Theme Profile)
 * 支持高度自定义与全套可选参数：多阶段时序、CoreShader 底层物理参数、外边框样式、纯色遮罩与派系规则绑定。
 */
public class OverlayProfile {

    public static class PhaseConfig {
        public final String name;
        public final float durationSeconds;
        public final ResourceLocation texture;
        public final String glitchMode;
        public final float glitchIntensity;

        public PhaseConfig(String name, float durationSeconds, ResourceLocation texture, String glitchMode, float glitchIntensity) {
            this.name = name != null ? name : "phase";
            this.durationSeconds = Math.max(0.05f, durationSeconds);
            this.texture = texture;
            this.glitchMode = glitchMode != null ? glitchMode.toUpperCase() : "NONE";
            this.glitchIntensity = glitchIntensity;
        }

        public int getGlitchModeId() {
            return switch (this.glitchMode) {
                case "SCANLINE_JITTER" -> 1;
                case "CHROMATIC" -> 2;
                case "FULL_GLITCH" -> 3;
                case "STATIC_NOISE" -> 4;
                case "BIO_WAVE" -> 5;
                default -> 0; // NONE
            };
        }
    }

    public final String id;
    public final int priority;
    public final List<String> dimensions;
    public final List<String> factions;
    public final double minScore;
    public final double maxScore;

    // 遮罩底色与可视性 (Mask & Opacity)
    public final float overlayOpacity;
    public final int backgroundColor; // ARGB
    public final String fillMode; // SOLID, NOISE, VIGNETTE
    public final float vignettePower;

    // 边框外观 (Border & Outline)
    public final boolean borderEnabled;
    public final int borderColor; // ARGB
    public final float borderThickness;
    public final String borderStyle; // SOLID, DASHED, PULSE, CORNER
    public final float borderPulseSpeed;

    // 中心徽标与贴图适配 (Decal & Aspect)
    public final boolean centerDecalEnabled;
    public final float decalScale;
    public final boolean decalAspectCorrection;
    public final String decalBlendMode; // NORMAL, ADDITIVE

    // 多阶段时序序列 (Phases)
    public final List<PhaseConfig> phases;
    public final float totalDurationSeconds;

    // 着色器底层微调参数 (Shader Uniforms)
    public final float scanlineCount;
    public final float scanlineSpeed;
    public final float scanlineJitterAmplitude;
    public final float chromaticOffset;
    public final float checkerboardFlashSpeed;
    public final int checkerboardColorA; // ARGB
    public final int checkerboardColorB; // ARGB

    // 地图与悬停行为 (Map Targets & Interaction)
    public final boolean showOnWorldmap;
    public final boolean showOnMinimap;
    public final String tooltipOverride;
    public final boolean tooltipObfuscated;

    public OverlayProfile(String id, int priority, List<String> dimensions, List<String> factions,
                          double minScore, double maxScore, float overlayOpacity, int backgroundColor,
                          String fillMode, float vignettePower, boolean borderEnabled, int borderColor,
                          float borderThickness, String borderStyle, float borderPulseSpeed,
                          boolean centerDecalEnabled, float decalScale, boolean decalAspectCorrection,
                          String decalBlendMode, List<PhaseConfig> phases, float scanlineCount,
                          float scanlineSpeed, float scanlineJitterAmplitude, float chromaticOffset,
                          float checkerboardFlashSpeed, int checkerboardColorA, int checkerboardColorB,
                          boolean showOnWorldmap, boolean showOnMinimap, String tooltipOverride,
                          boolean tooltipObfuscated) {
        this.id = id;
        this.priority = priority;
        this.dimensions = dimensions != null ? List.copyOf(dimensions) : List.of();
        this.factions = factions != null ? List.copyOf(factions) : List.of();
        this.minScore = minScore;
        this.maxScore = maxScore;
        this.overlayOpacity = Math.max(0.0f, Math.min(1.0f, overlayOpacity));
        this.backgroundColor = backgroundColor;
        this.fillMode = fillMode != null ? fillMode.toUpperCase() : "SOLID";
        this.vignettePower = vignettePower;
        this.borderEnabled = borderEnabled;
        this.borderColor = borderColor;
        this.borderThickness = borderThickness;
        this.borderStyle = borderStyle != null ? borderStyle.toUpperCase() : "SOLID";
        this.borderPulseSpeed = borderPulseSpeed;
        this.centerDecalEnabled = centerDecalEnabled;
        this.decalScale = Math.max(0.05f, Math.min(1.0f, decalScale));
        this.decalAspectCorrection = decalAspectCorrection;
        this.decalBlendMode = decalBlendMode != null ? decalBlendMode.toUpperCase() : "NORMAL";
        this.phases = phases != null ? List.copyOf(phases) : List.of();

        float dur = 0.0f;
        for (PhaseConfig p : this.phases) {
            dur += p.durationSeconds;
        }
        this.totalDurationSeconds = Math.max(0.1f, dur);

        this.scanlineCount = scanlineCount;
        this.scanlineSpeed = scanlineSpeed;
        this.scanlineJitterAmplitude = scanlineJitterAmplitude;
        this.chromaticOffset = chromaticOffset;
        this.checkerboardFlashSpeed = checkerboardFlashSpeed;
        this.checkerboardColorA = checkerboardColorA;
        this.checkerboardColorB = checkerboardColorB;
        this.showOnWorldmap = showOnWorldmap;
        this.showOnMinimap = showOnMinimap;
        this.tooltipOverride = tooltipOverride;
        this.tooltipObfuscated = tooltipObfuscated;
    }

    public int getBorderStyleId() {
        return switch (borderStyle) {
            case "DASHED" -> 1;
            case "PULSE" -> 2;
            case "CORNER" -> 3;
            default -> 0; // SOLID
        };
    }

    public int getFillModeId() {
        return switch (fillMode) {
            case "NOISE" -> 1;
            case "VIGNETTE" -> 2;
            default -> 0; // SOLID
        };
    }

    public static float[] argbToFloat4(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        return new float[]{r, g, b, a};
    }

    public static int parseColor(String hex, int defaultColor) {
        if (hex == null || hex.isBlank()) return defaultColor;
        try {
            String clean = hex.trim();
            if (clean.startsWith("#")) clean = clean.substring(1);
            if (clean.length() == 6) {
                clean = "FF" + clean;
            }
            return (int) (Long.parseLong(clean, 16) & 0xFFFFFFFFL);
        } catch (Exception ignored) {
            return defaultColor;
        }
    }

    public PhaseConfig getPhaseAtTime(float timeSeconds) {
        if (phases.isEmpty()) return null;
        if (phases.size() == 1) return phases.get(0);

        float cycleTime = timeSeconds % totalDurationSeconds;
        if (cycleTime < 0.0f) cycleTime += totalDurationSeconds;

        float accumulated = 0.0f;
        for (PhaseConfig p : phases) {
            accumulated += p.durationSeconds;
            if (cycleTime <= accumulated) {
                return p;
            }
        }
        return phases.get(phases.size() - 1);
    }

    public static OverlayProfile fromJson(JsonObject json, String fallbackId) {
        String id = json.has("id") ? json.get("id").getAsString() : fallbackId;
        int priority = json.has("priority") ? json.get("priority").getAsInt() : 0;

        List<String> dimensions = new ArrayList<>();
        if (json.has("dimensions") && json.get("dimensions").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("dimensions")) {
                dimensions.add(el.getAsString());
            }
        }

        List<String> factions = new ArrayList<>();
        if (json.has("factions") && json.get("factions").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("factions")) {
                factions.add(el.getAsString());
            }
        }

        double minScore = json.has("min_score") ? json.get("min_score").getAsDouble() : 0.0;
        double maxScore = json.has("max_score") ? json.get("max_score").getAsDouble() : 9999.0;

        float opacity = json.has("overlay_opacity") ? json.get("overlay_opacity").getAsFloat() : 1.0f;
        int bgColor = parseColor(json.has("background_color") ? json.get("background_color").getAsString() : null, 0xFF0F0A0C);
        String fillMode = json.has("fill_mode") ? json.get("fill_mode").getAsString() : "SOLID";
        float vignettePower = json.has("vignette_power") ? json.get("vignette_power").getAsFloat() : 1.5f;

        boolean borderEnabled = !json.has("border_enabled") || json.get("border_enabled").getAsBoolean();
        int borderColor = parseColor(json.has("border_color") ? json.get("border_color").getAsString() : null, 0xFFE61919);
        float borderThickness = json.has("border_thickness") ? json.get("border_thickness").getAsFloat() : 0.02f;
        String borderStyle = json.has("border_style") ? json.get("border_style").getAsString() : "SOLID";
        float borderPulseSpeed = json.has("border_pulse_speed") ? json.get("border_pulse_speed").getAsFloat() : 2.0f;

        boolean centerDecal = !json.has("center_decal_enabled") || json.get("center_decal_enabled").getAsBoolean();
        float decalScale = json.has("decal_scale") ? json.get("decal_scale").getAsFloat() : 0.75f;
        boolean aspectCorrect = !json.has("decal_aspect_correction") || json.get("decal_aspect_correction").getAsBoolean();
        String decalBlend = json.has("decal_blend_mode") ? json.get("decal_blend_mode").getAsString() : "NORMAL";

        List<PhaseConfig> phases = new ArrayList<>();
        if (json.has("phases") && json.get("phases").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("phases")) {
                if (el.isJsonObject()) {
                    JsonObject pObj = el.getAsJsonObject();
                    String pName = pObj.has("name") ? pObj.get("name").getAsString() : "phase";
                    float pDur = pObj.has("duration_seconds") ? pObj.get("duration_seconds").getAsFloat() : 3.0f;
                    String pTex = pObj.has("texture") ? pObj.get("texture").getAsString() : "civil_adventure:textures/map/adventure_phase1.png";
                    String pGlitch = pObj.has("glitch_mode") ? pObj.get("glitch_mode").getAsString() : "NONE";
                    float pIntens = pObj.has("glitch_intensity") ? pObj.get("glitch_intensity").getAsFloat() : 1.0f;
                    phases.add(new PhaseConfig(pName, pDur, new ResourceLocation(pTex), pGlitch, pIntens));
                }
            }
        }

        float scanlineCount = json.has("scanline_count") ? json.get("scanline_count").getAsFloat() : 40.0f;
        float scanlineSpeed = json.has("scanline_speed") ? json.get("scanline_speed").getAsFloat() : 12.0f;
        float scanlineJitter = json.has("scanline_jitter_amplitude") ? json.get("scanline_jitter_amplitude").getAsFloat() : 0.08f;
        float chromaticOffset = json.has("chromatic_offset") ? json.get("chromatic_offset").getAsFloat() : 0.015f;
        float checkerFlash = json.has("checkerboard_flash_speed") ? json.get("checkerboard_flash_speed").getAsFloat() : 8.0f;
        int checkColA = parseColor(json.has("checkerboard_color_a") ? json.get("checkerboard_color_a").getAsString() : null, 0xFFFF00FF);
        int checkColB = parseColor(json.has("checkerboard_color_b") ? json.get("checkerboard_color_b").getAsString() : null, 0xFF000000);

        boolean showWorld = !json.has("show_on_worldmap") || json.get("show_on_worldmap").getAsBoolean();
        boolean showMini = !json.has("show_on_minimap") || json.get("show_on_minimap").getAsBoolean();
        String tooltip = json.has("tooltip_override") && !json.get("tooltip_override").isJsonNull() ? json.get("tooltip_override").getAsString() : null;
        boolean toolObf = json.has("tooltip_obfuscated") && json.get("tooltip_obfuscated").getAsBoolean();

        return new OverlayProfile(id, priority, dimensions, factions, minScore, maxScore,
                opacity, bgColor, fillMode, vignettePower, borderEnabled, borderColor,
                borderThickness, borderStyle, borderPulseSpeed, centerDecal, decalScale,
                aspectCorrect, decalBlend, phases, scanlineCount, scanlineSpeed, scanlineJitter,
                chromaticOffset, checkerFlash, checkColA, checkColB, showWorld, showMini,
                tooltip, toolObf);
    }
}
