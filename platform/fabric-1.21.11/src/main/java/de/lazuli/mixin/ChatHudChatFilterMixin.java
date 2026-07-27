package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Tweaks spec T3 (Chat Filter): masks matched word(s) with asterisks in
 * place before an incoming message ever reaches {@code ChatHud}'s message
 * log (so a masked message never shows the original word even in
 * scrollback), scoped to substring replace only -- whole-message hiding is
 * an explicit non-goal (spec Non-goals/R6).
 *
 * <p>Targets the two public {@code addMessage} overloads (confirmed via
 * {@code javap}): {@code addMessage(Text)} and the signed-message overload
 * {@code addMessage(Text, MessageSignatureData, MessageIndicator)}.
 *
 * <p><strong>Reconstruction strategy:</strong> per-tweak scope (R6), this
 * mixin flattens the incoming {@code Text}'s plain string via {@code
 * Text.getString()}, masks it, and rebuilds a single {@code
 * Text.literal(...)} carrying the message's root {@link
 * net.minecraft.text.Style} -- sibling-level styling (e.g. per-word hover/
 * click events on a styled span) is not preserved for messages that
 * actually contain a filtered word, a known, accepted simplification of
 * the "flattening formatting" risk called out in the spec's T3
 * Classification; messages with no match are passed through unmodified
 * (this is the common case, so no formatting is affected for the vast
 * majority of chat).
 */
@Mixin(ChatHud.class)
abstract class ChatHudChatFilterMixin {

    @ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), argsOnly = true)
    private Text lazuli$filterSimple(Text message) {
        return lazuli$filter(message);
    }

    @ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), argsOnly = true)
    private Text lazuli$filterSigned(Text message) {
        return lazuli$filter(message);
    }

    @org.spongepowered.asm.mixin.Unique
    private static Text lazuli$filter(Text message) {
        String plain = message.getString();
        String masked = TweakEngineHandoff.require().filterText(plain);
        if (masked == null || masked.equals(plain)) {
            return message;
        }
        return Text.literal(masked).setStyle(message.getStyle());
    }
}
