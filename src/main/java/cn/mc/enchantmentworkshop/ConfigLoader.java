package cn.mc.enchantmentworkshop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern ID = Pattern.compile("[a-z0-9_][a-z0-9_/-]*");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> SLOTS = Set.of("mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "armor", "any");
    private ConfigLoader() {}

    public static Settings load(Path file) throws IOException, InvalidConfigurationException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream resource = ConfigLoader.class.getResourceAsStream("/config.yml")) {
                if (resource == null) throw new IOException("Missing bundled config.yml");
                Files.copy(resource, file);
            }
        }
        return parse(document(file).saveToString());
    }

    /** Split legacy config once, retaining a byte-for-byte backup before migration. */
    public static YamlConfiguration document(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration main = yaml(Files.readString(file, StandardCharsets.UTF_8));
        Path definitions = file.resolveSibling("enchantments.yml");
        if (!Files.exists(definitions)) {
            parse(main.saveToString());
            Path backup = file.resolveSibling("config-v1-backup.yml");
            if (!Files.exists(backup)) Files.copy(file, backup);
            YamlConfiguration separate = new YamlConfiguration();
            separate.options().setHeader(List.of("附魔编辑区：可手动编辑，也可使用 /ew editor。", "新增 ID、名字、等级和获取途径需要重启；效果数值可重载。", "不要删除已使用的 ID；停用请设置 enabled: false。"));
            separate.set("enchantments", main.getConfigurationSection("enchantments"));
            atomicWrite(definitions, separate.saveToString());
            main.set("enchantments", null);
            main.options().setHeader(List.of("通用设置。附魔定义在 enchantments.yml；/ew editor 打开图形编辑器。"));
            atomicWrite(file, main.saveToString());
        }
        YamlConfiguration separate = yaml(Files.readString(definitions, StandardCharsets.UTF_8));
        checkKeys(separate, Set.of("enchantments"));
        main.set("enchantments", section(separate, "enchantments"));
        return main;
    }

    public static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    public static void atomicWrite(Path file, String text) throws IOException {
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), ".enchantmentworkshop-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try { Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    public static Settings parse(String content) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(content);
        checkKeys(yaml, Set.of("config-version", "settings", "enchantments"));
        if (integer(yaml, "config-version", 1, 1, 1) != 1) throw invalid("config-version", "只支持版本 1");
        ConfigurationSection general = section(yaml, "settings");
        checkKeys(general, Set.of("allow-pvp-effects", "minimum-attack-cooldown", "disabled-worlds"));
        var defs = new LinkedHashMap<String, EnchantDefinition>();
        ConfigurationSection enchants = section(yaml, "enchantments");
        for (String id : enchants.getKeys(false)) {
            if (!ID.matcher(id).matches()) throw invalid(id, "附魔 ID 必须为小写字母、数字、下划线、斜线或连字符");
            ConfigurationSection s = section(enchants, id);
            checkKeys(s, Set.of("name", "description", "enabled", "max-level", "weight", "anvil-cost", "item-tag",
                    "active-slots", "conflicts", "min-cost", "cost-per-level", "cost-span", "acquisition", "effects"));
            String tag = key(s, "item-tag", "minecraft:enchantable/mining");
            List<String> slots = strings(s, "active-slots", List.of("mainhand"));
            if (slots.isEmpty() || !SLOTS.containsAll(slots)) throw invalid(s.getCurrentPath(), "active-slots 包含未知槽位或为空");
            List<String> conflicts = strings(s, "conflicts", List.of());
            if (new HashSet<>(conflicts).size() != conflicts.size()) throw invalid(s.getCurrentPath(), "conflicts 重复");
            for (String conflict : conflicts) {
                if (!KEY.matcher(conflict).matches() || conflict.equals("enchantmentworkshop:" + id))
                    throw invalid(s.getCurrentPath(), "无效的冲突附魔键 " + conflict);
            }
            ConfigurationSection acquisition = section(s, "acquisition");
            checkKeys(acquisition, Set.of("enchanting-table", "villager-trade", "random-loot"));
            List<?> rawEffects = s.getList("effects");
            if (rawEffects == null || rawEffects.isEmpty() || rawEffects.size() > 16)
                throw invalid(s.getCurrentPath(), "effects 必须包含 1 至 16 个效果");
            List<EffectDefinition> effects = new ArrayList<>();
            for (Object raw : rawEffects) {
                if (!(raw instanceof Map<?, ?> map)) throw invalid(s.getCurrentPath(), "效果必须是 YAML 对象");
                YamlConfiguration effect = new YamlConfiguration();
                for (var e : map.entrySet()) effect.set(e.getKey().toString(), e.getValue());
                effects.add(parseEffect(yaml(effect.saveToString()), id));
            }
            defs.put(id, new EnchantDefinition(id, required(s, "name"), s.getString("description", ""),
                    bool(s, "enabled", true), integer(s, "max-level", 3, 1, 255), integer(s, "weight", 5, 1, 1024),
                    integer(s, "anvil-cost", 4, 0, 100), tag, slots, conflicts,
                    integer(s, "min-cost", 10, 1, 1000), integer(s, "cost-per-level", 10, 0, 1000),
                    integer(s, "cost-span", 20, 1, 1000), bool(acquisition, "enchanting-table", true),
                    bool(acquisition, "villager-trade", true), bool(acquisition, "random-loot", true), effects));
        }
        for (EnchantDefinition def : defs.values()) {
            for (String conflict : def.conflicts()) {
                if (conflict.startsWith("enchantmentworkshop:") && !defs.containsKey(conflict.substring("enchantmentworkshop:".length())))
                    throw invalid(def.id(), "找不到冲突附魔 " + conflict);
            }
        }
        return new Settings(bool(general, "allow-pvp-effects", true), number(general, "minimum-attack-cooldown", .85, 0, 1),
                new HashSet<>(strings(general, "disabled-worlds", List.of())), defs);
    }

    private static EffectDefinition parseEffect(ConfigurationSection s, String id) {
        checkKeys(s, Set.of("type", "value", "per-level", "chance", "chance-per-level", "cooldown-ticks", "potion",
                "duration-ticks", "amplifier", "amplifier-per-level", "trigger", "target", "action", "conditions", "condition-mode",
                "flight-durability-cost", "flight-durability-interval-ticks"));
        EffectType type;
        try { type = EffectType.valueOf(required(s, "type").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw invalid(id, "未知效果类型 " + s.getString("type")); }
        ProgramRule program = type == EffectType.PROGRAM ? ProgramRule.parse(s) : null;
        int flightCost = integer(s, "flight-durability-cost", 0, 0, 1000);
        // Legacy files may still contain this interval. Validate it, but takeoff wear has no interval.
        int flightInterval = integer(s, "flight-durability-interval-ticks", 20, 20, 1200);
        if (flightInterval % 20 != 0) throw invalid(id, "飞行耐久消耗间隔必须是 20 刻（1 秒）的整数倍");
        if (flightCost > 0 && (program == null || !program.action().equals("FLIGHT")))
            throw invalid(id, "起飞耐久消耗只适用于可编程规则的飞行动作");
        if(program==null && Set.of("trigger","target","action","conditions","condition-mode").stream().anyMatch(s::contains)) throw invalid(id,"规则字段仅用于 PROGRAM");
        boolean potionType = type == EffectType.POTION_ON_HIT || type == EffectType.PASSIVE_POTION || program != null && program.action().equals("POTION");
        String potion = potionType ? key(s, "potion", null) : "minecraft:speed";
        boolean combat = type == EffectType.PROGRAM || type == EffectType.LIFESTEAL || type == EffectType.BONUS_DAMAGE || type == EffectType.POTION_ON_HIT;
        if (!combat && (s.contains("chance") || s.contains("chance-per-level") || s.contains("cooldown-ticks")))
            throw invalid(id, "chance/cooldown-ticks 只适用于攻击效果");
        int duration = integer(s, "duration-ticks", type == EffectType.PASSIVE_POTION ? 240 : 60, 1, 72000);
        if (type == EffectType.PASSIVE_POTION && (duration < 40 || duration > 300))
            throw invalid(id, "被动药水 duration-ticks 必须在 40 至 300 之间");
        return new EffectDefinition(type, number(s, "value", 0, 0, 10), number(s, "per-level", 0, 0, 10),
                number(s, "chance", 1, 0, 1), number(s, "chance-per-level", 0, 0, 1),
                integer(s, "cooldown-ticks", 0, 0, 72000), potion, duration,
                integer(s, "amplifier", 0, 0, 255), integer(s, "amplifier-per-level", 0, 0, 255), program, flightCost);
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) throw invalid(parent.getCurrentPath() + "." + key, "缺少配置对象");
        return section;
    }

    private static void checkKeys(ConfigurationSection s, Set<String> allowed) {
        for (String key : s.getKeys(false)) if (!allowed.contains(key)) throw invalid(s.getCurrentPath() + "." + key, "未知配置项");
    }

    private static String required(ConfigurationSection s, String key) {
        if (!(s.get(key) instanceof String value) || value.isBlank() || value.length() > 256)
            throw invalid(s.getCurrentPath() + "." + key, "需要 1 至 256 字符的文本");
        return value;
    }

    private static String key(ConfigurationSection s, String key, String fallback) {
        String value = s.contains(key) ? required(s, key) : fallback;
        if (value == null || !KEY.matcher(key.equals("item-tag") && value.startsWith("@") ? value.substring(1) : value).matches()) throw invalid(key, "需要命名空间键，例如 minecraft:speed");
        return value;
    }

    private static List<String> strings(ConfigurationSection s, String key, List<String> fallback) {
        if (!s.contains(key)) return fallback;
        if (!(s.get(key) instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof String)))
            throw invalid(key, "需要字符串列表");
        return list.stream().map(Object::toString).toList();
    }

    private static boolean bool(ConfigurationSection s, String key, boolean fallback) {
        Object value = s.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean bool)) throw invalid(key, "需要 true 或 false");
        return bool;
    }

    private static double number(ConfigurationSection s, String key, double fallback, double min, double max) {
        Object value = s.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < min || number.doubleValue() > max)
            throw invalid(s.getCurrentPath() + "." + key, "数值范围为 " + min + " 至 " + max);
        return number.doubleValue();
    }

    private static int integer(ConfigurationSection s, String key, int fallback, int min, int max) {
        double value = number(s, key, fallback, min, max);
        if (value != Math.rint(value)) throw invalid(key, "需要整数");
        return (int) value;
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException(path + ": " + message);
    }
}
