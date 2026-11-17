package cn.mc.enchantmentworkshop;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Both commands and GUI edit the same validated, versioned document. */
public final class DefinitionEditor {
    public static final List<String> FIELDS = List.of("name", "description", "enabled", "max-level", "weight", "anvil-cost",
            "item-tag", "active-slots", "conflicts", "min-cost", "cost-per-level", "cost-span",
            "acquisition.enchanting-table", "acquisition.villager-trade", "acquisition.random-loot");
    public static final List<String> EFFECT_FIELDS = List.of("value", "per-level", "chance", "chance-per-level", "cooldown-ticks",
            "potion", "duration-ticks", "amplifier", "amplifier-per-level", "trigger", "target", "action", "condition-mode",
            "flight-durability-cost", "conditions");
    private final EnchantmentWorkshopPlugin plugin;
    private final Path config;
    public record Draft(YamlConfiguration yaml, String revision) {}
    public record Deletion(String id, String name, List<String> references, Draft draft) {}
    private record PendingDeletion(Deletion deletion, long expires) {}
    private final Map<String, PendingDeletion> pendingDeletions = new HashMap<>();

    /** Read a fresh snapshot: deleting an entry must not save unrelated GUI edits. */
    public Deletion prepareDelete(String id) throws Exception {
        Draft draft = read();
        String root = path(draft, id);
        List<String> references = ids(draft).stream().filter(other -> !other.equals(id))
                .filter(other -> draft.yaml().getStringList("enchantments." + other + ".conflicts").contains("enchantmentworkshop:" + id)).toList();
        return new Deletion(id, draft.yaml().getString(root + ".name", id), references, draft);
    }
    public String delete(Deletion deletion) throws Exception {
        Draft draft = deletion.draft();
        path(draft, deletion.id());
        for (String other : deletion.references()) {
            String key = "enchantments." + other + ".conflicts";
            var conflicts = new ArrayList<>(draft.yaml().getStringList(key));
            conflicts.removeIf(value -> value.equals("enchantmentworkshop:" + deletion.id()));
            draft.yaml().set(key, conflicts);
        }
        draft.yaml().set("enchantments." + deletion.id(), null);
        return "已删除配置中的「" + deletion.name() + "」（" + deletion.id() + "），清理 " + deletion.references().size() + " 处冲突引用。" + save(draft);
    }
    public Deletion requestDelete(String sender, String id) throws Exception {
        pendingDeletions.entrySet().removeIf(entry -> entry.getValue().expires() < System.currentTimeMillis());
        Deletion deletion = prepareDelete(id);
        pendingDeletions.put(sender, new PendingDeletion(deletion, System.currentTimeMillis() + 120_000));
        return deletion;
    }
    public String confirmDelete(String sender, String id) throws Exception {
        PendingDeletion pending = pendingDeletions.remove(sender);
        if (pending == null || pending.expires() < System.currentTimeMillis() || !pending.deletion().id().equals(id))
            throw new IllegalArgumentException("请先使用 /ew delete " + id + " 预览删除，并在 2 分钟内确认。");
        return delete(pending.deletion());
    }

