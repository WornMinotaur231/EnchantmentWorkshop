package cn.mc.enchantmentworkshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigMigrationTest {
    @TempDir Path directory;
    @Test void createsSeparateDefinitionsAndPreservesLegacyBackup() throws Exception {
        Path config = directory.resolve("config.yml");
        Settings first = ConfigLoader.load(config);
        assertEquals(10, first.enchantments().size());
        assertTrue(Files.exists(directory.resolve("enchantments.yml")));
        assertNotNull(ConfigLoader.yaml(Files.readString(directory.resolve("config-v1-backup.yml"))).getConfigurationSection("enchantments"));
        assertNull(ConfigLoader.yaml(Files.readString(config)).get("enchantments"));
        assertEquals(first, ConfigLoader.load(config));
    }
    @Test void readsManualEditsFromSeparateFile() throws Exception {
        Path config = directory.resolve("config.yml"); ConfigLoader.load(config);
        Path definitions = directory.resolve("enchantments.yml");
        String text = Files.readString(definitions).replace("value: 0.08", "value: 0.25");
        Files.writeString(definitions, text);
        assertEquals(.25, ConfigLoader.load(config).enchantments().get("lifesteal").effects().getFirst().value());
    }
    @Test void rejectsInvalidLegacyConfigBeforeWritingMigration() throws Exception {
        Path config = directory.resolve("config.yml"); Files.writeString(config, "config-version: 8\n");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
        assertFalse(Files.exists(directory.resolve("enchantments.yml")));
        assertEquals("config-version: 8\n", Files.readString(config));
    }
    @Test void unfinishedMigrationKeepsAuthoritativeExternalFile() throws Exception {
        Path config = directory.resolve("config.yml"); Settings expected = ConfigLoader.load(config);
        Files.copy(directory.resolve("config-v1-backup.yml"), config, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(expected, ConfigLoader.load(config));
    }
    @Test void parserRejectsUnsafeNumbersAndBooleans() {
        assertThrows(IllegalArgumentException.class, () -> DefinitionEditor.value("value", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> DefinitionEditor.value("enabled", "yes"));
        assertEquals(java.util.List.of("head", "feet"), DefinitionEditor.value("active-slots", "head, feet"));
    }
}
