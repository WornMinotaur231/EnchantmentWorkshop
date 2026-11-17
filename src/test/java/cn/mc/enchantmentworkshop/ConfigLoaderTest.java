package cn.mc.enchantmentworkshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    private Settings flightSettings(String extra) throws Exception {
        var yaml = ConfigLoader.yaml(defaults());
        var effect = new java.util.LinkedHashMap<String, Object>(DefinitionEditor.effect(EffectType.PROGRAM));
        effect.put("action", "FLIGHT");
        ConfigLoader.yaml(extra).getValues(false).forEach(effect::put);
        yaml.set("enchantments.lifesteal.effects", java.util.List.of(effect));
        return ConfigLoader.parse(yaml.saveToString());
    }
    @Test void flightWearDefaultsOffAndCanBeConfigured() throws Exception {
        var defaults = flightSettings("").enchantments().get("lifesteal").effects().getFirst();
        assertEquals(0, defaults.flightDurabilityCost());
        var configured = flightSettings("flight-durability-cost: 2\nflight-durability-interval-ticks: 100").enchantments().get("lifesteal").effects().getFirst();
        assertEquals(2, configured.flightDurabilityCost());
        assertEquals(flightSettings("flight-durability-cost: 2"), flightSettings("flight-durability-cost: 2\nflight-durability-interval-ticks: 100"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"flight-durability-cost: -1", "flight-durability-cost: 1.5", "flight-durability-cost: 1001",
            "flight-durability-interval-ticks: 0", "flight-durability-interval-ticks: 21", "flight-durability-interval-ticks: 1220",
            "action: HEAL\nflight-durability-cost: 1"})
    void rejectsInvalidFlightWear(String extra) { assertThrows(IllegalArgumentException.class, () -> flightSettings(extra)); }
    @Test void emptyDefinitionsSurviveSerialization() throws Exception {
        var yaml = ConfigLoader.yaml(defaults());
        for (String id : new java.util.ArrayList<>(yaml.getConfigurationSection("enchantments").getKeys(false))) yaml.set("enchantments." + id, null);
        assertTrue(ConfigLoader.parse(yaml.saveToString()).enchantments().isEmpty());
        assertFalse(ConfigLoader.parse(defaults()).sameRegistry(ConfigLoader.parse(yaml.saveToString())));
    }
    private String defaults() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void customConflictsUseWorkshopNamespace() throws Exception {
        var yaml = ConfigLoader.yaml(defaults());
        yaml.set("enchantments.lifesteal.conflicts", java.util.List.of("enchantmentworkshop:frostbite"));
        var settings = ConfigLoader.parse(yaml.saveToString());
        assertEquals(java.util.List.of("enchantmentworkshop:frostbite"), settings.enchantments().get("lifesteal").conflicts());
        yaml.set("enchantments.lifesteal.conflicts", java.util.List.of("enchantmentworkshop:lifesteal"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parse(yaml.saveToString()));
    }

    @Test void shippedConfigIsUsable() throws Exception {
        Settings settings = ConfigLoader.parse(defaults());
        assertEquals(10, settings.enchantments().size());
        assertEquals("吸血", settings.enchantments().get("lifesteal").name());
        assertEquals(.16, settings.enchantments().get("lifesteal").effects().getFirst().valueAt(3), 1e-9);
        assertEquals(.4, settings.enchantments().get("frostbite").effects().getFirst().chanceAt(3), 1e-9);
    }

    @Test void runtimeTuningDoesNotRequireRegistryReplacement() throws Exception {
        Settings before = ConfigLoader.parse(defaults());
        Settings after = ConfigLoader.parse(defaults().replace("value: 0.08", "value: 0.12")
                .replace("allow-pvp-effects: true", "allow-pvp-effects: false"));
        assertTrue(before.sameRegistry(after));
        assertNotEquals(before, after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name: 吸血|name: 汲取", "max-level: 3|max-level: 4", "enabled: true|enabled: false",
            "minecraft:swords|minecraft:axes", "enchanting-table: true|enchanting-table: false", "lifesteal:|vampire:"})
    void registryChangesRequireRestart(String change) throws Exception {
        String[] pair = change.split("\\|", 2);
        assertFalse(ConfigLoader.parse(defaults()).sameRegistry(ConfigLoader.parse(defaults().replace(pair[0], pair[1]))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-level: 3|max-level: 0", "chance: 0.35|chance: 1.5", "value: 0.08|value: .NaN",
            "weight: 3|weight: 3.5", "enabled: true|enabled: 'yes'", "type: LIFESTEAL|type: TYPO",
            "value: 0.08|vaule: 0.08", "[mainhand]|[typo]", "potion: minecraft:slowness|potion: Bad Key",
            "conflicts: [minecraft:silk_touch]|conflicts: [enchantmentworkshop:missing]"})
    void rejectsInvalidValuesInsteadOfSilentlyUsingDefaults(String change) throws Exception {
        String[] pair = change.split("\\|", 2);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parse(defaults().replace(pair[0], pair[1])));
    }

    @Test void refusesUnsupportedChanceOnPassiveEffects() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parse(defaults().replace(
                "type: PASSIVE_POTION", "type: PASSIVE_POTION\n        chance: 0.5")));
    }

    @Test void snapshotsAreImmutable() throws Exception {
        Settings settings = ConfigLoader.parse(defaults());
        assertThrows(UnsupportedOperationException.class, () -> settings.enchantments().clear());
        assertThrows(UnsupportedOperationException.class, () -> settings.enchantments().get("lifesteal").effects().clear());
    }

    @Test void supportsNewEnchantCombiningExistingEffects() throws Exception {
        String yaml = defaults() + """

                  battle_mage:
                    name: 战斗法师
                    max-level: 2
                    item-tag: minecraft:swords
                    acquisition: {enchanting-table: true, villager-trade: false, random-loot: false}
                    effects:
                      - type: LIFESTEAL
                        value: 0.10
                      - type: POTION_ON_HIT
                        potion: minecraft:weakness
                        duration-ticks: 40
                """;
        Settings settings = ConfigLoader.parse(yaml);
        assertEquals(11, settings.enchantments().size());
        assertEquals(2, settings.enchantments().get("battle_mage").effects().size());
    }
}
