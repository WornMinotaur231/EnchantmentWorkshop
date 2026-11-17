package cn.mc.enchantmentworkshop;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class EffectListener implements Listener {
    private static final EquipmentSlot[] PLAYER_SLOTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    // Only ore-derived items: never accidentally smelt containers, player metadata, or arbitrary blocks.
    private static final Map<Material, Material> SMELTING = Map.of(Material.RAW_IRON, Material.IRON_INGOT,
            Material.RAW_GOLD, Material.GOLD_INGOT, Material.RAW_COPPER, Material.COPPER_INGOT,
            Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP);
    private final EnchantmentWorkshopPlugin plugin;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, AttackCharge> attackCharges = new HashMap<>();
    private final Map<EntityDamageByEntityEvent, List<Activation>> pendingAttacks = new IdentityHashMap<>();

    private record Activation(String key, EffectDefinition effect, int level) {}
    private record AttackCharge(UUID target, int tick, float charge) {}

    public EffectListener(EnchantmentWorkshopPlugin plugin) { this.plugin = plugin; }
    public void resetCooldowns() { cooldowns.clear(); attackCharges.clear(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void beforeAttack(PrePlayerAttackEntityEvent event) {
        if (event.willAttack()) attackCharges.put(event.getPlayer().getUniqueId(), new AttackCharge(
                event.getAttacked().getUniqueId(), Bukkit.getCurrentTick(), event.getPlayer().getAttackCooldown()));
    }

    private float attackCharge(Player player, LivingEntity target) {
        // Vanilla resets the cooldown BEFORE EntityDamageByEntityEvent fires.
        AttackCharge saved = attackCharges.get(player.getUniqueId());
        return saved != null && saved.tick() == Bukkit.getCurrentTick() && saved.target().equals(target.getUniqueId())
                ? saved.charge() : player.getAttackCooldown();
    }

    private boolean active(Player player) {
        return player.getGameMode() != GameMode.SPECTATOR && !player.isDead()
                && !plugin.settings().disabledWorlds().contains(player.getWorld().getName());
    }

    private boolean slotActive(EnchantDefinition def, EquipmentSlot slot) {
        return def.activeSlots().stream().anyMatch(group -> EnchantmentWorkshopBootstrap.slot(group).test(slot));
    }

    private List<Activation> effects(ItemStack item, EquipmentSlot slot) {
        var result = new ArrayList<Activation>();
        for (EnchantDefinition def : plugin.settings().enchantments().values()) {
            if (!slotActive(def, slot)) continue;
            int level = plugin.enchants().level(item, def);
            if (level <= 0) continue;
            for (int i = 0; i < def.effects().size(); i++) result.add(new Activation(def.id() + ":" + i, def.effects().get(i), level));
        }
        return result;
    }

    private List<Activation> handEffects(Player player) {
        return effects(player.getInventory().getItemInMainHand(), EquipmentSlot.HAND);
    }

    private boolean roll(Player player, Activation a) {
        long until = cooldowns.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(a.key(), 0L);
        return System.nanoTime() >= until && ThreadLocalRandom.current().nextDouble() < a.effect().chanceAt(a.level());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !active(player)
                || !(event.getEntity() instanceof LivingEntity target) || target instanceof ArmorStand
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getFinalDamage() <= 0 || target.isInvulnerable()
                || attackCharge(player, target) < plugin.settings().minimumAttackCooldown()
                || (target instanceof Player && !plugin.settings().pvpEffects())) return;
        var activations = new ArrayList<Activation>();
        double bonus = 0;
        for (Activation a : handEffects(player)) {
            EffectType type = a.effect().type();
            if ((type == EffectType.BONUS_DAMAGE || type == EffectType.LIFESTEAL || type == EffectType.POTION_ON_HIT) && roll(player, a)) {
                activations.add(a);
                if (type == EffectType.BONUS_DAMAGE) bonus += a.effect().valueAt(a.level());
            }
        }
        if (bonus > 0) event.setDamage(event.getDamage() * (1 + Math.min(bonus, 10)));
        if (!activations.isEmpty()) pendingAttacks.put(event, activations);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDefense(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !active(player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                && event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE) return;
        boolean playerAttack = event.getDamager() instanceof Player
                || event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player;
        if (playerAttack && !plugin.settings().pvpEffects()) return;
        double reduction = 0;
        for (EquipmentSlot slot : PLAYER_SLOTS) {
            for (Activation a : effects(player.getInventory().getItem(slot), slot)) {
                if (a.effect().type() == EffectType.DAMAGE_REDUCTION) reduction += a.effect().valueAt(a.level());
            }
        }
        if (reduction > 0) event.setDamage(event.getDamage() * (1 - Math.min(.8, reduction)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttackResolved(EntityDamageByEntityEvent event) {
        List<Activation> activations = pendingAttacks.remove(event);
        if (activations == null || event.isCancelled() || event.getFinalDamage() <= 0) return;
        Player player = (Player) event.getDamager();
        LivingEntity target = (LivingEntity) event.getEntity();
        double actualDamage = Math.min(event.getFinalDamage(), target.getHealth());
        long now = System.nanoTime();
        double healing = 0;
        for (Activation a : activations) {
            if (a.effect().cooldownTicks() > 0) cooldowns.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>())
                    .put(a.key(), now + a.effect().cooldownTicks() * 50_000_000L);
            if (a.effect().type() == EffectType.LIFESTEAL) healing += actualDamage * Math.min(1, a.effect().valueAt(a.level()));
        }
        double healAmount = Math.min(actualDamage, healing);
        // Run after the hit completes. A cancelled/blocked hit never heals or applies a debuff.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !player.isOnline() || !active(player)) return;
            if (healAmount > 0) heal(player, healAmount);
            if (!target.isValid() || target.isDead() || target.getWorld() != player.getWorld()) return;
            for (Activation a : activations) {
                if (a.effect().type() == EffectType.POTION_ON_HIT) target.addPotionEffect(potion(a));
            }
        });
    }

    private static void heal(Player player, double amount) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null || player.getHealth() >= attribute.getValue()) return;
        EntityRegainHealthEvent event = new EntityRegainHealthEvent(player, amount, EntityRegainHealthEvent.RegainReason.CUSTOM);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled() && Double.isFinite(event.getAmount()) && event.getAmount() > 0 && !player.isDead())
            player.setHealth(Math.min(attribute.getValue(), player.getHealth() + event.getAmount()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!active(player) || player.getGameMode() == GameMode.CREATIVE || event.getExpToDrop() <= 0) return;
        event.setExpToDrop(boostExperience(event.getExpToDrop(), handEffects(player)));
    }

    private static int boostExperience(int base, List<Activation> effects) {
        double bonus = effects.stream().filter(a -> a.effect().type() == EffectType.EXPERIENCE_BOOST)
                .mapToDouble(a -> a.effect().valueAt(a.level())).sum();
        if (base <= 0 || bonus <= 0) return base;
        double extra = base * Math.min(bonus, 10);
        // Unbiased rounding: a 20% bonus on a 1 XP ore must not turn into 100%.
        long rounded = (long) Math.floor(extra) + (ThreadLocalRandom.current().nextDouble() < extra % 1 ? 1 : 0);
        return (int) Math.min(Integer.MAX_VALUE, base + rounded);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !active(killer) || event.getEntity() instanceof Player || killer.getGameMode() == GameMode.CREATIVE) return;
        event.setDroppedExp(boostExperience(event.getDroppedExp(), handEffects(killer)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (!active(player) || player.getGameMode() == GameMode.CREATIVE) return;
        List<Activation> effects = handEffects(player);
        boolean smelt = effects.stream().anyMatch(a -> a.effect().type() == EffectType.AUTO_SMELT)
                && !player.getInventory().getItemInMainHand().containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH);
        boolean collect = effects.stream().anyMatch(a -> a.effect().type() == EffectType.TELEKINESIS);
        if (!smelt && !collect) return;
        // Keep the actual drop entities. Other plugins may still cancel this event at a later priority.
        if (smelt) {
            for (Item item : event.getItems()) {
                ItemStack stack = item.getItemStack();
                Material output = SMELTING.get(stack.getType());
                if (output != null && !stack.hasItemMeta()) item.setItemStack(new ItemStack(output, stack.getAmount()));
            }
        }
        if (collect) {
            List<Item> drops = List.copyOf(event.getItems());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (event.isCancelled() || !player.isOnline() || !active(player)) return;
                for (Item item : drops) {
                    if (!event.getItems().contains(item) || !item.isValid() || item.isDead()
                            || item.getWorld() != player.getWorld()
                            || item.getLocation().distanceSquared(player.getLocation()) > 64) continue;
                    // Inventory leftovers remain on the original entity; no duplicate world drops.
                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.getItemStack().clone());
                    if (leftover.isEmpty()) item.remove();
                    else item.setItemStack(leftover.values().iterator().next());
                }
            });
        }
    }

    public void tickPassive() {
        for (Player player : Bukkit.getOnlinePlayers()) refreshPassive(player);
    }

    public void refreshPassive(Player player) {
        if (!active(player)) return;
        Map<PotionEffectType, PotionEffect> desired = new HashMap<>();
        for (EquipmentSlot slot : PLAYER_SLOTS) {
            for (Activation a : effects(player.getInventory().getItem(slot), slot)) {
                if (a.effect().type() != EffectType.PASSIVE_POTION) continue;
                PotionEffect effect = potion(a);
                desired.merge(effect.getType(), effect, (old, next) -> next.getAmplifier() > old.getAmplifier()
                        || next.getAmplifier() == old.getAmplifier() && next.getDuration() > old.getDuration() ? next : old);
            }
        }
        desired.values().forEach(player::addPotionEffect);
        // Do not remove a potion by type on unequip: that could erase another plugin's or a potion's effect.
    }

    private static PotionEffect potion(Activation a) {
        PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.fromString(a.effect().potion()));
        return new PotionEffect(java.util.Objects.requireNonNull(type), a.effect().durationTicks(),
                a.effect().amplifierAt(a.level()), true, false, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
        attackCharges.remove(event.getPlayer().getUniqueId());
    }
}
