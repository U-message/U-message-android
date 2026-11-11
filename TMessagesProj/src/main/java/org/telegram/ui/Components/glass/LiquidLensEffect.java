package org.telegram.ui.Components.glass;

import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

/** U message: the refraction of {@link GlassPill}, unlike LiquidGlassEffect it is meant for sharp content. */
@RequiresApi(api = 33)
class LiquidLensEffect {

    private final RenderNode node;
    private final RuntimeShader shader;
    private float lastLeft = Float.NaN, lastTop, lastRight, lastBottom, lastThickness, lastStrength, lastAberration, lastLight;

    LiquidLensEffect(RenderNode node) {
        this.node = node;
        shader = new RuntimeShader(AndroidUtilities.readRes(R.raw.liquid_lens_shader));
    }

    void update(float left, float top, float right, float bottom, float thickness, float strength, float aberration, float light) {
        if (left == lastLeft && top == lastTop && right == lastRight && bottom == lastBottom && thickness == lastThickness
            && strength == lastStrength && aberration == lastAberration && light == lastLight) {
            return; // it stays on the bar at rest, and the bar is redrawn while the page behind it scrolls
        }
        lastLeft = left;
        lastTop = top;
        lastRight = right;
        lastBottom = bottom;
        lastThickness = thickness;
        lastStrength = strength;
        lastAberration = aberration;
        lastLight = light;
        final float width = right - left, height = bottom - top;
        shader.setFloatUniform("center", (left + right) / 2f, (top + bottom) / 2f);
        shader.setFloatUniform("size", width / 2f, height / 2f);
        shader.setFloatUniform("radius", Math.min(width, height) / 2f);
        shader.setFloatUniform("thickness", thickness);
        shader.setFloatUniform("strength", strength);
        shader.setFloatUniform("aberration", aberration);
        shader.setFloatUniform("light", light);
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"));
    }
}
