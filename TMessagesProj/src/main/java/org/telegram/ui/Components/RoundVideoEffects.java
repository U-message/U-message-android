package org.telegram.ui.Components;

import android.graphics.Color;
import android.opengl.GLES20;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;

/**
 * U message: color filters and beauty styles for round video messages.
 * The GLSL snippet must be inserted after "uniform samplerExternalOES sTexture;" and
 * exposes applyEffects(color, uv); set its uniforms with {@link Uniforms}.
 */
public class RoundVideoEffects {

    // Preset ids: Original, Beauty, color filters, then the Snap lenses of the lens group.
    // The Snap lenses load later from the network, so they get the ids after the fixed presets and the carousel
    // shows them right after Original (see presetAt).
    public static final int PRESET_ORIGINAL = 0;
    public static final int PRESET_BEAUTY = 1;
    public static final int PRESET_FILTER_FIRST = PRESET_BEAUTY + 1;

    /** Index = filterType uniform. */
    public static final String[] FILTER_NAMES = {
            "Original", "Clarendon", "Juno", "Lark", "Gingham", "Valencia", "Moon", "Nashville", "Vintage"
    };

    /** Lipstick shades: pigment color, how much of its saturation reaches the lips, name. */
    public static final int[] LIPSTICK_COLORS = {
            0xffb4505c, // natural rose
            0xffd8738a, // soft pink
            0xffb07564, // nude
            0xffb5132b, // cherry red
            0xff6d1a2c, // burgundy
            0xffe0685a, // coral
            0xff8c2951  // berry
    };
    private static final float[] LIPSTICK_SATURATION = {0.70f, 0.60f, 0.45f, 0.85f, 0.80f, 0.70f, 0.80f};
    public static final int[] LIPSTICK_NAMES = {
            R.string.UMessageLipstickRose, R.string.UMessageLipstickPink, R.string.UMessageLipstickNude,
            R.string.UMessageLipstickCherry, R.string.UMessageLipstickBurgundy, R.string.UMessageLipstickCoral,
            R.string.UMessageLipstickBerry
    };

    /** One color filter, or Beauty: face smoothing with a light blush on the cheeks. */
    public static final class Preset {
        public final int nameRes;
        public final int filter, filterStrength, beauty, foundation, blush, eyes, eyeTone, lipstick, shade;
        public final int lipSaturation, lipBrightness, lipSoftness;

        private Preset(int nameRes, int filter, int filterStrength, int beauty, int foundation, int blush,
                       int eyes, int eyeTone, int lipstick, int shade,
                       int lipSaturation, int lipBrightness, int lipSoftness) {
            this.nameRes = nameRes;
            this.filter = filter;
            this.filterStrength = filterStrength;
            this.beauty = beauty;
            this.foundation = foundation;
            this.blush = blush;
            this.eyes = eyes;
            this.eyeTone = eyeTone;
            this.lipstick = lipstick;
            this.shade = shade;
            this.lipSaturation = lipSaturation;
            this.lipBrightness = lipBrightness;
            this.lipSoftness = lipSoftness;
        }
    }

    private static final Preset[] PRESETS = buildPresets();

    private static Preset[] buildPresets() {
        final java.util.ArrayList<Preset> list = new java.util.ArrayList<>();
        list.add(new Preset(R.string.UMessageMakeupOriginal, 0, 0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 60));
        list.add(new Preset(R.string.UMessageRoundBeauty, 0, 0, 100, 0, 35, 0, 0, 0, 0, 50, 50, 60));
        final int[] filterNames = {R.string.UMessageFilterClarendon, R.string.UMessageFilterJuno, R.string.UMessageFilterLark,
                R.string.UMessageFilterGingham, R.string.UMessageFilterValencia, R.string.UMessageFilterMoon,
                R.string.UMessageFilterNashville, R.string.UMessageFilterVintage};
        for (int i = 0; i < filterNames.length; i++) {
            list.add(new Preset(filterNames[i], i + 1, 100, 0, 0, 0, 0, 0, 0, 0, 50, 50, 60));
        }
        return list.toArray(new Preset[0]);
    }

