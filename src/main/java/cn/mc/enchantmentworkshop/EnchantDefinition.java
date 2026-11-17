package cn.mc.enchantmentworkshop;

import java.util.List;

public record EnchantDefinition(String id, String name, String description, boolean enabled,
                                int maxLevel, int weight, int anvilCost, String itemTag,
                                List<String> activeSlots, List<String> conflicts,
                                int minCost, int costPerLevel, int costSpan,
                                boolean enchantingTable, boolean villagerTrade, boolean randomLoot,
                                List<EffectDefinition> effects) {
    public EnchantDefinition {
        activeSlots = List.copyOf(activeSlots);
        conflicts = List.copyOf(conflicts);
        effects = List.copyOf(effects);
    }

    // Runtime effects/descriptions may change; everything Minecraft caches must wait for a restart.
    public List<Object> registryIdentity() {
        return List.of(id, name, enabled, maxLevel, weight, anvilCost, itemTag, activeSlots, conflicts,
                minCost, costPerLevel, costSpan, enchantingTable, villagerTrade, randomLoot);
    }
}
