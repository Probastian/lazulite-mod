package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

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
 * earlier guessed {@code MultiPlayerGameMode.dropItem} target, which does
 * not exist):</strong> {@code LocalPlayer.drop(boolean)} is the real entry
 * point, confirmed identical on 26.1 and 26.2.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerAntiDropMixin {

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelDrop(boolean dropStack, CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        ItemStack held = self.getInventory().getSelectedItem();
        String itemId = held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        if (TweakEngineHandoff.require().shouldCancelDrop(itemId, dropStack)) {
            cir.setReturnValue(false);
        }
    }
}
