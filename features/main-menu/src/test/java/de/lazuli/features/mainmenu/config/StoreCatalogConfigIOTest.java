package de.lazuli.features.mainmenu.config;

import de.lazuli.api.mainmenu.StoreItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class StoreCatalogConfigIOTest {

    private final StoreCatalogConfigIO io = new StoreCatalogConfigIO();

    @Test
    void parsesAFullyConfiguredItem() {
        StoreCatalogConfigIO.ParseResult result = io.parse("""
                {
                  "items": [
                    {
                      "id": "moss-cloak", "displayName": "Moss Cloak",
                      "description": "A traveler's cloak, dyed in forest tones.",
                      "category": "TORSO", "priceCents": 499, "originalPriceCents": 799,
                      "inventoryItemDefId": null, "steamDlcAppId": null, "featured": true
                    }
                  ]
                }
                """);
        assertThat(result.warning()).isNull();
        assertThat(result.items()).containsExactly(new StoreItem(
                "moss-cloak", "Moss Cloak", "A traveler's cloak, dyed in forest tones.", "TORSO",
                499, OptionalInt.of(799), OptionalInt.empty(), OptionalInt.empty(), true));
    }

    @Test
    void serializeParseRoundTrip() {
        List<StoreItem> items = List.of(
                new StoreItem("moss-cloak", "Moss Cloak", "desc", "TORSO", 499,
                        OptionalInt.of(799), OptionalInt.of(1001), OptionalInt.empty(), true),
                new StoreItem("wanderer-hood", "Wanderer's Hood", "desc2", "HEAD", 299,
                        OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(480), false));
        String json = io.serialize(items);
        StoreCatalogConfigIO.ParseResult result = io.parse(json);
        assertThat(result.warning()).isNull();
        assertThat(result.items()).containsExactlyElementsOf(items);
    }

    @Test
    void malformedFallsBackToDefaultWithWarning() {
        StoreCatalogConfigIO.ParseResult result = io.parse("{ not valid json");
        assertThat(result.items()).isEqualTo(StoreCatalogConfigIO.DEFAULT_CATALOG);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void nullContentFallsBackToDefaultWithWarning() {
        StoreCatalogConfigIO.ParseResult result = io.parse(null);
        assertThat(result.items()).isEqualTo(StoreCatalogConfigIO.DEFAULT_CATALOG);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void missingRequiredFieldFallsBackToDefaultWithWarning() {
        StoreCatalogConfigIO.ParseResult result = io.parse("""
                {
                  "items": [
                    { "id": "moss-cloak" }
                  ]
                }
                """);
        assertThat(result.items()).isEqualTo(StoreCatalogConfigIO.DEFAULT_CATALOG);
        assertThat(result.warning()).isNotNull();
    }

    @Test
    void defaultCatalogCoversAllFourWardrobeSlotsPlusOneFeaturedItem() {
        assertThat(StoreCatalogConfigIO.DEFAULT_CATALOG)
                .extracting(StoreItem::category)
                .containsExactlyInAnyOrder("HEAD", "TORSO", "LEGS", "FEET");
        assertThat(StoreCatalogConfigIO.DEFAULT_CATALOG.stream().filter(StoreItem::featured)).hasSize(1);
        assertThat(StoreCatalogConfigIO.DEFAULT_CATALOG).allSatisfy(item -> {
            assertThat(item.inventoryItemDefId()).isEmpty();
            assertThat(item.steamDlcAppId()).isEmpty();
        });
    }

    @Test
    void missingFileCreatesDefault(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("main-menu-store-catalog.json");
        assertThat(Files.exists(path)).isFalse();

        StoreCatalogConfigIO.ParseResult result = io.load(path);

        assertThat(result.warning()).isNull();
        assertThat(result.items()).isEqualTo(StoreCatalogConfigIO.DEFAULT_CATALOG);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void malformedFileFallsBackToDefaultWithWarning(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("main-menu-store-catalog.json");
        Files.writeString(path, "{ not valid json");

        StoreCatalogConfigIO.ParseResult result = io.load(path);

        assertThat(result.items()).isEqualTo(StoreCatalogConfigIO.DEFAULT_CATALOG);
        assertThat(result.warning()).isNotNull();
    }
}
