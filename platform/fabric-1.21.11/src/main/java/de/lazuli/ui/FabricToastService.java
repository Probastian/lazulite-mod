package de.lazuli.ui;

import de.lazuli.services.ui.ToastService;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

/**
 * The real, vanilla-toast-backed {@link ToastService} on Minecraft 1.21.11
 * (Yarn-mapped). Confirmed via {@code javap} against this module's own
 * resolved, Yarn-remapped Minecraft jar:
 * {@code MinecraftClient.getInstance().getToastManager()} and
 * {@code SystemToast.add(ToastManager, SystemToast.Type, Text, Text)},
 * using {@code SystemToast.Type.PERIODIC_NOTIFICATION} as the closest
 * existing generic/repeatable notification token (mirrors the Mojmap
 * modules' own {@code SystemToastId.PERIODIC_NOTIFICATION} choice).
 */
public final class FabricToastService implements ToastService {

    @Override
    public void post(String title, String message) {
        SystemToast.add(MinecraftClient.getInstance().getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.literal(title), Text.literal(message));
    }
}
