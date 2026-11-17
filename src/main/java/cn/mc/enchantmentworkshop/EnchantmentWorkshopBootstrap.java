package cn.mc.enchantmentworkshop;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.set.RegistrySet;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public final class EnchantmentWorkshopBootstrap implements PluginBootstrap {
    private Settings settings;

    @Override
    public void bootstrap(BootstrapContext context) {
        try { settings = ConfigLoader.load(context.getDataDirectory().resolve("config.yml")); }
        catch (Exception ex) { throw new IllegalStateException("EnchantmentWorkshop 配置无效，未注册附魔。请修正 config.yml。", ex); }
        context.getLifecycleManager().registerEventHandler(RegistryEvents.ENCHANTMENT.compose(), event -> {
            for (EnchantDefinition d : settings.enchantments().values()) {
                // Disabled entries stay registered to preserve existing items across restarts.
                event.registry().register(typedKey(d.id()), b -> b.description(Component.text(d.name()))
                        .supportedItems(d.itemTag().startsWith("@") ? RegistrySet.keySet(RegistryKey.ITEM, TypedKey.create(RegistryKey.ITEM,Key.key(d.itemTag().substring(1)))) : event.getOrCreateTag(TagKey.create(RegistryKey.ITEM, Key.key(d.itemTag()))))
                        .maxLevel(d.maxLevel()).weight(d.weight()).anvilCost(d.anvilCost())
                        .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(d.minCost(), d.costPerLevel()))
                        .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(d.minCost() + d.costSpan(), d.costPerLevel()))
                        .activeSlots(d.activeSlots().stream().map(EnchantmentWorkshopBootstrap::slot).toList())
                        .exclusiveWith(RegistrySet.keySet(RegistryKey.ENCHANTMENT, d.conflicts().stream()
                                .map(key -> TypedKey.create(RegistryKey.ENCHANTMENT, Key.key(key))).toList())));
            }
        });
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.postFlatten(RegistryKey.ENCHANTMENT), event -> {
            for (EnchantDefinition d : settings.enchantments().values()) {
                if (!d.enabled()) continue;
                List<TypedKey<Enchantment>> keys = List.of(typedKey(d.id()));
                if (d.enchantingTable()) event.registrar().addToTag(tag("in_enchanting_table"), keys);
                if (d.villagerTrade()) {
                    event.registrar().addToTag(tag("tradeable"), keys);
                    event.registrar().addToTag(tag("on_traded_equipment"), keys);
                }
                if (d.randomLoot()) {
                    event.registrar().addToTag(tag("on_random_loot"), keys);
                    event.registrar().addToTag(tag("on_mob_spawn_equipment"), keys);
                }
                event.registrar().addToTag(tag("non_treasure"), keys);
            }
        });
        context.getLogger().info("EnchantmentWorkshop: 已准备 {} 个原生附魔。", settings.enchantments().size());
    }

    public static TypedKey<Enchantment> typedKey(String id) {
        return TypedKey.create(RegistryKey.ENCHANTMENT, Key.key("enchantmentworkshop", id));
    }

    private static TagKey<Enchantment> tag(String name) {
        return TagKey.create(RegistryKey.ENCHANTMENT, Key.key("minecraft", name));
    }

    public static EquipmentSlotGroup slot(String name) {
        return switch (name) {
            case "mainhand" -> EquipmentSlotGroup.MAINHAND;
            case "offhand" -> EquipmentSlotGroup.OFFHAND;
            case "hand" -> EquipmentSlotGroup.HAND;
            case "head" -> EquipmentSlotGroup.HEAD;
            case "chest" -> EquipmentSlotGroup.CHEST;
            case "legs" -> EquipmentSlotGroup.LEGS;
            case "feet" -> EquipmentSlotGroup.FEET;
            case "armor" -> EquipmentSlotGroup.ARMOR;
            case "any" -> EquipmentSlotGroup.ANY;
            default -> throw new IllegalArgumentException("Unknown slot: " + name);
        };
    }

    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) { return new EnchantmentWorkshopPlugin(settings); }
}
