package cn.mc.enchantmentworkshop;

public record EffectDefinition(EffectType type, double value, double perLevel,
                               double chance, double chancePerLevel, int cooldownTicks,
                               String potion, int durationTicks, int amplifier, int amplifierPerLevel, ProgramRule program,
                               int flightDurabilityCost) {
    public double valueAt(int level) { return value + perLevel * (level - 1); }
    public double chanceAt(int level) { return Math.clamp(chance + chancePerLevel * (level - 1), 0, 1); }
    public int amplifierAt(int level) { return Math.clamp(amplifier + amplifierPerLevel * (level - 1), 0, 255); }
}
