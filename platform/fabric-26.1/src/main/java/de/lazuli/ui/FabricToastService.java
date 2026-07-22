package de.lazuli.ui;

import de.lazuli.services.ui.ToastService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * The real, vanilla-toast-backed {@link ToastService} on Minecraft 26.1
 * (Mojang-mapped, unobfuscated). Confirmed via {@code javap} against this
 * module's own resolved Minecraft jar: on this version {@code Minecraft}
 * itself exposes {@code public ToastManager getToastManager()} directly
 * (unlike 26.2, where the accessor moved to {@code Minecraft.gui.toastManager()}
 * -- a genuine cross-version divergence, logged in
 * {@code .claude/context/minecraft.md}). {@code SystemToast.add(...)} itself
 * is unchanged between the two versions.
 */
public final class FabricToastService implements ToastService {

    @Override
    public void post(String title, String message) {
        SystemToast.add(Minecraft.getInstance().getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(message));
    }
}