    /** Fully resolved values sent to the live preview and encoder. */
    public static final class Look {
        public final int preset, intensity, filter, filterIntensity, beauty, foundation, blush, eyes, eyeTone, lipstickPercent;
        public final Lipstick lipstick;
        /** Snap Camera Kit lens id ({@link SnapCameraKit}), null for none. */
        public final String lens;
        /** Whenever {@link #lens} has no frame to show, show and record a flat cover instead of the camera. */
        public final boolean covered;

        private Look(int preset, int intensity, int filter, int filterIntensity, int beauty, int foundation,
                     int blush, int eyes, int eyeTone, int lipstickPercent, Lipstick lipstick, String lens, boolean covered) {
            this.lens = lens;
            this.covered = covered;
            this.preset = preset;
            this.intensity = intensity;
            this.filter = filter;
            this.filterIntensity = filterIntensity;
            this.beauty = beauty;
            this.foundation = foundation;
            this.blush = blush;
            this.eyes = eyes;
            this.eyeTone = eyeTone;
            this.lipstickPercent = lipstickPercent;
            this.lipstick = lipstick;
        }
    }

    /** A Snap lens: no shader effects, the lens does everything. */
    private static final Preset SNAP_PRESET = new Preset(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 60);

    public static int getPresetCount() {
        return PRESETS.length + SnapCameraKit.getLensCount();
    }

    public static boolean isSnapPreset(int preset) {
        return preset >= PRESETS.length;
    }

    /** Index in {@link SnapCameraKit}'s lens list of a Snap preset. */
    public static int getSnapLensIndex(int preset) {
        return preset - PRESETS.length;
    }

    public static Preset getPreset(int index) {
        if (isSnapPreset(index)) {
            return SNAP_PRESET;
        }
        return PRESETS[Math.max(0, index)];
    }

    public static String getPresetName(int preset) {
        if (isSnapPreset(preset)) {
            final String name = SnapCameraKit.getLensName(getSnapLensIndex(preset));
            return name != null ? name : "Snap";
        }
        return LocaleController.getString(getPreset(preset).nameRes);
    }

    /** Carousel position to preset id: Original, the Snap lenses, then the fixed presets. */
    public static int presetAt(int position) {
        final int lenses = SnapCameraKit.getLensCount();
        if (position == 0) {
            return PRESET_ORIGINAL;
        }
        if (position <= lenses) {
            return PRESETS.length + position - 1;
        }
        return position - lenses;
    }

    public static int positionOf(int preset) {
        if (preset == PRESET_ORIGINAL) {
            return 0;
        }
        if (isSnapPreset(preset)) {
            return preset - PRESETS.length + 1;
        }
        return preset + SnapCameraKit.getLensCount();
    }

    public static Look createLook(int preset, int intensity) {
        preset = Math.max(0, preset);
        intensity = Math.max(0, Math.min(100, intensity));
        final Preset recipe = getPreset(preset);
        final float amount = preset == PRESET_ORIGINAL ? 0f : intensity / 100f;
        // a Snap lens that is not loaded yet shows the camera as is
        String lens = isSnapPreset(preset) ? SnapCameraKit.getLensId(getSnapLensIndex(preset)) : null;
        boolean covered = SnapCameraKit.COVER_LENS_ID.equals(lens);
        if (!covered && isSavedCoverWithoutLens(preset)) {
            // the list has not loaded, or no longer has the lens: this position must not show another lens or the camera
            lens = null;
            covered = true;
        }
        if (covered) {
            intensity = 100; // a lower strength mixes the camera frame back in
        }
        final int foundation = Math.round(recipe.foundation * amount);
        // Beauty's blush: the cheek mask from the face mesh, scaled like the smoothing
        final int blush = Math.round(recipe.blush * amount);
        final int beauty = Math.round(recipe.beauty * amount);
        final int eyes = Math.round(recipe.eyes * amount);
        final int lipStrength = Math.round(recipe.lipstick * amount);
        final Lipstick lipstick = lipStrength > 0 ? new Lipstick(lipStrength, recipe.shade, recipe.lipSaturation,
                recipe.lipBrightness, recipe.lipSoftness) : null;
        return new Look(preset, intensity, recipe.filter, Math.round(recipe.filterStrength * amount), beauty,
                foundation, blush, eyes, recipe.eyeTone, lipStrength, lipstick, lens, covered);
    }

