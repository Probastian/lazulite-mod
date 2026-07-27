package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T1 (Anti-Drop): cancels the client-side "drop selected item"
 * entry point before any inventory mutation/packet send.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar (see {@code docs/specs/tweaks-hooks-wiring.md}'s
 * "Findings -- javap verification pass" section, correcting the spec's
 * earlier guessed {@code ClientPlayerInteractionManager.dropItem} target,
 * which does not exist):</strong> {@code ClientPlayerEntity.dropSelectedItem
 * (boolean)} is the real entry point invoked from the client's key-handling,
 * where the {@code boolean} argument selects "drop whole stack" vs "drop one
 * item" -- treated here as the hook's {@code shiftHeld} signal, matching
 * vanilla's own drop-stack-vs-drop-one keybind semantics.
 */
@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerEntityAntiDropMixin {

    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelDrop(boolean dropStack, CallbackInfoReturnable<Boolean> cir) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        ItemStack held = self.getInventory().getSelectedStack();
        String itemId = held.isEmpty() ? "" : Registries.ITEM.getId(held.getItem()).toString();
        if (TweakEngineHandoff.require().shouldCancelDrop(itemId, dropStack)) {
            cir.setReturnValue(false);
        }
    }
}
