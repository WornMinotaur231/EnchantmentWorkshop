package cn.mc.enchantmentworkshop.probe;

import cn.mc.enchantmentworkshop.*;
import com.mojang.authlib.GameProfile;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Runs only in an isolated test server. Not packaged with the production plugin. */
public final class IntegrationProbe extends JavaPlugin implements Listener {
    private final Map<String, Boolean> checks = new LinkedHashMap<>();
    private EnchantmentWorkshopPlugin plugin;
    private Player player;
    private ServerPlayer handle;
    private World world;
    private Path config;
    private String original;
    private boolean admin;
    private boolean cancelDamage;
    private boolean cancelDrops;
    private boolean cancelWear;
    private int wearEvents;
    private int wearBreaks;
    private int phase;
    private Cow cow;
    private Item fullDrop;
    private Item collectedDrop;
    private Item cancelledDrop;
    private boolean nativeAttack;
    private double nativeBase;
    private double nativeResult;
    private Path loadedJar;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                setup();
                if (Boolean.getBoolean("enchantmentworkshop.probe.restart")) {
                    check("restart_created_command_registered", plugin.settings().enchantments().containsKey("command_created"));
                    check("restart_created_book", ((EnchantmentStorageMeta) plugin.enchants().book("command_created", 3).getItemMeta())
                            .getStoredEnchantLevel(plugin.enchants().enchantment("command_created")) == 3);
                    check("restart_combined_effects", plugin.settings().enchantments().get("probe_combo").effects().size() == 2);
                    check("restart_deleted_definition_absent", !plugin.settings().enchantments().containsKey("guardian"));
                    check("restart_deleted_registry_absent", RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                            .get(org.bukkit.NamespacedKey.fromString("enchantmentworkshop:guardian")) == null);
                    check("restart_new_native_weapon", plugin.enchants().apply(new ItemStack(Material.DIAMOND_SWORD), "probe_combo", 2)
                            .getEnchantmentLevel(plugin.enchants().enchantment("probe_combo")) == 2);
                    finish(null); return;
                }
                synchronous(); Bukkit.getScheduler().runTaskTimer(this, this::step, 2, 2);
            }
            catch (Throwable ex) { finish(ex); }
        }, 20);
    }

    private void setup() throws Exception {
        plugin = (EnchantmentWorkshopPlugin) Bukkit.getPluginManager().getPlugin("EnchantmentWorkshop");
        check("plugin_enabled", plugin != null && plugin.isEnabled());
        check("renamed_plugin_identity",plugin.getName().equals("EnchantmentWorkshop"));
        check("ew_command_registered",Bukkit.getCommandMap().getCommand("ew")!=null);
        check("enchantmentworkshop_alias_registered",Bukkit.getCommandMap().getCommand("enchantmentworkshop")!=null);
        loadedJar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        check("production_jar_loaded", loadedJar.getFileName().toString().equals("EnchantmentWorkshop-1.0.0.jar"));
        config = plugin.getDataFolder().toPath().resolve("enchantments.yml");
        original = Files.readString(config, StandardCharsets.UTF_8);
        world = Bukkit.getWorlds().getFirst();
        world.setChunkForceLoaded(0, 0, true);
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "EnchantProbe");
        handle = new ServerPlayer(server, ((CraftWorld) world).getHandle(), profile, ClientInformation.createDefault());
        handle.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), handle,
                CommonListenerCookie.createInitial(profile, false));
        handle.setPos(0.5, 65, 0.5);
        Player real = handle.getBukkitEntity();
        player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "isOnline" -> true;
                case "getAttackCooldown" -> 1f;
                case "hasPermission" -> args[0].equals("enchantmentworkshop.use") || admin;
                case "sendMessage" -> null;
                default -> {
                    try { yield method.invoke(real, args); }
                    catch (InvocationTargetException ex) { throw ex.getCause(); }
                }
            };
        });
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
    }

    private void synchronous() throws Exception {
        var enchants = plugin.enchants();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (var d : plugin.settings().enchantments().values()) {
            check("workshop_namespace_" + d.id(), plugin.enchants().enchantment(d.id()).getKey().getNamespace().equals("enchantmentworkshop"));
            check("registry_" + d.id(), registry.get(EnchantmentWorkshopBootstrap.typedKey(d.id())) != null);
            ItemStack book = enchants.book(d.id(), d.maxLevel());
            ItemStack restored = ItemStack.deserializeBytes(book.serializeAsBytes());
            check("book_roundtrip_" + d.id(), ((EnchantmentStorageMeta) restored.getItemMeta()).getStoredEnchantLevel(enchants.enchantment(d.id())) == d.maxLevel());
            check("table_tag_" + d.id(), registry.getTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE).contains(EnchantmentWorkshopBootstrap.typedKey(d.id())));
            check("trade_tag_" + d.id(), registry.getTag(EnchantmentTagKeys.TRADEABLE).contains(EnchantmentWorkshopBootstrap.typedKey(d.id())));
        }
        ItemStack sword = enchants.apply(new ItemStack(Material.DIAMOND_SWORD), "lifesteal", 2);
        check("weapon_roundtrip", ItemStack.deserializeBytes(sword.serializeAsBytes()).getEnchantmentLevel(enchants.enchantment("lifesteal")) == 2);
        check("remove_custom_only", enchants.remove(sword, "lifesteal").getEnchantmentLevel(enchants.enchantment("lifesteal")) == 0);
        expectRejected("reject_wrong_item", () -> enchants.apply(new ItemStack(Material.DIRT), "lifesteal", 1));
        expectRejected("reject_excess_level", () -> enchants.book("lifesteal", 4));
        ItemStack silk = new ItemStack(Material.DIAMOND_PICKAXE);
        silk.addEnchantment(Enchantment.SILK_TOUCH, 1);
        expectRejected("reject_silk_conflict", () -> enchants.apply(silk, "smelting", 1));
        check("native_conflict_symmetric", enchants.enchantment("smelting").conflictsWith(Enchantment.SILK_TOUCH)
                && Enchantment.SILK_TOUCH.conflictsWith(enchants.enchantment("smelting")));

        AnvilMenu anvil = new AnvilMenu(1, handle.getInventory());
        anvil.getSlot(0).set(CraftItemStack.asNMSCopy(new ItemStack(Material.DIAMOND_SWORD)));
        anvil.getSlot(1).set(CraftItemStack.asNMSCopy(enchants.book("lifesteal", 2)));
        anvil.createResult();
        check("native_anvil_apply", CraftItemStack.asBukkitCopy(anvil.getSlot(2).getItem()).getEnchantmentLevel(enchants.enchantment("lifesteal")) == 2);
        anvil.getSlot(0).set(CraftItemStack.asNMSCopy(enchants.book("lifesteal", 2)));
        anvil.getSlot(1).set(CraftItemStack.asNMSCopy(enchants.book("lifesteal", 2)));
        anvil.createResult();
        check("native_anvil_upgrade", ((EnchantmentStorageMeta) CraftItemStack.asBukkitCopy(anvil.getSlot(2).getItem()).getItemMeta())
                .getStoredEnchantLevel(enchants.enchantment("lifesteal")) == 3);
        anvil.getSlot(0).set(CraftItemStack.asNMSCopy(silk));
        anvil.getSlot(1).set(CraftItemStack.asNMSCopy(enchants.book("smelting", 1)));
        anvil.createResult();
        check("native_anvil_conflict", anvil.getSlot(2).getItem().isEmpty());
        GrindstoneMenu grinder = new GrindstoneMenu(2, handle.getInventory());
        grinder.getSlot(0).set(CraftItemStack.asNMSCopy(sword));
        grinder.slotsChanged(null);
        check("native_grindstone", !grinder.getSlot(2).getItem().isEmpty()
                && CraftItemStack.asBukkitCopy(grinder.getSlot(2).getItem()).getEnchantmentLevel(enchants.enchantment("lifesteal")) == 0);

        boolean found = false;
        Random random = new Random(73);
        for (int i = 0; i < 400; i++) {
            var item = new ItemStack(Material.DIAMOND_SWORD).enchantWithLevels(30, registry.getTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE), random);
            if (item.getEnchantments().keySet().stream().anyMatch(e -> e.key().namespace().equals("enchantmentworkshop"))) { found = true; break; }
        }
        check("native_enchanting_roll", found);

        var command = new EnchantCommand(plugin);
        CommandSourceStack source = (CommandSourceStack) Proxy.newProxyInstance(CommandSourceStack.class.getClassLoader(), new Class[]{CommandSourceStack.class},
                (proxy, method, args) -> method.getName().equals("getSender") ? player : null);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        command.execute(source, new String[]{"enchant", "lifesteal", "1"});
        check("admin_permission_enforced", player.getInventory().getItemInMainHand().getEnchantments().isEmpty());
        admin = true;
        command.execute(source, new String[]{"enchant", "enchantmentworkshop:lifesteal", "1"});
        check("admin_command_apply", player.getInventory().getItemInMainHand().getEnchantmentLevel(enchants.enchantment("lifesteal")) == 1);

        Settings before = plugin.settings();
        Files.writeString(config, original.replace("name: 吸血", "name: 改名").replace("value: 0.08", "value: 0.11"));
        plugin.reloadEffects();
        check("reload_keeps_registered_name", plugin.settings().enchantments().get("lifesteal").name().equals(before.enchantments().get("lifesteal").name())
                && plugin.settings().enchantments().get("lifesteal").effects().getFirst().value() == .11);
        Files.writeString(config, original.replace("value: 0.08", "value: 0.10"));
        plugin.reloadEffects();
        check("reload_runtime_accepted", plugin.settings().enchantments().get("lifesteal").effects().getFirst().value() == .10);
        Files.writeString(config, original.replace("potion: minecraft:slowness", "potion: minecraft:nonexistent"));
        Settings valid = plugin.settings();
        expectRejected("reload_unknown_potion_rejected", plugin::reloadEffects);
        check("reload_bad_potion_atomic", plugin.settings() == valid);
        deterministicConfig();

        ItemStack xpTool = enchants.apply(new ItemStack(Material.DIAMOND_PICKAXE), "wisdom", 3);
        player.getInventory().setItemInMainHand(xpTool);
        BlockBreakEvent breakEvent = new BlockBreakEvent(world.getBlockAt(0, 63, 0), player);
        breakEvent.setExpToDrop(10);
        Bukkit.getPluginManager().callEvent(breakEvent);
        check("experience_bonus", breakEvent.getExpToDrop() == 16);
        BlockBreakEvent cancelled = new BlockBreakEvent(world.getBlockAt(0, 63, 0), player);
        cancelled.setExpToDrop(10); cancelled.setCancelled(true);
        Bukkit.getPluginManager().callEvent(cancelled);
        check("cancelled_break_unchanged", cancelled.getExpToDrop() == 10);
        player.getInventory().setHelmet(enchants.apply(new ItemStack(Material.DIAMOND_HELMET), "night_vision", 1));
        player.getInventory().setBoots(enchants.apply(new ItemStack(Material.DIAMOND_BOOTS), "swiftness", 2));
        player.getInventory().setItemInMainHand(enchants.apply(new ItemStack(Material.DIAMOND_PICKAXE), "haste", 2));
        new EffectListener(plugin).refreshPassive(player);
        check("passive_night_vision", player.hasPotionEffect(PotionEffectType.NIGHT_VISION));
        check("passive_speed", player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() == 1);
        check("passive_haste", player.getPotionEffect(PotionEffectType.HASTE).getAmplifier() == 1);
        player.getInventory().setChestplate(enchants.apply(new ItemStack(Material.DIAMOND_CHESTPLATE), "guardian", 3));
        cow = world.spawn(player.getLocation().add(2, 0, 0), Cow.class, c -> c.setAI(false));
        var defense = new EntityDamageByEntityEvent(cow, player, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).build(), 10);
        Bukkit.getPluginManager().callEvent(defense);
        check("guardian_reduction", Math.abs(defense.getDamage() - 8.8) < .001);
    }

    private void deterministicConfig() throws Exception {
        var yaml = ConfigLoader.yaml(original);
        for (String id : List.of("lifesteal", "critical", "frostbite")) {
            var effects = new ArrayList<Map<?, ?>>();
            for (var map : yaml.getMapList("enchantments." + id + ".effects")) {
                var effect = new LinkedHashMap<Object, Object>(map);
                effect.put("chance", 1.0); effect.put("chance-per-level", 0.0); effect.put("cooldown-ticks", 0);
                effects.add(effect);
            }
            yaml.set("enchantments." + id + ".effects", effects);
        }
        Files.writeString(config, yaml.saveToString());
        plugin.reloadEffects();
    }

    private void step() {
        try {
            switch (phase++) {
                case 0 -> {
                    check("test_target_loaded", cow.isValid());
                    ItemStack sword = plugin.enchants().apply(new ItemStack(Material.DIAMOND_SWORD), "lifesteal", 3);
                    sword = plugin.enchants().apply(sword, "frostbite", 3);
                    sword = plugin.enchants().apply(sword, "critical", 3);
                    player.getInventory().setItemInMainHand(sword);
                    player.setHealth(10);
                    var damage = attack();
                    check("critical_damage", Math.abs(damage.getDamage() - 16.5) < .001);
                }
                case 1 -> {
                    check("lifesteal_capped_to_target_health", Math.abs(player.getHealth() - 11.6) < .01);
                    if (!cow.hasPotionEffect(PotionEffectType.SLOWNESS)) getLogger().warning("Frostbite diagnostics: valid="
                            + cow.isValid() + ", dead=" + cow.isDead() + ", sameWorld=" + (cow.getWorld() == player.getWorld())
                            + ", enchants=" + player.getInventory().getItemInMainHand().getEnchantments()
                            + ", effects=" + cow.getActivePotionEffects());
                    check("frostbite_debuff", cow.hasPotionEffect(PotionEffectType.SLOWNESS));
                    player.setHealth(10); cow.removePotionEffect(PotionEffectType.SLOWNESS);
                    cancelDamage = true;
                    attack();
                }
                case 2 -> {
                    check("cancelled_hit_no_heal", player.getHealth() == 10);
                    check("cancelled_hit_no_debuff", !cow.hasPotionEffect(PotionEffectType.SLOWNESS));
                    cancelDamage = false;
                    player.getInventory().clear();
                    ItemStack tool = plugin.enchants().apply(new ItemStack(Material.DIAMOND_PICKAXE), "smelting", 1);
                    tool = plugin.enchants().apply(tool, "telekinesis", 1);
                    player.getInventory().setItemInMainHand(tool);
                    for (int i = 1; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
                    fullDrop = drops(3, false);
                }
                case 3 -> {
                    check("smelting_preserves_count", fullDrop.getItemStack().getType() == Material.IRON_INGOT && fullDrop.getItemStack().getAmount() == 3);
                    check("full_inventory_keeps_drop", fullDrop.isValid());
                    player.getInventory().setItem(1, null);
                    collectedDrop = drops(4, false);
                }
                case 4 -> {
                    check("telekinesis_collect", !collectedDrop.isValid() && player.getInventory().contains(Material.IRON_INGOT, 4));
                    cancelDrops = true;
                    cancelledDrop = drops(5, false);
                }
                case 5 -> {
                    check("cancelled_drops_not_collected", cancelledDrop.isValid() && player.getInventory().getItem(1).getAmount() == 4);
                    cancelDrops = false;
                    // Unsafe third-party items can contain conflicting enchants: silk touch still wins.
                    player.getInventory().getItemInMainHand().addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1);
                    Item raw = drops(2, true);
                    check("forced_silk_touch_skips_smelt", raw.getItemStack().getType() == Material.RAW_IRON);
                    player.getInventory().setItemInMainHand(plugin.enchants().apply(new ItemStack(Material.DIAMOND_SWORD), "critical", 3));
                    cow.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(100);
                    cow.setHealth(100);
                    handle.detectEquipmentUpdates();
                    setCounter("attackStrengthTicker", 100);
                    setCounter("itemSwapTicker", 100);
                    nativeAttack = true;
                    handle.attack(((org.bukkit.craftbukkit.entity.CraftLivingEntity) cow).getHandle());
                    nativeAttack = false;
                    check("real_melee_uses_pre_reset_charge", nativeBase > 0 && Math.abs(nativeResult / nativeBase - 1.65) < .001);
                    cow.setNoDamageTicks(0);
                    nativeBase = 0; nativeResult = 0;
                    setCounter("attackStrengthTicker", 0);
                    nativeAttack = true;
                    handle.attack(((org.bukkit.craftbukkit.entity.CraftLivingEntity) cow).getHandle());
                    nativeAttack = false;
                    check("real_weak_attack_no_bonus", nativeBase > 0 && Math.abs(nativeResult - nativeBase) < .001);
                    programChecks();
                }
                case 6 -> {
                    var field=EnchantmentWorkshopPlugin.class.getDeclaredField("programs");field.setAccessible(true);var engine=(ProgramEngine)field.get(plugin);
                    var pulse=ProgramEngine.class.getDeclaredMethod("tickPlayer",Player.class);pulse.setAccessible(true);pulse.invoke(engine,player);
                    check("flight_ignores_legacy_duration_after_server_delay",player.getAllowFlight() && player.isFlying());
                    check("flight_takeoff_scheduled_callback_charges_once",wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==2);
                    engine.quit(new org.bukkit.event.player.PlayerQuitEvent(player,net.kyori.adventure.text.Component.empty()));
                    Files.writeString(config,original);plugin.reloadEffects();
                    editorChecks();
                    finish(null);
                }
                default -> { }
            }
        } catch (Throwable ex) { finish(ex); }
    }

    private EntityDamageByEntityEvent attack() {
        var event = new EntityDamageByEntityEvent(player, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).build(), 10);
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private void editorChecks() throws Exception {
        Files.writeString(config, original); plugin.reloadEffects();
        var editor = plugin.editor();
        var draft = editor.read(); var stale = editor.read();
        editor.setEffect(draft, "lifesteal", 0, "value", "0.25");
        check("editor_effect_live_save", editor.save(draft).contains("并生效")
                && plugin.settings().enchantments().get("lifesteal").effects().getFirst().value() == .25);
        expectRejected("editor_stale_draft_rejected", () -> editor.save(stale));
        check("editor_backup_exists", Files.exists(config.resolveSibling("enchantments.yml.bak")));
        var invalid = editor.read(); editor.setEffect(invalid, "lifesteal", 0, "chance", "2");
        String before = Files.readString(config);
        expectRejected("editor_invalid_save_rejected", () -> editor.save(invalid));
        check("editor_invalid_save_keeps_file", before.equals(Files.readString(config)));
        var fresh = editor.read();
        editor.create(fresh, "probe_combo", "frostbite", "组合测试", false);
        editor.addEffect(fresh, "probe_combo", EffectType.LIFESTEAL);
        editor.setEffect(fresh, "probe_combo", 1, "value", "0.18");
        check("editor_new_definition_pending", editor.save(fresh).contains("重启")
                && !plugin.settings().enchantments().containsKey("probe_combo")
                && ConfigLoader.load(config.resolveSibling("config.yml")).enchantments().get("probe_combo").effects().size() == 2);
        var clone = editor.read(); editor.create(clone, "probe_copy", "probe_combo", "副本", true);
        editor.setEffect(clone, "probe_copy", 1, "value", "0.5");
        check("editor_copy_detached", ((Number)editor.effects(clone, "probe_combo").get(1).get("value")).doubleValue() == .18);
        var unsafe = editor.read();
        expectRejected("editor_rejects_invalid_id", () -> editor.create(unsafe, "../bad", "lifesteal", "bad", false));
        var command = new EnchantCommand(plugin);
        CommandSourceStack source = (CommandSourceStack) Proxy.newProxyInstance(CommandSourceStack.class.getClassLoader(), new Class[]{CommandSourceStack.class},
                (proxy, method, args) -> method.getName().equals("getSender") ? player : null);
        admin = false;
        command.execute(source, new String[]{"create", "forbidden", "lifesteal"});
        check("editor_command_permission", !editor.ids(editor.read()).contains("forbidden"));
        admin = true;
        command.execute(source, new String[]{"create", "command_created", "lifesteal", "指令创建"});
        check("editor_create_command", editor.ids(editor.read()).contains("command_created"));
        optionChecks();
        deletionChecks(command, source);
        Files.createDirectories(getDataFolder().toPath());
        Files.copy(config, getDataFolder().toPath().resolve("restart-candidate.yml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        plugin.menus().open(player);
        var view = player.getOpenInventory();
        check("editor_menu_open", view.getTopInventory().getHolder() instanceof EditorMenu.Page
                && view.getTopInventory().getItem(48).getType() == Material.EMERALD);
        var click = new org.bukkit.event.inventory.InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                0, org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY);
        Bukkit.getPluginManager().callEvent(click);
        check("editor_prevents_shift_extraction", click.isCancelled());
        var drag = new org.bukkit.event.inventory.InventoryDragEvent(view, new ItemStack(Material.STONE), new ItemStack(Material.STONE, 2), false,
                Map.of(0, new ItemStack(Material.STONE)));
        Bukkit.getPluginManager().callEvent(drag);
        check("editor_prevents_drag_insertion", drag.isCancelled());
        player.closeInventory();
    }

    private void deletionChecks(EnchantCommand command, CommandSourceStack source) throws Exception {
        var editor = plugin.editor();
        String before = Files.readString(config);
        command.execute(source, new String[]{"delete", "guardian", "confirm"});
        check("delete_requires_preview", Files.readString(config).equals(before));
        command.execute(source, new String[]{"delete", "guardian"});
        check("delete_preview_does_not_write", Files.readString(config).equals(before));
        admin = false;
        command.execute(source, new String[]{"delete", "guardian", "confirm"});
        check("delete_requires_permission", Files.readString(config).equals(before));
        admin = true;
        var changed = editor.read(); editor.set(changed, "frostbite", "description", "删除并发校验"); editor.save(changed);
        String concurrent = Files.readString(config);
        command.execute(source, new String[]{"delete", "guardian", "confirm"});
        check("delete_stale_confirmation_preserves_file", Files.readString(config).equals(concurrent));
        expectRejected("delete_unknown_id", () -> editor.prepareDelete("minecraft:sharpness"));
        var references = editor.read(); editor.set(references, "frostbite", "conflicts", "enchantmentworkshop:guardian,minecraft:sharpness"); editor.save(references);
        var deletion = editor.prepareDelete("guardian");
        check("delete_previews_references", deletion.references().contains("frostbite"));
        var rules = editor.read();
        editor.addEffect(rules, "lifesteal", EffectType.PROGRAM);
        var programMenu = EditorMenu.class.getDeclaredMethod("program", Player.class, DefinitionEditor.Draft.class, String.class, int.class);
        programMenu.setAccessible(true); programMenu.invoke(plugin.menus(), player, rules, "lifesteal", 1);
        var translated = player.getOpenInventory().getTopInventory().getItem(0).getItemMeta().lore();
        check("editor_trigger_display_chinese", translated.stream().anyMatch(line -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).contains("攻击命中")));
        check("editor_all_program_options_localized", java.util.stream.Stream.of(ProgramRule.TRIGGERS, ProgramRule.ACTIONS, ProgramRule.CONDITIONS)
                .flatMap(List::stream).noneMatch(value -> EditorText.text(value).equals(value)));
        var gui = EditorMenu.class.getDeclaredMethod("deletePreview", Player.class, DefinitionEditor.Draft.class, String.class);
        gui.setAccessible(true); gui.invoke(plugin.menus(), player, editor.read(), "guardian");
        var inventory = player.getOpenInventory().getTopInventory();
        check("delete_gui_has_cancel_and_confirm", inventory.getItem(45).getType() == Material.ARROW && inventory.getItem(49).getType() == Material.RED_CONCRETE);
        player.closeInventory();
        String backup = Files.readString(config);
        command.execute(source, new String[]{"delete", "guardian"});
        command.execute(source, new String[]{"delete", "guardian", "确认"});
        check("delete_command_removes_definition", !editor.ids(editor.read()).contains("guardian"));
        check("delete_preserves_backup", Files.readString(config.resolveSibling("enchantments.yml.bak")).equals(backup));
        check("delete_removes_only_own_reference", editor.settings(editor.read()).enchantments().get("frostbite").conflicts().equals(List.of("minecraft:sharpness")));
        check("delete_effect_stops_immediately", !plugin.settings().enchantments().containsKey("guardian"));
        plugin.reloadEffects();
        String deleted = Files.readString(config);
        command.execute(source, new String[]{"delete", "guardian", "confirm"});
        check("delete_confirmation_single_use", Files.readString(config).equals(deleted));
        for (String id : editor.ids(editor.read())) editor.delete(editor.prepareDelete(id));
        check("delete_last_definition_allowed", editor.settings(editor.read()).enchantments().isEmpty());
        plugin.menus().open(player);
        check("delete_empty_editor_can_create", player.getOpenInventory().getTopInventory().getItem(46).getType() == Material.NETHER_STAR);
        player.closeInventory();
        var fresh = editor.read(); editor.create(fresh, "after_empty", "lifesteal", "重新创建", false); editor.save(fresh);
        check("delete_empty_config_can_recreate", editor.ids(editor.read()).equals(List.of("after_empty")));
        Files.writeString(config, deleted);
    }

    private void programChecks() throws Exception {
        var yaml=ConfigLoader.yaml(original);
        var rule=new LinkedHashMap<>(DefinitionEditor.effect(EffectType.PROGRAM));
        rule.put("trigger","INTERACT");rule.put("potion","minecraft:strength");rule.put("conditions",Map.of("sneaking",true));
        yaml.set("enchantments.lifesteal.effects",List.of(rule));Files.writeString(config,yaml.saveToString());plugin.reloadEffects();
        player.getInventory().setItemInMainHand(plugin.enchants().apply(new ItemStack(Material.DIAMOND_SWORD),"lifesteal",1));
        var field=EnchantmentWorkshopPlugin.class.getDeclaredField("programs");field.setAccessible(true);var engine=(ProgramEngine)field.get(plugin);
        player.removePotionEffect(PotionEffectType.STRENGTH);player.setSneaking(false);engine.fire(player,null,"INTERACT");
        check("program_condition_blocks_action",!player.hasPotionEffect(PotionEffectType.STRENGTH));
        player.setSneaking(true);engine.fire(player,null,"INTERACT");check("program_self_potion",player.hasPotionEffect(PotionEffectType.STRENGTH));
        player.removePotionEffect(PotionEffectType.STRENGTH);engine.fire(player,null,"INTERACT");check("program_cooldown",!player.hasPotionEffect(PotionEffectType.STRENGTH));
        rule.put("trigger","HURT");rule.put("target","OTHER");rule.put("potion","minecraft:weakness");rule.put("conditions",Map.of());
        yaml.set("enchantments.lifesteal.effects",List.of(rule));Files.writeString(config,yaml.saveToString());plugin.reloadEffects();
        cow.removePotionEffect(PotionEffectType.WEAKNESS);engine.fire(player,cow,"HURT");check("program_attacker_potion",cow.hasPotionEffect(PotionEffectType.WEAKNESS));
        rule.put("trigger","INTERACT");rule.put("target","SELF");rule.put("action","FLIGHT");
        yaml.set("enchantments.lifesteal.effects",List.of(rule));Files.writeString(config,yaml.saveToString());plugin.reloadEffects();
        player.setAllowFlight(false);engine.fire(player,null,"INTERACT");check("program_flight_granted",player.getAllowFlight());
        engine.quit(new org.bukkit.event.player.PlayerQuitEvent(player,net.kyori.adventure.text.Component.empty()));
        check("program_flight_revoked_on_quit",!player.getAllowFlight());player.setSneaking(false);
        var region=ProgramRule.parse(ConfigLoader.yaml("conditions:\n  box: '-10,0,-10,10,100,10'\n  height: '60,80'\n  world: '"+world.getName()+"'"));
        check("program_position_conditions",region.matches(player,null));
        flightWearChecks(engine);
    }

    private void pressMenu(int slot) throws Exception {
        Object page = player.getOpenInventory().getTopInventory().getHolder();
        var field = EditorMenu.Page.class.getDeclaredField("actions"); field.setAccessible(true);
        Object action = ((Map<?, ?>) field.get(page)).get(slot);
        if (action == null) throw new AssertionError("Missing menu action " + slot);
        var run = action.getClass().getDeclaredMethod("run"); run.setAccessible(true);
        try { run.invoke(action); } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
    private String menuText(ItemStack item) {
        var serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        var meta = item.getItemMeta();
        return serializer.serialize(meta.displayName()) + " " + (meta.lore()==null?"":String.join(" ", meta.lore().stream().map(serializer::serialize).toList()));
    }
    private void optionChecks() throws Exception {
        var editor = plugin.editor(); var draft = editor.read(); String before = Files.readString(config);
        var edit = EditorMenu.class.getDeclaredMethod("edit", Player.class, DefinitionEditor.Draft.class, String.class); edit.setAccessible(true);
        edit.invoke(plugin.menus(), player, draft, "lifesteal"); pressMenu(DefinitionEditor.FIELDS.indexOf("item-tag"));
        pressMenu(51);
        var first = player.getOpenInventory().getTopInventory().getItem(0);
        check("item_picker_uses_actual_item", first != null && first.getType() != Material.PAPER && first.getType() != Material.BARRIER);
        edit.invoke(plugin.menus(), player, draft, "lifesteal"); pressMenu(DefinitionEditor.FIELDS.indexOf("active-slots"));
        check("slots_menu_shows_selected_mainhand", player.getOpenInventory().getTopInventory().getItem(0).getType() == Material.DIAMOND_SWORD);
        pressMenu(1); check("slots_menu_multiselect", draft.yaml().getStringList("enchantments.lifesteal.active-slots").equals(List.of("mainhand", "offhand")));
        pressMenu(0); expectRejected("slots_menu_keeps_one_slot", () -> pressMenu(1));
        pressMenu(48); pressMenu(49);
        editor.create(draft, "unsaved_conflict", "lifesteal", "未保存冲突候选", false);
        pressMenu(DefinitionEditor.FIELDS.indexOf("conflicts"));
        boolean foundDraft = false, foundVanilla = false;
        for (int page = 0; page < 4; page++) {
            for (int slot = 0; slot < 45; slot++) {
                ItemStack item = player.getOpenInventory().getTopInventory().getItem(slot); if (item == null) continue;
                String text = menuText(item);
                check("conflict_menu_excludes_self", !text.contains("代码：enchantmentworkshop:lifesteal "));
                if (text.contains("代码：minecraft:sharpness ")) { pressMenu(slot); foundVanilla = true; }
                if (text.contains("代码：enchantmentworkshop:unsaved_conflict ")) { pressMenu(slot); foundDraft = true; }
            }
            if (foundDraft && foundVanilla) break;
            pressMenu(53);
        }
        check("conflict_menu_includes_draft_and_native", foundDraft && foundVanilla && draft.yaml().getStringList("enchantments.lifesteal.conflicts").containsAll(List.of("minecraft:sharpness", "enchantmentworkshop:unsaved_conflict")));
        pressMenu(48); check("conflict_menu_clear", draft.yaml().getStringList("enchantments.lifesteal.conflicts").isEmpty());
        editor.addEffect(draft, "lifesteal", EffectType.PROGRAM); editor.setEffect(draft,"lifesteal",1,"conditions.sneaking","true");
        var program = EditorMenu.class.getDeclaredMethod("program", Player.class, DefinitionEditor.Draft.class, String.class, int.class); program.setAccessible(true);
        program.invoke(plugin.menus(), player, draft, "lifesteal", 1); pressMenu(9);
        check("condition_selector_has_unconditional", menuText(player.getOpenInventory().getTopInventory().getItem(0)).contains("无条件"));
        pressMenu(0); check("unconditional_selection_clears_conditions", ((Map<?,?>)editor.effects(draft,"lifesteal").get(1).get("conditions")).isEmpty());
        editor.setEffect(draft,"lifesteal",1,"conditions.sneaking","true"); editor.setEffect(draft,"lifesteal",1,"conditions","none");
        check("unconditional_command_field", editor.settings(draft).enchantments().get("lifesteal").effects().get(1).program().matches(null,null));
        editor.setEffect(draft,"lifesteal",1,"action","FLIGHT"); program.invoke(plugin.menus(),player,draft,"lifesteal",1);
        check("flight_menu_has_wear_controls", menuText(player.getOpenInventory().getTopInventory().getItem(26)).contains("每次起飞消耗耐久") && menuText(player.getOpenInventory().getTopInventory().getItem(27)).contains("摔落保护"));
        check("flight_menu_hides_duration_and_interval",java.util.Arrays.stream(player.getOpenInventory().getTopInventory().getContents()).filter(java.util.Objects::nonNull).noneMatch(item->menuText(item).contains("持续时间") || menuText(item).contains("消耗间隔")));
        check("option_menus_do_not_save_implicitly", before.equals(Files.readString(config))); player.closeInventory();
    }
    private void completeTakeoff(ProgramEngine engine, org.bukkit.event.player.PlayerToggleFlightEvent event) throws Exception {
        var method=ProgramEngine.class.getDeclaredMethod("finishTakeoff",org.bukkit.event.player.PlayerToggleFlightEvent.class);method.setAccessible(true);method.invoke(engine,event);
    }
    private org.bukkit.event.player.PlayerToggleFlightEvent takeoff(ProgramEngine engine, boolean cancelLater) throws Exception {
        player.setFlying(false);
        var event=new org.bukkit.event.player.PlayerToggleFlightEvent(player,true);Bukkit.getPluginManager().callEvent(event);
        if(cancelLater)event.setCancelled(true);
        if(!event.isCancelled())player.setFlying(true);
        completeTakeoff(engine,event);return event;
    }
    private int wearDamage(org.bukkit.inventory.EquipmentSlot slot) {
        return ((org.bukkit.inventory.meta.Damageable)player.getInventory().getItem(slot).getItemMeta()).getDamage();
    }
    private EntityDamageEvent fallEvent(EntityDamageEvent.DamageCause cause) {
        var event=new EntityDamageEvent(player,cause,DamageSource.builder(cause==EntityDamageEvent.DamageCause.FALL?DamageType.FALL:DamageType.LAVA).build(),10);
        Bukkit.getPluginManager().callEvent(event);return event;
    }
    private void flightWearChecks(ProgramEngine engine) throws Exception {
        var yaml=ConfigLoader.yaml(original);var rule=new LinkedHashMap<>(DefinitionEditor.effect(EffectType.PROGRAM));
        rule.put("trigger","INTERACT");rule.put("action","FLIGHT");rule.put("duration-ticks",1);
        rule.put("flight-durability-cost",3);rule.put("flight-durability-interval-ticks",40);
        yaml.set("enchantments.lifesteal.effects",List.of(rule));Files.writeString(config,yaml.saveToString());plugin.reloadEffects();
        var pulse=ProgramEngine.class.getDeclaredMethod("tickPlayer",Player.class);pulse.setAccessible(true);
        var cooldown=ProgramEngine.class.getDeclaredField("cooldowns");cooldown.setAccessible(true);
        player.getInventory().setItemInMainHand(plugin.enchants().apply(new ItemStack(Material.DIAMOND_SWORD),"lifesteal",1));
        player.setAllowFlight(false);wearEvents=0;wearBreaks=0;
        check("flight_gear_protects_fall_before_activation",fallEvent(EntityDamageEvent.DamageCause.FALL).isCancelled());
        check("flight_gear_does_not_cancel_other_damage",!fallEvent(EntityDamageEvent.DamageCause.LAVA).isCancelled());
        engine.fire(player,null,"INTERACT");for(int i=0;i<5;i++)pulse.invoke(engine,player);
        check("flight_activation_and_idle_do_not_charge",player.getAllowFlight() && wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==0);
        var start=takeoff(engine,false);
        check("flight_takeoff_charges_once",player.isFlying() && wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==3 && wearEvents==1);
        completeTakeoff(engine,start);
        check("flight_duplicate_callback_no_extra_charge",wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==3 && wearEvents==1);
        for(int i=0;i<100;i++)pulse.invoke(engine,player);
        ((Map<?,?>)cooldown.get(engine)).clear();engine.fire(player,null,"INTERACT");
        check("flight_hover_and_repeat_trigger_no_charge",player.getAllowFlight() && wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==3 && wearEvents==1);
        var stop=new org.bukkit.event.player.PlayerToggleFlightEvent(player,false);Bukkit.getPluginManager().callEvent(stop);player.setFlying(false);completeTakeoff(engine,stop);
        check("flight_landing_no_charge",wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==3);
        takeoff(engine,false);check("flight_second_takeoff_charges_once",wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==6 && wearEvents==2);
        takeoff(engine,true);check("flight_cancelled_late_does_not_charge",!player.isFlying() && wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==6);
        var cancelled=new org.bukkit.event.player.PlayerToggleFlightEvent(player,true);cancelled.setCancelled(true);Bukkit.getPluginManager().callEvent(cancelled);completeTakeoff(engine,cancelled);
        check("flight_cancelled_early_does_not_charge",wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==6);
        cancelWear=true;takeoff(engine,false);cancelWear=false;
        check("flight_takeoff_honors_damage_cancellation",wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==6);
        var item=player.getInventory().getItemInMainHand();var meta=(org.bukkit.inventory.meta.Damageable)item.getItemMeta();meta.setUnbreakable(true);item.setItemMeta(meta);
        takeoff(engine,false);check("flight_takeoff_honors_unbreakable",wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==6);
        meta.setUnbreakable(false);meta.setDamage(Material.DIAMOND_SWORD.getMaxDurability()-2);item.setItemMeta(meta);
        takeoff(engine,false);check("flight_last_durability_break_revokes",player.getInventory().getItemInMainHand().isEmpty() && !player.getAllowFlight() && !player.isFlying() && player.hasPotionEffect(PotionEffectType.SLOW_FALLING));
        check("flight_break_fires_native_event",wearBreaks==1);
        check("flight_broken_item_no_fall_immunity",!fallEvent(EntityDamageEvent.DamageCause.FALL).isCancelled());
        ((Map<?,?>)cooldown.get(engine)).clear();item=plugin.enchants().apply(new ItemStack(Material.DIAMOND_SWORD),"lifesteal",1);player.getInventory().setItemInMainHand(item);engine.fire(player,null,"INTERACT");
        player.setFlying(false);var removed=new org.bukkit.event.player.PlayerToggleFlightEvent(player,true);Bukkit.getPluginManager().callEvent(removed);player.setFlying(true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));completeTakeoff(engine,removed);
        check("flight_source_removed_before_charge_revokes",!player.getAllowFlight());
        player.getInventory().setItem(9,item);
        check("flight_backpack_only_no_fall_immunity",!fallEvent(EntityDamageEvent.DamageCause.FALL).isCancelled());player.getInventory().setItem(9,null);
        player.getInventory().setItemInMainHand(item);((Map<?,?>)cooldown.get(engine)).clear();engine.fire(player,null,"INTERACT");
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));pulse.invoke(engine,player);check("flight_unequip_revokes_without_takeoff",!player.getAllowFlight());
        meta=(org.bukkit.inventory.meta.Damageable)item.getItemMeta();meta.setDamage(Material.DIAMOND_SWORD.getMaxDurability());item.setItemMeta(meta);player.getInventory().setItemInMainHand(item);
        ((Map<?,?>)cooldown.get(engine)).clear();engine.fire(player,null,"INTERACT");
        check("flight_zero_durability_cannot_grant",!player.getAllowFlight());check("flight_zero_durability_no_fall_immunity",!fallEvent(EntityDamageEvent.DamageCause.FALL).isCancelled());
        player.getInventory().setItemInMainHand(plugin.enchants().apply(new ItemStack(Material.DIAMOND_SWORD),"lifesteal",1));player.setAllowFlight(true);
        ((Map<?,?>)cooldown.get(engine)).clear();engine.fire(player,null,"INTERACT");takeoff(engine,false);
        check("flight_external_permission_not_charged",wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==0);player.setFlying(false);player.setAllowFlight(false);
        rule.put("conditions",Map.of("sneaking",true));yaml.set("enchantments.lifesteal.effects",List.of(rule));Files.writeString(config,yaml.saveToString());plugin.reloadEffects();
        player.setSneaking(false);engine.fire(player,null,"INTERACT");
        check("flight_conditions_still_required",!player.getAllowFlight());
        check("flight_gear_fall_immunity_independent_of_conditions",fallEvent(EntityDamageEvent.DamageCause.FALL).isCancelled());
        player.setSneaking(true);((Map<?,?>)cooldown.get(engine)).clear();engine.fire(player,null,"INTERACT");player.setSneaking(false);pulse.invoke(engine,player);
        check("flight_condition_loss_revokes",!player.getAllowFlight());
        rule.put("conditions",Map.of());rule.put("flight-durability-cost",0);yaml.set("enchantments.lifesteal.effects",List.of(rule));
        var armorRule=new LinkedHashMap<>(rule);armorRule.put("flight-durability-cost",2);yaml.set("enchantments.guardian.effects",List.of(armorRule));Files.writeString(config,yaml.saveToString());plugin.reloadEffects();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));player.getInventory().setChestplate(plugin.enchants().apply(new ItemStack(Material.DIAMOND_CHESTPLATE),"guardian",1));
        check("flight_armor_grants_fall_immunity",fallEvent(EntityDamageEvent.DamageCause.FALL).isCancelled());engine.fire(player,null,"INTERACT");takeoff(engine,false);
        check("flight_takeoff_charges_source_slot_only",wearDamage(org.bukkit.inventory.EquipmentSlot.CHEST)==2 && wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==0);
        engine.quit(new org.bukkit.event.player.PlayerQuitEvent(player,net.kyori.adventure.text.Component.empty()));player.getInventory().setChestplate(null);
        player.getInventory().setItemInMainHand(plugin.enchants().apply(new ItemStack(Material.DIAMOND_SWORD),"lifesteal",1));engine.fire(player,null,"INTERACT");takeoff(engine,false);
        for(int i=0;i<4;i++)pulse.invoke(engine,player);
        check("flight_zero_cost_still_works",player.getAllowFlight() && wearDamage(org.bukkit.inventory.EquipmentSlot.HAND)==0);
        engine.quit(new org.bukkit.event.player.PlayerQuitEvent(player,net.kyori.adventure.text.Component.empty()));
        rule.put("flight-durability-cost",2);yaml.set("enchantments.lifesteal.effects",List.of(rule));Files.writeString(config,yaml.saveToString());plugin.reloadEffects();
        engine.fire(player,null,"INTERACT");
        var scheduled=new org.bukkit.event.player.PlayerToggleFlightEvent(player,true);Bukkit.getPluginManager().callEvent(scheduled);player.setFlying(true);
        // Let the real scheduler charge this takeoff, then verify duration=1 did not expire the grant.
    }
    @EventHandler public void wear(org.bukkit.event.player.PlayerItemDamageEvent event) {wearEvents++;if(cancelWear)event.setCancelled(true);}
    @EventHandler public void wearBreak(org.bukkit.event.player.PlayerItemBreakEvent event) {wearBreaks++;}

    private Item drops(int amount, boolean leave) {
        var block = world.getBlockAt(0, 64, 0);
        Item item = world.dropItem(player.getLocation(), new ItemStack(Material.RAW_IRON, amount));
        item.setPickupDelay(1000);
        var event = new BlockDropItemEvent(block, block.getState(), player, new ArrayList<>(List.of(item)));
        Bukkit.getPluginManager().callEvent(event);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void cancel(EntityDamageByEntityEvent event) { if (cancelDamage) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.MONITOR) public void cancel(BlockDropItemEvent event) { if (cancelDrops) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST) public void measureBefore(EntityDamageByEntityEvent event) {
        if (nativeAttack && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) nativeBase = event.getDamage();
    }
    @EventHandler(priority = EventPriority.MONITOR) public void measureAfter(EntityDamageByEntityEvent event) {
        if (nativeAttack && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) nativeResult = event.getDamage();
    }

    private void setCounter(String name, int value) throws Exception {
        for (Class<?> type = handle.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); field.setInt(handle, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private void check(String name, boolean condition) {
        checks.put(name, condition);
        if (!condition) throw new AssertionError(name);
    }
    private interface Checked { void run() throws Exception; }
    private void expectRejected(String name, Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { check(name, true); return; }
        check(name, false);
    }

    private void finish(Throwable error) {
        Bukkit.getScheduler().cancelTasks(this);
        try {
            if (config != null && original != null) { Files.writeString(config, original); plugin.reloadEffects(); }
            if (cow != null) cow.remove();
            getDataFolder().mkdirs();
            String jarHash = loadedJar == null ? "unavailable" : java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loadedJar)));
            String report = "result=" + (error == null ? "PASS" : "FAIL") + "\nserver=" + Bukkit.getVersion()
                    + "\npluginJar=" + loadedJar + "\npluginSha256=" + jarHash + "\n"
                    + String.join("\n", checks.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList())
                    + (error == null ? "" : "\nerror=" + error);
            Files.writeString(getDataFolder().toPath().resolve("results.txt"), report);
            if (error != null) error.printStackTrace();
            getLogger().info("ENCHANTMENTWORKSHOP_PROBE_" + (error == null ? "PASS" : "FAIL") + " checks=" + checks.size());
        } catch (Throwable ex) { ex.printStackTrace(); getLogger().severe("ENCHANTMENTWORKSHOP_PROBE_FAIL: " + ex); }
        Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 10);
    }
}