    private static boolean isSavedCoverWithoutLens(int preset) {
        return isSnapPreset(preset) && preset == UMessageConfig.getRoundEffectPreset()
                && SnapCameraKit.COVER_LENS_ID.equals(UMessageConfig.getRoundEffectLens())
                && SnapCameraKit.indexOfLens(SnapCameraKit.COVER_LENS_ID) < 0;
    }

    /** Lens id of a preset; the saved one while the saved preset's lens is not in the list yet. Null for no lens. */
    public static String getLensIdOf(int preset) {
        if (!isSnapPreset(preset)) {
            return null;
        }
        final String lens = SnapCameraKit.getLensId(getSnapLensIndex(preset));
        if (lens != null) {
            return lens;
        }
        return preset == UMessageConfig.getRoundEffectPreset() ? UMessageConfig.getRoundEffectLens() : null;
    }

    /** The preset now holding a lens, -1 when the lens is not in the list. */
    public static int getPresetOfLens(String lensId) {
        final int index = SnapCameraKit.indexOfLens(lensId);
        return index >= 0 ? PRESETS.length + index : -1;
    }

    /** Uses only the saved color filter or Beauty; legacy makeup values are intentionally ignored. */
    public static int getConfiguredPreset() {
        final int saved = UMessageConfig.getRoundEffectPreset();
        // a saved Snap lens stays selected while the lens list is still loading
        if (saved >= 0) {
            final int current = isSnapPreset(saved) ? getPresetOfLens(UMessageConfig.getRoundEffectLens()) : -1;
            return current >= 0 ? current : saved;
        }
        // legacy settings: a color filter or Beauty
        final int filter = UMessageConfig.getRoundFilter();
        if (filter > 0) {
            for (int i = PRESET_FILTER_FIRST; i < PRESETS.length; i++) {
                if (PRESETS[i].filter == filter) {
                    return i;
                }
            }
        }
        return UMessageConfig.getRoundBeauty() > 0 ? PRESET_BEAUTY : PRESET_ORIGINAL;
    }

    public static int getConfiguredIntensity() {
        return UMessageConfig.getRoundEffectIntensity();
    }

    /** Saves the selected filter and clears every removed makeup layer. */
    public static void saveConfiguredLook(int preset, int intensity) {
        preset = Math.max(0, preset);
        intensity = Math.max(0, Math.min(100, intensity));
        // an unloaded lens keeps its saved id, for the moment the list arrives
        final String lens = getLensIdOf(preset);
        UMessageConfig.setRoundEffectLens(lens);
        UMessageConfig.setRoundEffectPreset(preset);
        final Look look = createLook(preset, intensity);
        UMessageConfig.setRoundEffectIntensity(intensity);
        UMessageConfig.setRoundFilter(look.filterIntensity > 0 ? look.filter : 0);
        UMessageConfig.setRoundBeauty(look.beauty);
        UMessageConfig.setRoundBlush(0);
        UMessageConfig.setRoundLipstick(0);
    }

    /** Lipstick look, immutable so the camera and encoder threads can share it. */
    public static final class Lipstick {
        /** Pigment color with its luminance scaled to 1: only hue and saturation, the lips keep their own light. */
        final float r, g, b;
        final float intensity, saturation, brightness, depth, softness;

        /**
         * @param percent strength, 1..100
         * @param saturationPercent brightnessPercent 50 is neutral
         * @param softnessPercent width of the soft edge
         */
        public Lipstick(int percent, int shade, int saturationPercent, int brightnessPercent, int softnessPercent) {
            shade = Math.max(0, Math.min(LIPSTICK_COLORS.length - 1, shade));
            final int color = LIPSTICK_COLORS[shade];
            final float cr = Color.red(color) / 255f, cg = Color.green(color) / 255f, cb = Color.blue(color) / 255f;
            final float luma = Math.max(0.04f, cr * 0.299f + cg * 0.587f + cb * 0.114f);
            r = cr / luma;
            g = cg / luma;
            b = cb / luma;
            intensity = Math.max(0, Math.min(100, percent)) / 100f;
            saturation = Math.min(1f, LIPSTICK_SATURATION[shade] * (0.4f + 1.2f * saturationPercent / 100f));
            brightness = (brightnessPercent - 50) / 50f * 0.12f;
            // dark shades deepen the lips a little, light ones barely lighten them: a factor, so contrast is kept
            depth = Math.max(0.62f, Math.min(1.05f, 1f + (luma / 0.40f - 1f) * 0.5f));
            softness = Math.max(0, Math.min(100, softnessPercent)) / 100f;
        }

