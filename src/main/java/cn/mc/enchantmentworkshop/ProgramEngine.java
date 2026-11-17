package cn.mc.enchantmentworkshop;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import java.util.*;

public final class ProgramEngine implements Listener {
    private final EnchantmentWorkshopPlugin plugin;
    private final Map<UUID,Map<String,Long>> cooldowns=new HashMap<>();
    private record FlightGrant(String id, int index, EquipmentSlot slot) {
        boolean same(String id, int index, EquipmentSlot slot) { return this.id.equals(id) && this.index==index && this.slot==slot; }
    }
    private record Takeoff(PlayerToggleFlightEvent event, FlightGrant grant) {}
    private static final List<EquipmentSlot> SLOTS=List.of(EquipmentSlot.HAND,EquipmentSlot.OFF_HAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET);
    private final Map<UUID,FlightGrant> flights=new HashMap<>();
    private final Map<UUID,Takeoff> takeoffs=new HashMap<>();
    private final NamespacedKey flightKey;
    private boolean executing;
    // Keep the old persistent key so flight ownership from previous versions is cleaned on join.
    public ProgramEngine(EnchantmentWorkshopPlugin p){plugin=p;flightKey=new NamespacedKey("enchantmentworkshop","temporary_flight");}
    private boolean enabled(Player p){return !p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR&&!plugin.settings().disabledWorlds().contains(p.getWorld().getName());}
    public void fire(Player p, LivingEntity other, String trigger){
        if(executing||!enabled(p))return;
        if(other instanceof Player && !plugin.settings().pvpEffects())return;
        Set<String> seen=new HashSet<>();
        for(EquipmentSlot slot:SLOTS) {
            for(var def:plugin.settings().enchantments().values()) {
                if(def.activeSlots().stream().noneMatch(s->EnchantmentWorkshopBootstrap.slot(s).test(slot)))continue;
                int level=plugin.enchants().level(p.getInventory().getItem(slot),def); if(level==0)continue;
                for(int i=0;i<def.effects().size();i++) {
                    var effect=def.effects().get(i); var rule=effect.program(); String key=def.id()+":"+i;
                    if(rule==null||!rule.trigger().equals(trigger)||!seen.add(key)||!rule.matches(p,other))continue;
                    LivingEntity target=rule.target().equals("SELF")?p:other;
                    if(target==null||target.isDead()||target.getWorld()!=p.getWorld()||target.getLocation().distanceSquared(p.getLocation())>4096)continue;
                    long now=System.nanoTime(); var map=cooldowns.computeIfAbsent(p.getUniqueId(),x->new HashMap<>());
                    if(now<map.getOrDefault(key,Long.MIN_VALUE)||Math.random()>=effect.chanceAt(level))continue;
                    map.put(key,now+Math.max(20,effect.cooldownTicks())*50_000_000L);
                    executing=true;
                    try{apply(p,target,effect,level,def.id(),i,slot);} finally{executing=false;}
                }
            }
        }
    }
    private void apply(Player owner,LivingEntity target,EffectDefinition e,int level,String id,int index,EquipmentSlot slot){
        switch(e.program().action()) {
            case "POTION" -> target.addPotionEffect(new PotionEffect(Objects.requireNonNull(Registry.POTION_EFFECT_TYPE.get(NamespacedKey.fromString(e.potion()))),Math.min(12000,e.durationTicks()),e.amplifierAt(level)));
            case "HEAL" -> {var event=new EntityRegainHealthEvent(target,Math.min(20,e.valueAt(level)),EntityRegainHealthEvent.RegainReason.CUSTOM); Bukkit.getPluginManager().callEvent(event); if(!event.isCancelled()&&Double.isFinite(event.getAmount())&&event.getAmount()>0)target.setHealth(Math.min(target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(),target.getHealth()+event.getAmount()));}
            case "IGNITE" -> {var event=new EntityCombustByEntityEvent(owner,target,Math.min(30,e.durationTicks()/20f)); Bukkit.getPluginManager().callEvent(event); if(!event.isCancelled())target.setFireTicks((int)(event.getDuration()*20));}
            case "EXPLOSION" -> {if(!plugin.settings().pvpEffects())return; target.getWorld().createExplosion(target.getLocation(),(float)Math.clamp(e.valueAt(level),0,4),false,false,owner);}
            case "LAUNCH" -> target.setVelocity(target.getVelocity().setY(Math.clamp(e.valueAt(level),0,2)));
            case "FLIGHT" -> {
                if(owner.getGameMode()!=GameMode.SURVIVAL&&owner.getGameMode()!=GameMode.ADVENTURE)return;
                if(owner.getAllowFlight()&&!flights.containsKey(owner.getUniqueId()))return;
                if(!damageable(owner.getInventory().getItem(slot)))return;
                FlightGrant current=flights.get(owner.getUniqueId());
                // A grant has no timer; only its source equipment and conditions determine its lifetime.
                if(current!=null && !current.same(id,index,slot))return;
                if(current==null)flights.put(owner.getUniqueId(),new FlightGrant(id,index,slot));
                owner.getPersistentDataContainer().set(flightKey,PersistentDataType.BYTE,(byte)1);
                owner.setAllowFlight(true);
            }
            default -> throw new IllegalStateException("Unknown action");
        }
    }
    private void revoke(Player p){
        flights.remove(p.getUniqueId());
        takeoffs.remove(p.getUniqueId());
        if(!p.getPersistentDataContainer().has(flightKey))return;
        p.getPersistentDataContainer().remove(flightKey);
        if(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE){p.setFlying(false);p.setAllowFlight(false);p.setFallDistance(0);p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,100,0));}
    }
    private static boolean damageable(org.bukkit.inventory.ItemStack item) {
        if(item.isEmpty() || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable meta))return false;
        int maximum=meta.hasMaxDamage()?meta.getMaxDamage():item.getType().getMaxDurability();
        return maximum>0 && meta.getDamage()<maximum;
    }
    private EffectDefinition flightEffect(Player p, FlightGrant lease) {
        if(!enabled(p) || (p.getGameMode()!=GameMode.SURVIVAL && p.getGameMode()!=GameMode.ADVENTURE))return null;
        var def=plugin.settings().enchantments().get(lease.id);
        if(def==null || lease.index>=def.effects().size() || def.activeSlots().stream().noneMatch(s->EnchantmentWorkshopBootstrap.slot(s).test(lease.slot))
                || plugin.enchants().level(p.getInventory().getItem(lease.slot),def)==0)return null;
        var e=def.effects().get(lease.index); var rule=e.program();
        if(rule==null || !rule.action().equals("FLIGHT"))return null;
        var conditions=new HashMap<>(rule.conditions()); conditions.remove("target-type");
        if(!new ProgramRule(rule.trigger(),rule.target(),rule.action(),rule.any(),conditions).matches(p,null))return null;
        if(!damageable(p.getInventory().getItem(lease.slot)))return null;
        return e;
    }
    /** Recheck the source every second; hovering and repeated rule triggers never incur wear. */
    private void tickPlayer(Player p) {
        FlightGrant grant=flights.get(p.getUniqueId());
        if(grant!=null && flightEffect(p,grant)==null)revoke(p);
        fire(p,null,"PERIODIC");
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void toggleFlight(PlayerToggleFlightEvent event) {
        Player p=event.getPlayer(); FlightGrant grant=flights.get(p.getUniqueId());
        if(grant==null || !event.isFlying() || p.isFlying())return;
        if(flightEffect(p,grant)==null){event.setCancelled(true);revoke(p);return;}
        takeoffs.put(p.getUniqueId(),new Takeoff(event,grant));
        // Wait for later listeners and the server's actual state transition before charging.
        Bukkit.getScheduler().runTask(plugin,()->finishTakeoff(event));
    }
    private void finishTakeoff(PlayerToggleFlightEvent event) {
        Player p=event.getPlayer();Takeoff pending=takeoffs.get(p.getUniqueId());
        if(pending==null || pending.event()!=event)return;
        takeoffs.remove(p.getUniqueId());
        if(event.isCancelled() || !p.isOnline() || !p.isFlying() || flights.get(p.getUniqueId())!=pending.grant())return;
        var effect=flightEffect(p,pending.grant());
        if(effect==null){revoke(p);return;}
        if(effect.flightDurabilityCost()>0)p.damageItemStack(pending.grant().slot(),effect.flightDurabilityCost());
        if(flights.get(p.getUniqueId())==pending.grant() && flightEffect(p,pending.grant())==null)revoke(p);
    }
    /** Fall protection is a property of equipped flight gear, even before a flight trigger fires. */
    private boolean hasFlightEquipment(Player p) {
        if(!enabled(p))return false;
        for(EquipmentSlot slot:SLOTS){
            var item=p.getInventory().getItem(slot);
            if(!damageable(item))continue;
            for(var def:plugin.settings().enchantments().values()){
                if(def.activeSlots().stream().noneMatch(s->EnchantmentWorkshopBootstrap.slot(s).test(slot)) || plugin.enchants().level(item,def)==0)continue;
                if(def.effects().stream().anyMatch(e->e.program()!=null && e.program().action().equals("FLIGHT")))return true;
            }
        }
        return false;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void fall(EntityDamageEvent event) {
        if(event.getCause()==EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player p && hasFlightEquipment(p)){
            event.setCancelled(true);p.setFallDistance(0);
        }
    }
    public void tick(){for(Player p:Bukkit.getOnlinePlayers())tickPlayer(p);}
    public void clear(){for(Player p:Bukkit.getOnlinePlayers())revoke(p);cooldowns.clear();flights.clear();takeoffs.clear();}
    private LivingEntity source(Entity e){if(e instanceof Projectile pr && pr.getShooter() instanceof LivingEntity l)return l;return e instanceof LivingEntity l?l:null;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){
        if(executing||e.getFinalDamage()<=0)return;
        LivingEntity src=source(e.getDamager()); LivingEntity dst=e.getEntity() instanceof LivingEntity l?l:null;
        Bukkit.getScheduler().runTask(plugin,()->{if(e.isCancelled())return;if(src instanceof Player p&&p.isOnline())fire(p,dst,"ATTACK");if(dst instanceof Player p&&p.isOnline())fire(p,src,"HURT");});
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void block(BlockBreakEvent e){Bukkit.getScheduler().runTask(plugin,()->{if(!e.isCancelled()&&e.getPlayer().isOnline())fire(e.getPlayer(),null,"BREAK");});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void death(EntityDeathEvent e){if(e.getEntity().getKiller()!=null)fire(e.getEntity().getKiller(),null,"KILL");}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(e.getHand()==EquipmentSlot.HAND&&e.getAction().isRightClick())fire(e.getPlayer(),null,"INTERACT");}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void sneak(PlayerToggleSneakEvent e){Bukkit.getScheduler().runTask(plugin,()->{if(!e.isCancelled()&&e.getPlayer().isOnline())fire(e.getPlayer(),null,"SNEAK");});}
    @EventHandler public void quit(PlayerQuitEvent e){revoke(e.getPlayer());cooldowns.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void join(PlayerJoinEvent e){revoke(e.getPlayer());}
    @EventHandler public void death(PlayerDeathEvent e){revoke(e.getEntity());}
    @EventHandler public void world(PlayerChangedWorldEvent e){revoke(e.getPlayer());}
}
