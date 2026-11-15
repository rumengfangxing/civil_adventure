#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float GameTime;
uniform float OverlayOpacity;
uniform float Aspect;
uniform float GlitchIntensity;
uniform int GlitchMode;
uniform vec2 ScreenSize;

uniform vec4 BackgroundColor;
uniform vec4 BorderColor;
uniform float BorderThickness;
uniform int BorderStyle;
uniform float BorderPulseSpeed;

uniform int FillMode;
uniform float VignettePower;
uniform float DecalScale;
uniform int DecalAspectCorrection;

uniform float ScanlineCount;
uniform float ScanlineSpeed;
uniform float ScanlineJitter;
uniform float ChromaticOffset;
uniform float CheckerSpeed;
uniform vec4 CheckerColorA;
uniform vec4 CheckerColorB;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    // 1. Screen-space pixel distances to quad perimeter
    float pixelDistX = min(texCoord0.x, 1.0 - texCoord0.x) * ScreenSize.x;
    float pixelDistY = min(texCoord0.y, 1.0 - texCoord0.y) * ScreenSize.y;
    float borderDist = min(pixelDistX, pixelDistY);
    float borderThickPx = max(2.0, min(ScreenSize.x, ScreenSize.y) * BorderThickness);

    // 2. Base Background (消去可视性: Solid opaque by default)
    vec4 baseColor = BackgroundColor;
    vec2 scanUV = texCoord0;

    // 水平扫描线撕裂 (CRT Scanline Slice Tear): 仅在故障强度较高时微幅错位
    if (GlitchIntensity > 0.3) {
        float slice = floor(scanUV.y * max(10.0, ScanlineCount));
        float sliceNoise = fract(sin(slice * 12.9898 + floor(GameTime * 24.0) * 78.233) * 43758.5453);
        if (sliceNoise > 0.65) {
            float jitter = sin(slice * 17.0 + GameTime * ScanlineSpeed) * ScanlineJitter * GlitchIntensity;
            scanUV.x = clamp(scanUV.x + jitter, 0.0, 1.0);
        }
    }

    if (FillMode == 1) {
        // NOISE: Dynamic TV static grain
        float n = hash12(scanUV * 200.0 + vec2(GameTime * 15.0));
        baseColor.rgb = mix(baseColor.rgb, vec3(n), 0.18);
    } else if (FillMode == 2) {
        // VIGNETTE: Radial darkness falloff
        float d = distance(scanUV, vec2(0.5)) * 1.414;
        float vig = clamp(pow(d, max(0.1, VignettePower)), 0.0, 1.0);
        baseColor.rgb = mix(baseColor.rgb, baseColor.rgb * 0.25, vig);
    } else {
        // SOLID: Subtle CRT scanlines
        float scanline = sin(scanUV.y * ScreenSize.y * 1.5 + GameTime * ScanlineSpeed) * 0.03;
        baseColor.rgb -= vec3(scanline);
        // 微弱模拟信号底噪 (仅故障时轻微扰动，不产生大色块)
        if (GlitchIntensity > 0.3) {
            float n = hash12(scanUV * 90.0 + vec2(GameTime * 28.0));
            baseColor.rgb += (n - 0.5) * 0.05 * GlitchIntensity;
        }
    }

    // 3. Aspect-Correct UV Mapping for Center Decal (若 DecalScale <= 0.001 则完全关闭 Decal 逻辑，严防 UI 图标泄漏)
    vec4 contentColor = baseColor;
    if (DecalScale > 0.001) {
        float dScale = max(0.05, DecalScale);
        vec2 centeredUV = (texCoord0 - 0.5) / dScale;
        if (DecalAspectCorrection == 1) {
            if (Aspect >= 1.0) {
                centeredUV.x *= Aspect;
            } else {
                centeredUV.y /= Aspect;
            }
        }
        vec2 decalUV = centeredUV + 0.5;

        bool inDecalArea = (decalUV.x >= 0.0 && decalUV.x <= 1.0 && decalUV.y >= 0.0 && decalUV.y <= 1.0);

        if (inDecalArea) {
            vec4 samp = vec4(0.0);

            if (GlitchMode == 1) {
                // ── Mode 1: SCANLINE_JITTER ──
                float slice = floor(decalUV.y * ScanlineCount);
                float sliceSeed = slice * 12.9898 + floor(GameTime * 20.0) * 78.233;
                float sliceNoise = fract(sin(sliceSeed) * 43758.5453);
                float shift = 0.0;
                if (sliceNoise > 0.60) {
                    shift = (sin(slice * 15.0 + GameTime * ScanlineSpeed) * ScanlineJitter) * GlitchIntensity;
                }
                vec2 glitchedUV = vec2(clamp(decalUV.x + shift, 0.0, 1.0), decalUV.y);
                samp = texture(Sampler0, glitchedUV);

            } else if (GlitchMode == 2) {
                // ── Mode 2: CHROMATIC ABERRATION ──
                float cOffset = ChromaticOffset * GlitchIntensity;
                float r = texture(Sampler0, clamp(decalUV + vec2(cOffset, 0.0), 0.0, 1.0)).r;
                float g = texture(Sampler0, decalUV).g;
                float b = texture(Sampler0, clamp(decalUV - vec2(cOffset, 0.0), 0.0, 1.0)).b;
                samp = vec4(r, g, b, 1.0);

            } else if (GlitchMode == 3) {
                // ── Mode 3: FULL_GLITCH (高科技数码色彩反转与扫描线撕裂，剔除恶劣紫黑棋盘格) ──
                float slice = floor(decalUV.y * ScanlineCount);
                float sliceSeed = slice * 12.9898 + floor(GameTime * 20.0) * 78.233;
                float sliceNoise = fract(sin(sliceSeed) * 43758.5453);
                float shift = 0.0;
                if (sliceNoise > 0.60) {
                    shift = (sin(slice * 15.0 + GameTime * ScanlineSpeed) * ScanlineJitter) * GlitchIntensity;
                }
                vec2 glitchedUV = vec2(clamp(decalUV.x + shift, 0.0, 1.0), decalUV.y);

                float cOffset = ChromaticOffset * GlitchIntensity;
                float r = texture(Sampler0, clamp(glitchedUV + vec2(cOffset, 0.0), 0.0, 1.0)).r;
                float g = texture(Sampler0, glitchedUV).g;
                float b = texture(Sampler0, clamp(glitchedUV - vec2(cOffset, 0.0), 0.0, 1.0)).b;
                samp = vec4(r, g, b, 1.0);

                // 微幅数码反相噪波，取代突兀的缺失纹理方块
                float patchTime = floor(GameTime * 15.0);
                float patchSeed = dot(floor(decalUV * 10.0), vec2(27.1, 91.3)) + patchTime;
                float patchNoise = fract(sin(patchSeed) * 43758.5453);
                if (patchNoise > 0.90) {
                    samp.rgb = mix(samp.rgb, vec3(1.0) - samp.rgb, 0.4);
                }

            } else if (GlitchMode == 4) {
                // ── Mode 4: STATIC_NOISE (TV snow) ──
                vec4 rawSamp = texture(Sampler0, decalUV);
                float noiseVal = hash12(decalUV * 150.0 + vec2(GameTime * 25.0));
                samp = mix(rawSamp, vec4(vec3(noiseVal), 1.0), 0.45 * GlitchIntensity);

            } else if (GlitchMode == 5) {
                // ── Mode 5: BIO_WAVE (Biological organism pulsing) ──
                float waveX = sin(decalUV.y * 16.0 + GameTime * 4.0) * 0.025 * GlitchIntensity;
                float waveY = cos(decalUV.x * 16.0 + GameTime * 3.5) * 0.025 * GlitchIntensity;
                samp = texture(Sampler0, clamp(decalUV + vec2(waveX, waveY), 0.0, 1.0));
                // Slight bio-toxic tint
                samp.rgb = mix(samp.rgb, vec3(samp.r * 0.6, samp.g * 1.3, samp.b * 0.5), 0.35);

            } else {
                // ── Mode 0: NONE (Clean texture display) ──
                samp = texture(Sampler0, decalUV);
            }

            // Blend decal over base background
            if (samp.a > 0.01 && (samp.r > 0.12 || samp.g > 0.12 || samp.b > 0.12)) {
                contentColor = samp;
            }
        }
    }

    // 4. Perimeter Border Processing
    if (borderDist < borderThickPx) {
        if (BorderStyle == 1) {
            // DASHED (Warning tape stripes)
            float perim = (pixelDistX < borderThickPx) ? (texCoord0.y * ScreenSize.y) : (texCoord0.x * ScreenSize.x);
            float dashShift = (GlitchIntensity > 0.3) ? floor(GameTime * 25.0) * 3.0 : 0.0;
            if (mod(floor((perim + dashShift) / 8.0), 2.0) == 0.0) {
                if (GlitchIntensity > 0.5 && mod(floor(GameTime * 20.0), 3.0) == 0.0) {
                    contentColor = vec4(0.0, 0.9, 1.0, BorderColor.a);
                } else {
                    contentColor = BorderColor;
                }
            }
        } else if (BorderStyle == 2) {
            // PULSE (Breathing glow)
            float pulse = 0.55 + 0.45 * sin(GameTime * BorderPulseSpeed * 3.14159);
            contentColor = vec4(BorderColor.rgb * pulse, BorderColor.a);
        } else if (BorderStyle == 3) {
            // CORNER (Aiming brackets only)
            if (pixelDistX < 14.0 || pixelDistY < 14.0) {
                contentColor = BorderColor;
            }
        } else {
            // SOLID
            if (GlitchIntensity > 0.4) {
                float bShift = sin(GameTime * 35.0);
                contentColor = (bShift > 0.0) ? BorderColor : vec4(0.0, 0.9, 1.0, BorderColor.a);
            } else {
                contentColor = BorderColor;
            }
        }
    } else if (borderDist < borderThickPx + 2.0 && (pixelDistX < 14.0 || pixelDistY < 14.0)) {
        // Corner bracket glow accent
        contentColor = mix(BorderColor, vec4(1.0, 0.9, 0.9, 1.0), 0.35);
    }

    // 5. Apply Configurable Opacity (消去可视性: default 1.0 = 100% solid blackout)
    contentColor.a *= OverlayOpacity;

    fragColor = contentColor * ColorModulator;
}