        public float getSoftness() {
            return softness;
        }

        /** Look from the settings at the given strength, null when off. */
        public static Lipstick fromConfig(int percent) {
            if (percent <= 0) {
                return null;
            }
            return new Lipstick(percent, UMessageConfig.getRoundLipstickShade(), UMessageConfig.getRoundLipSaturation(),
                    UMessageConfig.getRoundLipBrightness(), UMessageConfig.getRoundLipSoftness());
        }
    }

    /** Uniform locations of a program built with {@link #GLSL}. */
    public static class Uniforms {
        private int filter = -1, filterAmount = -1, beauty = -1, blush = -1, makeup = -1, eyeColor = -1, mask = -1, viewport = -1;
        private int lipMask = -1, lipColor = -1, lipParams = -1, lipChroma = -1;
        private int makeupFrame = -1, makeupOn = -1;
        private int coverTexture;

        public void init(int program) {
            filter = GLES20.glGetUniformLocation(program, "filterType");
            filterAmount = GLES20.glGetUniformLocation(program, "filterAmount");
            beauty = GLES20.glGetUniformLocation(program, "beauty");
            blush = GLES20.glGetUniformLocation(program, "blush");
            makeup = GLES20.glGetUniformLocation(program, "makeup");
            eyeColor = GLES20.glGetUniformLocation(program, "eyeColor");
            mask = GLES20.glGetUniformLocation(program, "blushMask");
            viewport = GLES20.glGetUniformLocation(program, "viewportSize");
            lipMask = GLES20.glGetUniformLocation(program, "lipMask");
            lipColor = GLES20.glGetUniformLocation(program, "lipColor");
            lipParams = GLES20.glGetUniformLocation(program, "lipParams");
            lipChroma = GLES20.glGetUniformLocation(program, "lipChroma");
            makeupFrame = GLES20.glGetUniformLocation(program, "makeupFrame");
            makeupOn = GLES20.glGetUniformLocation(program, "makeupOn");
        }

        /**
         * The Snap lens frame (gl_FragCoord space, see {@link SnapCameraKit.Renderer}) blends with the camera color,
         * 0 is off. Call with the program in use; binds texture unit 3 and leaves unit 0 active.
         */
        public void applyMakeup(int texture) {
            applyMakeup(texture, 100);
        }

        /** Blends the rendered lens with the original frame at the configured strength. */
        public void applyMakeup(int texture, int intensity) {
            GLES20.glUniform1i(makeupFrame, 3);
            GLES20.glUniform1f(makeupOn, texture != 0 ? Math.max(0, Math.min(100, intensity)) / 100f : 0f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }

        /** Draws a flat color instead of the camera, for a look that must not show it. Same contract as applyMakeup. */
        public void applyCover() {
            if (coverTexture == 0 || !GLES20.glIsTexture(coverTexture)) {
                final int[] texture = new int[1];
                GLES20.glGenTextures(1, texture, 0);
                coverTexture = texture[0];
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, coverTexture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                final java.nio.ByteBuffer color = java.nio.ByteBuffer.allocateDirect(4);
                color.put((byte) 0x1C).put((byte) 0x1C).put((byte) 0x1E).put((byte) 0xFF).position(0);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, color);
            }
            applyMakeup(coverTexture, 100);
        }

        /**
         * Call with the program in use after {@link #apply}; binds the rendered mask to texture unit 2 and leaves
         * unit 0 active. Lipstick is off when either argument is null or no lips were found.
         */
        public void applyLipstick(Lipstick lipstick, LipstickTracker.Mask lips) {
            // always point the sampler at its own unit: a sampler2D left on unit 0 would clash with the camera texture
            GLES20.glUniform1i(lipMask, 2);
            final int texture = lipstick != null && lips != null ? lips.getTexture() : 0;
            if (texture == 0) {
                GLES20.glUniform4f(lipColor, 0, 0, 0, 0);
                return;
            }
            GLES20.glUniform4f(lipColor, lipstick.r, lipstick.g, lipstick.b, lipstick.intensity);
            GLES20.glUniform4f(lipParams, lipstick.saturation, lipstick.brightness, lipstick.depth, lips.colorConfidence);
            GLES20.glUniform4f(lipChroma, lips.skinCb, lips.skinCr, lips.lipCb, lips.lipCr);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }

