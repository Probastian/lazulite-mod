package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.network.ClientPlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T9 (Clear Water): multiplies the underwater overlay's alpha
 * by the configured opacity multiplier.
 *
 * <p><strong>Bug fix, re-confirmed via {@code javap} against this module's
 * own resolved Yarn-mapped merged Minecraft jar:</strong> the previous
 * revision of this mixin targeted {@code InGameHud.renderOverlay(
 * DrawContext, Identifier, float)} on the (incorrect) assumption that
 * underwater shared the same generic overlay-texture draw call as portal/
 * spyglass/nausea/powder-snow. It does not, on this platform: {@code
 * javap} shows underwater rendering lives entirely in a separate class,
 * {@code net.minecraft.client.gui.hud.InGameOverlayRenderer}, via a
 * dedicated {@code renderUnderwaterOverlay(MinecraftClient, MatrixStack,
 * VertexConsumerProvider)} that builds the overlay quad directly (no
 * {@code Identifier}/{@code float} alpha parameter to intercept) --
 * {@code InGameHud.renderOverlay} is never invoked for underwater at all
 * on this platform, which is why the old mixin had zero observable effect.
 *
 * <p>The alpha actually driving that quad's color is
 * {@code ClientPlayerEntity.getUnderwaterVisibility()} (Yarn
 * {@code method_3140}, javadoc: "the color multiplier of vision in
 * water"), which is a stable, single-value choke point unaffected by the
 * vertex-consumer draw details -- multiplying its return value is
 * equivalent to multiplying the overlay's alpha, matching this hook's
 * documented contract, without needing to fight the render-state/vertex
 * pipeline directly.
 */
@Mixin(ClientPlayerEntity.class)
abstract class InGameHudClearWaterMixin {

    @Inject(method = "getUnderwaterVisibility", at = @At("RETURN"), cancellable = true)
    private void lazuli$clearWater(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(cir.getReturnValue() * TweakEngineHandoff.require().underwaterOverlayOpacityMultiplier());
    }
}
