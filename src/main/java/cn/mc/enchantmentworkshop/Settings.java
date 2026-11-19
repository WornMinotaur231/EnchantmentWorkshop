package cn.mc.enchantmentworkshop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record Settings(boolean pvpEffects, double minimumAttackCooldown, Set<String> disabledWorlds,
                       Map<String, EnchantDefinition> enchantments) {
    public Settings {
        disabledWorlds = Set.copyOf(disabledWorlds);
        enchantments = Collections.unmodifiableMap(new LinkedHashMap<>(enchantments));
    }

    public boolean sameRegistry(Settings other) {
        return enchantments.keySet().equals(other.enchantments.keySet()) && enchantments.entrySet().stream()
                .allMatch(e -> e.getValue().registryIdentity().equals(other.enchantments.get(e.getKey()).registryIdentity()));
    }
    /** Preserve the frozen native registry while applying runtime changes for existing IDs. */
    public Settings liveOverlay(Settings candidate) {
        var live=new LinkedHashMap<String,EnchantDefinition>();
        enchantments.forEach((id,base)->{
            var next=candidate.enchantments.get(id);
            if(next!=null)live.put(id,new EnchantDefinition(id,base.name(),next.description(),next.enabled(),base.maxLevel(),base.weight(),base.anvilCost(),base.itemTag(),base.activeSlots(),base.conflicts(),base.minCost(),base.costPerLevel(),base.costSpan(),base.enchantingTable(),base.villagerTrade(),base.randomLoot(),next.effects()));
        });
        return new Settings(candidate.pvpEffects,candidate.minimumAttackCooldown,candidate.disabledWorlds,live);
    }
}
