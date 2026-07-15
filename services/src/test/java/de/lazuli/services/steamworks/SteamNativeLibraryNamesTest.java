package de.lazuli.services.steamworks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SteamNativeLibraryNamesTest {

    @Test
    void resolvesWindows64BitSteamApi() {
        assertThat(SteamNativeLibraryNames.resourceName("steam_api", "Windows 10", "amd64"))
                .isEqualTo("steam_api64.dll");
    }

    @Test
    void resolvesWindows64BitSteamworks4j() {
        assertThat(SteamNativeLibraryNames.resourceName("steamworks4j", "Windows 11", "x86_64"))
                .isEqualTo("steamworks4j64.dll");
    }

    @Test
    void windows32BitIsUnsupported() {
        assertThat(SteamNativeLibraryNames.resourceName("steam_api", "Windows 10", "x86")).isNull();
    }

    @Test
    void resolvesLinux64BitSteamApi() {
        assertThat(SteamNativeLibraryNames.resourceName("steam_api", "Linux", "amd64"))
                .isEqualTo("libsteam_api.so");
    }

    @Test
    void resolvesLinuxAarch64Steamworks4j() {
        assertThat(SteamNativeLibraryNames.resourceName("steamworks4j", "Linux", "aarch64"))
                .isEqualTo("libsteamworks4j.so");
    }

    @Test
    void resolvesMacSteamApiRegardlessOfArch() {
        assertThat(SteamNativeLibraryNames.resourceName("steam_api", "Mac OS X", "x86_64"))
                .isEqualTo("libsteam_api.dylib");
        assertThat(SteamNativeLibraryNames.resourceName("steam_api", "Mac OS X", "aarch64"))
                .isEqualTo("libsteam_api.dylib");
    }

    @Test
    void resolvesMacSteamworks4j() {
        assertThat(SteamNativeLibraryNames.resourceName("steamworks4j", "Mac OS X", "aarch64"))
                .isEqualTo("libsteamworks4j.dylib");
    }

    @Test
    void unknownOsIsUnsupported() {
        assertThat(SteamNativeLibraryNames.resourceName("steam_api", "SomeOtherOS", "amd64")).isNull();
    }

    @Test
    void nullOsAndArchAreUnsupportedRatherThanThrowing() {
        assertThat(SteamNativeLibraryNames.resourceName("steam_api", null, null)).isNull();
    }
}
