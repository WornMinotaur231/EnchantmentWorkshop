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
}
