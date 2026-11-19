package cn.mc.enchantmentworkshop;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class EnchantmentWorkshopPlugin extends JavaPlugin {
    private final Settings bootstrapSettings;
    private Settings settings;
    private EnchantService enchants;
    private EffectListener effects;
    private DefinitionEditor editor;
    private EditorMenu menus;
    private ProgramEngine programs;

    public EnchantmentWorkshopPlugin(Settings settings) { this.bootstrapSettings = settings; this.settings = settings; }

    @Override
    public void onEnable() {
        validateDefinitions(settings);
        editor = new DefinitionEditor(this);
        menus = new EditorMenu(this);
        programs = new ProgramEngine(this);
        getServer().getPluginManager().registerEvents(programs,this);
        getServer().getScheduler().runTaskTimer(this,programs::tick,20,20);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getScheduler().runTaskTimer(this, this::pollConfig, 20L, 20L);
        enchants = new EnchantService(this);
        effects = new EffectListener(this);
        getServer().getPluginManager().registerEvents(effects, this);
        getServer().getScheduler().runTaskTimer(this, effects::tickPassive, 20L, 20L);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("ew", "Enchantment Workshop / 附魔工坊", List.of("enchantmentworkshop"), new EnchantCommand(this)));
        getLogger().info("已启用 " + settings.enchantments().size() + " 个原生附魔，输入 /ew 查看帮助。");
    }

    public Settings settings() { return settings; }
    public EnchantService enchants() { return enchants; }
    public DefinitionEditor editor() { return editor; }
    public EditorMenu menus() { return menus; }
    public boolean canReload(Settings candidate) { return bootstrapSettings.sameRegistry(candidate); }
    @Override public void onDisable() { if (menus != null) menus.shutdown(); if(programs!=null)programs.clear(); }

    private record ConfigFiles(String general, String definitions) {}
    private ConfigFiles observed;
    private boolean pendingRestart;

    private ConfigFiles readFiles() throws java.io.IOException {
        var folder = getDataFolder().toPath();
        return new ConfigFiles(java.nio.file.Files.readString(folder.resolve("config.yml")),
                java.nio.file.Files.readString(folder.resolve("enchantments.yml")));
    }

    private void pollConfig() {
        try {
            ConfigFiles files = readFiles();
            if (!files.equals(observed)) reload(files);
        } catch (java.io.IOException unavailable) {
            // A file may be temporarily absent during an atomic replacement.
        } catch (Exception invalid) {
            getLogger().warning("自动重载失败，继续使用上次有效配置：" + invalid.getMessage());
        }
    }

    public void reloadEffects() throws Exception { reload(readFiles()); }

    private void reload(ConfigFiles files) throws Exception {
        observed = files;
        var merged = ConfigLoader.combine(ConfigLoader.yaml(files.general()), ConfigLoader.yaml(files.definitions()));
        Settings candidate = ConfigLoader.parse(merged.saveToString());
        validateDefinitions(candidate);
        applyRuntime(bootstrapSettings.liveOverlay(candidate));
        pendingRestart = !canReload(candidate);
    }

    String reloadStatus() {
        return pendingRestart ? "现有效果已加载，原生注册变更需完整重启。" : "已保存并生效。";
    }

    void applyRuntime(Settings candidate) { if(programs!=null)programs.clear(); settings = candidate; effects.resetCooldowns(); }

    public static void validateDefinitions(Settings settings) {
        for (EnchantDefinition def : settings.enchantments().values()) {
            var items = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ITEM);
            if (def.itemTag().startsWith("@") ? items.get(NamespacedKey.fromString(def.itemTag().substring(1)))==null : !items.hasTag(io.papermc.paper.registry.tag.TagKey.create(io.papermc.paper.registry.RegistryKey.ITEM, net.kyori.adventure.key.Key.key(def.itemTag()))))
                throw new IllegalArgumentException(def.id() + ": 未知物品标签 " + def.itemTag());
            var enchants = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
            for (String key : def.conflicts()) {
                if (key.startsWith("enchantmentworkshop:") && settings.enchantments().containsKey(key.substring("enchantmentworkshop:".length()))) continue;
                if (enchants.get(NamespacedKey.fromString(key)) == null) throw new IllegalArgumentException("未知冲突附魔：" + key);
            }
            for (EffectDefinition effect : def.effects()) {
                if ((effect.type() == EffectType.PASSIVE_POTION || effect.type() == EffectType.POTION_ON_HIT || effect.program()!=null && effect.program().action().equals("POTION"))
                        && Registry.POTION_EFFECT_TYPE.get(NamespacedKey.fromString(effect.potion())) == null)
                    throw new IllegalArgumentException(def.id() + ": 未知药水效果 " + effect.potion());
            }
        }
    }
}
