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

    public void reloadEffects() throws Exception {
        Settings candidate = ConfigLoader.load(getDataFolder().toPath().resolve("config.yml"));
        validateDefinitions(candidate);
        if (!bootstrapSettings.sameRegistry(candidate))
            throw new IllegalArgumentException("附魔注册信息已改变（ID、名字、启用状态、等级、适用物品或获取途径等），请完整重启服务器；当前配置未被替换。");
        applyRuntime(candidate);
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
