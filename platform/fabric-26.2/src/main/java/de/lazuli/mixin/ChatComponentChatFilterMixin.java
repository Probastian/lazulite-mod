package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Tweaks spec T3 (Chat Filter): masks matched word(s) with asterisks in
 * place before an incoming message reaches {@code ChatComponent}'s message
 * log.
 *
 * <p><strong>Confirmed via {@code javap}, corrects the spec's guessed
 * public single-{@code Component}-arg overload:</strong> the real (and
 * only) internal add path on 26.1/26.2 is the private {@code addMessage(
 * Component, MessageSignature, GuiMessageSource, GuiMessageTag)} -- Mixin
 * can still target private methods by name/descriptor. Same
 * single-flattened-{@code Component} reconstruction strategy/simplification
 * as the 1.21.11 mixin (see its Javadoc).
 *
 * <p><strong>Handler signature quirk:</strong> with {@code argsOnly = true}
 * and an implicitly-matched (unnamed/no ordinal) captured variable that is
 * itself one of the target method's formal parameters, Mixin 0.8.7 does not
 * exclude that parameter from the trailing "other args" list -- the
 * captured value is prepended as its own leading parameter AND repeated in
 * its original position among the full argument list. So the handler needs
 * two leading {@code Component} parameters here (the captured value, then
 * the untouched duplicate of the same formal parameter), confirmed by
 * running the client and reading Mixin's reported "Expected signature" in
 * the {@code InvalidInjectionException}. See "Known Cross-Version API
 * Differences" in {@code .claude/context/minecraft.md}.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentChatFilterMixin {

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component lazuli$filter(Component message, Component contents, MessageSignature signature,
            GuiMessageSource source, GuiMessageTag tag) {
        String plain = message.getString();
        String masked = TweakEngineHandoff.require().filterText(plain);
        if (masked == null || masked.equals(plain)) {
            return message;
        }
        return Component.literal(masked).setStyle(message.getStyle());
    }
}
