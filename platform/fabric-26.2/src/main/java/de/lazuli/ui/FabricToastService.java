package de.lazuli.ui;

import de.lazuli.services.ui.ToastService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * The real, vanilla-toast-backed {@link ToastService} on Minecraft 26.2
 * (Mojang-mapped, unobfuscated). Confirmed via {@code javap} against this
 * module's own resolved Minecraft jar (not decompiled documentation):
 * {@code Minecraft.getInstance().gui} exposes
 * {@code public ToastManager toastManager()}, and
 * {@code SystemToast.add(ToastManager, SystemToastId, Component, Component)}
 * posts a title+message toast using an existing {@code SystemToastId}
 * ({@code PERIODIC_NOTIFICATION}, the closest existing generic/repeatable
 * notification token -- there is no bespoke "custom message" id).
 */
public final class FabricToastService implements ToastService {

    @Override
    public void post(String title, String message) {
        SystemToast.add(Minecraft.getInstance().gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(message));
    }
}