    public DefinitionEditor(EnchantmentWorkshopPlugin plugin) {
        this.plugin = plugin;
        config = plugin.getDataFolder().toPath().resolve("config.yml");
    }
    public Draft read() throws Exception {
        var yaml = ConfigLoader.document(config);
        return new Draft(yaml, yaml.saveToString());
    }
    public Settings settings(Draft draft) throws Exception { return ConfigLoader.parse(draft.yaml().saveToString()); }
    public List<String> ids(Draft draft) { return new ArrayList<>(draft.yaml().getConfigurationSection("enchantments").getKeys(false)); }
    public String state(EnchantDefinition saved) {
        return saved.equals(plugin.settings().enchantments().get(saved.id())) ? "已生效" : "待重启 / 尚未应用";
    }
    public List<String> templates() throws Exception {
        return new ArrayList<>(defaults().getConfigurationSection("enchantments").getKeys(false));
    }
    private YamlConfiguration defaults() throws Exception {
        try (var in = DefinitionEditor.class.getResourceAsStream("/config.yml")) {
            return ConfigLoader.yaml(new String(Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public void create(Draft draft, String id, String template, String name, boolean copy) throws Exception {
        if (!id.matches("[a-z0-9_][a-z0-9_/-]*")) throw new IllegalArgumentException("ID 只能用小写字母、数字、下划线、斜线和连字符。");
        if (ids(draft).contains(id)) throw new IllegalArgumentException("ID 已存在：" + id);
        var origin = (copy ? draft.yaml() : defaults()).getConfigurationSection("enchantments." + template);
        if (origin == null) throw new IllegalArgumentException("未知模板/源附魔：" + template);
        // Round-trip the source to detach nested sections, lists and maps from the original.
        YamlConfiguration temp = new YamlConfiguration(); temp.set("entry", origin);
        var detached = ConfigLoader.yaml(temp.saveToString()).getConfigurationSection("entry");
        draft.yaml().set("enchantments." + id, detached);
        draft.yaml().set("enchantments." + id + ".name", name.isBlank() ? "自定义附魔 " + id : name);
    }
    public static Object value(String field, String text) {
        if (List.of("active-slots", "conflicts").contains(field))
            return text.equals("[]") || text.isBlank() ? List.of() : Arrays.stream(text.split(",")).map(String::trim).toList();
        if (field.equals("enabled") || field.startsWith("acquisition.")) {
            if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) throw new IllegalArgumentException("请输入 true 或 false。");
            return Boolean.parseBoolean(text);
        }
        if (List.of("name", "description", "item-tag", "potion", "trigger", "target", "action", "condition-mode").contains(field)) return text;
        try {
            double n = Double.parseDouble(text);
            if (!Double.isFinite(n)) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("请输入有限数字。"); }
    }
    private String path(Draft draft, String id) {
        if (!ids(draft).contains(id)) throw new IllegalArgumentException("未知附魔：" + id);
        return "enchantments." + id;
    }
    public void set(Draft draft, String id, String field, String text) {
        if (!FIELDS.contains(field)) throw new IllegalArgumentException("未知字段；可用字段：" + String.join(", ", FIELDS));
        draft.yaml().set(path(draft, id) + "." + field, value(field, text));
    }
    public List<Map<?, ?>> effects(Draft draft, String id) { return new ArrayList<>(draft.yaml().getMapList(path(draft, id) + ".effects")); }
    public static Map<String, Object> effect(EffectType type) {
        Map<String, Object> effect = new LinkedHashMap<>(); effect.put("type", type.name());
        switch (type) {
            case PROGRAM -> {effect.put("trigger","ATTACK");effect.put("target","SELF");effect.put("action","POTION");effect.put("potion","minecraft:speed");effect.put("duration-ticks",100);effect.put("cooldown-ticks",100);effect.put("value",1);}
            case LIFESTEAL -> { effect.put("value", .1); effect.put("chance", .3); effect.put("cooldown-ticks", 20); }
            case BONUS_DAMAGE -> { effect.put("value", .3); effect.put("chance", .2); effect.put("cooldown-ticks", 20); }
            case DAMAGE_REDUCTION -> effect.put("value", .04);
            case EXPERIENCE_BOOST -> effect.put("value", .2);
            case POTION_ON_HIT, PASSIVE_POTION -> {
                effect.put("potion", type == EffectType.PASSIVE_POTION ? "minecraft:speed" : "minecraft:slowness");
                effect.put("duration-ticks", type == EffectType.PASSIVE_POTION ? 60 : 80);
                effect.put("amplifier", 0);
            }
            default -> { }
        }
        return effect;
    }
    public void addEffect(Draft draft, String id, EffectType type) {
        var effects = effects(draft, id);
        if (effects.size() >= 16) throw new IllegalArgumentException("最多 16 个效果。");
        effects.add(effect(type)); draft.yaml().set(path(draft, id) + ".effects", effects);
    }
    public void removeEffect(Draft draft, String id, int index) {
        var effects = effects(draft, id);
        if (effects.size() <= 1) throw new IllegalArgumentException("至少保留一个效果。");
        requireIndex(effects, index); effects.remove(index);
        draft.yaml().set(path(draft, id) + ".effects", effects);
    }
    public void setEffect(Draft draft, String id, int index, String field, String text) {
        if (!EFFECT_FIELDS.contains(field) && !(field.startsWith("conditions.") && ProgramRule.CONDITIONS.contains(field.substring(11)))) throw new IllegalArgumentException("未知效果字段。");
        var effects = effects(draft, id); requireIndex(effects, index);
        Map<Object, Object> effect = new LinkedHashMap<>(effects.get(index));
        if (field.equals("conditions")) {
            if (!List.of("none", "无条件", "default").contains(text)) throw new IllegalArgumentException("使用 conditions none 设置无条件。");
            effect.put("conditions", Map.of());
        } else if(field.startsWith("conditions.")) {
            String condition=field.substring(11); Map<String,Object> conditions=new LinkedHashMap<>();
            if(effect.get("conditions") instanceof Map<?,?> old)old.forEach((k,v)->conditions.put(k.toString(),v));
            if(text.equals("default"))conditions.remove(condition);else{ProgramRule.validate(condition,text);conditions.put(condition,text);}effect.put("conditions",conditions);
        } else if (text.equals("default")) {
            Object fallback = effect(EffectType.valueOf(effect.get("type").toString())).get(field);
            if (fallback == null) effect.remove(field); else effect.put(field, fallback);
        } else effect.put(field, value(field, text));
        effects.set(index, effect); draft.yaml().set(path(draft, id) + ".effects", effects);
    }
    private static void requireIndex(List<?> list, int index) {
        if (index < 0 || index >= list.size()) throw new IllegalArgumentException("效果序号范围为 1 至 " + list.size());
    }
    public String save(Draft draft) throws Exception {
        if (!read().revision().equals(draft.revision())) throw new IllegalArgumentException("配置已被别人或文件编辑器修改，请重新打开编辑器再修改，避免覆盖。");
        Settings candidate = settings(draft);
        EnchantmentWorkshopPlugin.validateDefinitions(candidate);
        Path definitions = config.resolveSibling("enchantments.yml");
        YamlConfiguration output = ConfigLoader.yaml(Files.readString(definitions));
        output.set("enchantments", draft.yaml().getConfigurationSection("enchantments"));
        Files.copy(definitions, definitions.resolveSibling("enchantments.yml.bak"), StandardCopyOption.REPLACE_EXISTING);
        ConfigLoader.atomicWrite(definitions, output.saveToString());
        if (plugin.canReload(candidate)) {
            plugin.applyRuntime(candidate);
            return "已保存并生效。";
        }
        return "已保存，完整重启服务器后生效。原生附魔的新增、删除和注册属性变更不能热重载。";
    }
}