        /**
         * @param beautyPercent skin smoothing strength, 0 is off
         * @param blushPercent blush strength, ignored without a {@link BlushTracker} mask
         * Binds the mask to texture unit 1 and leaves unit 0 active.
         */
        public void apply(int filterIndex, int filterPercent, int beautyPercent, int foundationPercent,
                          int blushPercent, int eyesPercent, int eyeTone, int maskTexture, int width, int height) {
            GLES20.glUniform1f(filter, filterIndex);
            GLES20.glUniform1f(filterAmount, Math.max(0, Math.min(100, filterPercent)) / 100f);
            GLES20.glUniform1f(beauty, beautyPercent / 100f);
            GLES20.glUniform1f(blush, maskTexture != 0 ? blushPercent / 100f : 0f);
            GLES20.glUniform4f(makeup, maskTexture != 0 ? foundationPercent / 100f : 0f,
                    maskTexture != 0 ? eyesPercent / 100f : 0f, 0f, 0f);
            switch (eyeTone) {
                case 1: GLES20.glUniform3f(eyeColor, 0.78f, 0.48f, 0.55f); break; // rose
                case 2: GLES20.glUniform3f(eyeColor, 0.56f, 0.49f, 0.45f); break; // taupe
                case 3: GLES20.glUniform3f(eyeColor, 0.82f, 0.53f, 0.43f); break; // coral
                case 4: GLES20.glUniform3f(eyeColor, 0.54f, 0.38f, 0.50f); break; // plum
                default: GLES20.glUniform3f(eyeColor, 0.70f, 0.57f, 0.50f); break; // nude
            }
            GLES20.glUniform2f(viewport, width, height);
            GLES20.glUniform1i(mask, 1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
    }

    public static int getFilterCount() {
        return FILTER_NAMES.length;
    }

    public static String getFilterName(int index) {
        return FILTER_NAMES[index];
    }

    public static final String GLSL =
            "uniform float filterType;\n" +
            "uniform float filterAmount;\n" +
            "uniform float beauty;\n" +
            "uniform float blush;\n" +
            "uniform vec4 makeup;\n" +
            "uniform vec3 eyeColor;\n" +
            "uniform sampler2D blushMask;\n" +
            "uniform vec2 viewportSize;\n" +
            "uniform sampler2D lipMask;\n" +
            "uniform vec4 lipColor;\n" +  // pigment with luminance 1, a = strength (0 is off)
            "uniform vec4 lipParams;\n" + // saturation, brightness, shade depth, lip / skin color confidence
            "uniform vec4 lipChroma;\n" + // CbCr of the skin around the mouth, CbCr of the lips
            "uniform sampler2D makeupFrame;\n" + // Snap lens frame in gl_FragCoord space
            "uniform float makeupOn;\n" +

            "vec3 applyFilter(vec3 c) {\n" +
            "   if (filterType < 0.5 || filterAmount < 0.004) return c;\n" +
            "   vec3 original = c;\n" +
            "   float l = dot(c, vec3(0.299, 0.587, 0.114));\n" +
            "   if (filterType < 1.5) {\n" + // Clarendon
            "       c = (c - 0.5) * 1.2 + 0.5;\n" +
            "       c = mix(vec3(l), c, 1.35);\n" +
            "       c += vec3(-0.02, 0.01, 0.05) * (1.0 - l);\n" +
            "   } else if (filterType < 2.5) {\n" + // Juno
            "       c = mix(vec3(l), c, 1.3) * vec3(1.08, 1.02, 0.9);\n" +
            "   } else if (filterType < 3.5) {\n" + // Lark
            "       c = mix(vec3(l), c * 1.08 + 0.03, 0.9) * vec3(0.98, 1.0, 1.04);\n" +
            "   } else if (filterType < 4.5) {\n" + // Gingham
            "       c = mix(vec3(l), c, 0.75) * 0.85 + vec3(0.12, 0.1, 0.1);\n" +
            "   } else if (filterType < 5.5) {\n" + // Valencia
            "       c = (c * vec3(1.08, 0.99, 0.86) + vec3(0.06, 0.03, 0.0) - 0.5) * 1.05 + 0.5;\n" +
            "   } else if (filterType < 6.5) {\n" + // Moon
            "       c = vec3((l - 0.5) * 1.3 + 0.55);\n" +
            "   } else if (filterType < 7.5) {\n" + // Nashville
            "       c = (c * vec3(1.0, 0.95, 0.85) + vec3(0.1, 0.04, 0.08) - 0.5) * 1.1 + 0.5;\n" +
            "   } else {\n" + // Vintage (sepia)
            "       vec3 s = vec3(dot(c, vec3(0.393, 0.769, 0.189)), dot(c, vec3(0.349, 0.686, 0.168)), dot(c, vec3(0.272, 0.534, 0.131)));\n" +
            "       c = mix(c, s, 0.85);\n" +
            "   }\n" +
            "   return clamp(mix(original, c, filterAmount), 0.0, 1.0);\n" +
            "}\n" +

            // edge-preserving blur tap: neighbours with a very different color (eyes, brows, hair) get no weight
            "void beautyTap(vec2 uv, vec2 o, vec3 c, inout vec3 sum, inout float wsum) {\n" +
            // under a Snap lens the smoothing reads the lens frame, so the lens is smoothed, not erased
            "   vec3 s = makeupOn > 0.0 ? mix(texture2D(sTexture, uv + o).rgb, texture2D(makeupFrame, gl_FragCoord.xy / viewportSize + o).rgb, makeupOn) : texture2D(sTexture, uv + o).rgb;\n" +
            "   float w = max(0.0, 1.0 - length(s - c) * 4.0);\n" +
            "   sum += s * w;\n" +
            "   wsum += w;\n" +
            "}\n" +

            "float lipLuma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n" +
            "vec2 lipCbCr(vec3 c) { return vec2(dot(c, vec3(-0.1687, -0.3313, 0.5)), dot(c, vec3(0.5, -0.4187, -0.0813))); }\n" +

            // lipstick coverage 0..1: the soft mask of the lip mesh (gl_FragCoord space of the frame); near the lip
            // borders (mask green) only pixels whose color is closer to the lips than to the skin keep it, which
            // follows the real lip line and leaves skin, stubble, teeth and the dark mouth out
            "float lipCoverage(vec3 c) {\n" +
            "   if (lipColor.a < 0.004) return 0.0;\n" +
            "   vec4 m = texture2D(lipMask, gl_FragCoord.xy / viewportSize);\n" +
            "   if (m.r < 0.004) return 0.0;\n" +
            "   vec2 axis = lipChroma.zw - lipChroma.xy;\n" +
            "   float p = dot(lipCbCr(c) - lipChroma.xy, axis) / max(dot(axis, axis), 0.0002);\n" +
            "   float lipness = smoothstep(0.05, 0.55, p) * smoothstep(0.02, 0.09, lipLuma(c));\n" +
            "   return m.r * mix(1.0, lipness, m.g * lipParams.w);\n" +
            "}\n" +

            // recolors the lip pixel instead of painting over it: the pigment's hue and saturation under the lip's
            // own luminance, so texture, lines, shadows and highlights stay; dim pixels get little color (no neon
            // in low light), highlights stay bright and almost white (no blown out color in strong light)
            "vec3 applyLipstick(vec3 c, float a) {\n" +
            "   if (a < 0.004) return c;\n" +
            "   float l = lipLuma(c);\n" +
            "   float light = smoothstep(0.02, 0.3, l);\n" +
            "   float spec = smoothstep(0.7, 0.95, l);\n" +
            "   float sat = lipParams.x * light * (1.0 - 0.75 * spec);\n" +
            "   float k = mix(lipParams.z, 1.0, spec) * (1.0 + lipParams.y);\n" +
            "   float lt = min(l * k, 1.0);\n" +
            "   vec3 t = lt * mix(vec3(1.0), lipColor.rgb, sat) + (c - l) * (0.3 * k);\n" +
            // out of gamut: pull toward gray at the same luminance instead of clipping channels
            "   float lo = min(min(t.r, t.g), t.b);\n" +
            "   float hi = max(max(t.r, t.g), t.b);\n" +
            "   if (lo < 0.0) t = lt + (t - lt) * lt / max(lt - lo, 0.001);\n" +
            "   if (hi > 1.0) t = lt + (t - lt) * (1.0 - lt) / max(hi - lt, 0.001);\n" +
            "   return mix(c, clamp(t, 0.0, 1.0), a * lipColor.a);\n" +
            "}\n" +

            // skin smoothing and blush, both 0..1; the blush mask is in gl_FragCoord space of the frame.
            // lip is the lipstick coverage: lips are not smoothed, their texture stays
            "vec3 applyBeauty(vec3 c, vec2 uv, float lip) {\n" +
            "   vec4 fm = texture2D(blushMask, gl_FragCoord.xy / viewportSize);\n" +
            "   if (blush > 0.01) {\n" +
            "       c = mix(c, c * vec3(1.015, 0.88, 0.91) + vec3(0.035, 0.0, 0.012), fm.r * blush * 0.42);\n" +
            "   }\n" +
            "   if (makeup.y > 0.01) {\n" +
            "       float l = lipLuma(c);\n" +
            "       vec3 toned = l * mix(vec3(1.0), eyeColor, 0.42) + (c - l) * 0.72;\n" +
            "       c = mix(c, toned, fm.g * makeup.y * 0.34);\n" +
            "   }\n" +
            "   if (beauty < 0.01 && makeup.x < 0.01) return c;\n" +
            "   float cb = -0.1687 * c.r - 0.3313 * c.g + 0.5 * c.b;\n" +
            "   float cr = 0.5 * c.r - 0.4187 * c.g - 0.0813 * c.b;\n" +
            "   float skin = smoothstep(-0.25, -0.18, cb) * (1.0 - smoothstep(0.0, 0.05, cb))\n" +
            "              * smoothstep(0.0, 0.04, cr) * (1.0 - smoothstep(0.18, 0.23, cr));\n" +
            "   skin *= 1.0 - lip;\n" +
            "   if (skin < 0.01) return c;\n" +
            "   if (makeup.x > 0.01) {\n" +
            "       float l = lipLuma(c);\n" +
            "       vec3 even = mix(vec3(l), c, 0.92);\n" +
            "       even = mix(even, vec3(1.0) - (vec3(1.0) - even) * (vec3(1.0) - even), 0.06);\n" +
            "       c = mix(c, even, fm.b * skin * makeup.x * 0.38);\n" +
            "   }\n" +
            "   if (beauty < 0.01) return c;\n" +
            "   float r = 0.003 + 0.005 * beauty;\n" +
            "   float d = r * 0.7071;\n" +
            "   vec3 sum = c;\n" +
            "   float wsum = 1.0;\n" +
            "   beautyTap(uv, vec2(r, 0.0), c, sum, wsum);\n" +
            "   beautyTap(uv, -(vec2(r, 0.0)), c, sum, wsum);\n" +
            "   beautyTap(uv, vec2(0.0, r), c, sum, wsum);\n" +
            "   beautyTap(uv, -(vec2(0.0, r)), c, sum, wsum);\n" +
            "   beautyTap(uv, vec2(d, d), c, sum, wsum);\n" +
            "   beautyTap(uv, -(vec2(d, d)), c, sum, wsum);\n" +
            "   beautyTap(uv, vec2(d, -d), c, sum, wsum);\n" +
            "   beautyTap(uv, -(vec2(d, -d)), c, sum, wsum);\n" +
            "   beautyTap(uv, vec2(2.0 * r, 0.0), c, sum, wsum);\n" +
            "   beautyTap(uv, -(vec2(2.0 * r, 0.0)), c, sum, wsum);\n" +
            "   beautyTap(uv, vec2(0.0, 2.0 * r), c, sum, wsum);\n" +
            "   beautyTap(uv, -(vec2(0.0, 2.0 * r)), c, sum, wsum);\n" +
            "   c = mix(c, sum / wsum, skin * beauty);\n" +
            "   c = mix(c, vec3(1.0) - (vec3(1.0) - c) * (vec3(1.0) - c), skin * 0.3 * beauty);\n" +
            "   return clamp(c, 0.0, 1.0);\n" +
            "}\n" +

            "vec3 applyEffects(vec3 c, vec2 uv) {\n" +
            "   if (makeupOn > 0.0) c = mix(c, texture2D(makeupFrame, gl_FragCoord.xy / viewportSize).rgb, makeupOn);\n" +
            "   float lip = lipCoverage(c);\n" +
            "   c = applyFilter(applyLipstick(applyBeauty(c, uv, lip), lip));\n" +
            "   return c;\n" +
            "}\n";
}
