package cn.mc.enchantmentworkshop;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class EnchantCommand implements BasicCommand {
    private final EnchantmentWorkshopPlugin plugin;
    public EnchantCommand(EnchantmentWorkshopPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("enchantmentworkshop.use") || sender.hasPermission("enchantmentworkshop.admin");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!canUse(sender)) { error(sender, "没有权限。"); return; }
        try {
            String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            if (EditorCommands.ACTIONS.contains(action)) { EditorCommands.execute(plugin, sender, args); return; }
            if (action.equals("help")) { help(sender); return; }
            if (action.equals("list")) { list(sender); return; }
            if (action.equals("info")) {
                require(args.length == 2, "用法：/ew info <附魔ID>");
                EnchantDefinition d = plugin.enchants().definition(id(args[1]));
                message(sender, d.name() + " (" + d.id() + ") · 最高 " + d.maxLevel() + " 级" + (d.enabled() ? "" : " · 已停用"));
                message(sender, d.description());
                message(sender, "适用标签：" + d.itemTag() + "；槽位：" + String.join(", ", d.activeSlots()));
                message(sender, "获取途径：附魔台=" + d.enchantingTable() + "，村民=" + d.villagerTrade() + "，随机战利品=" + d.randomLoot());
                return;
            }
            require(sender.hasPermission("enchantmentworkshop.admin"), "需要 enchantmentworkshop.admin 权限（默认 OP）。");
            switch (action) {
                case "give" -> {
                    require(args.length >= 3 && args.length <= 5, "用法：/ew give <在线玩家> <附魔ID> [等级] [数量]");
                    Player target = Bukkit.getPlayerExact(args[1]);
                    require(target != null, "找不到在线玩家：" + args[1]);
                    int level = args.length >= 4 ? positive(args[3], 255) : 1;
                    int amount = args.length >= 5 ? positive(args[4], 64) : 1;
                    ItemStack book = plugin.enchants().book(id(args[2]), level);
                    // Each enchanted book has max stack size 1; count real free storage slots first.
                    int free = 0;
                    for (ItemStack item : target.getInventory().getStorageContents()) if (item == null || item.getType().isAir()) free++;
                    require(free >= amount, "目标背包空间不足，需要 " + amount + " 个空槽位。");
                    for (int i = 0; i < amount; i++) {
                        var remaining = target.getInventory().addItem(book.clone());
                        remaining.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
                    }
                    message(sender, "已给予 " + target.getName() + " " + amount + " 本" + plugin.enchants().definition(id(args[2])).name() + " " + level + " 级附魔书。");
                }
                case "enchant" -> {
                    require(args.length >= 2 && args.length <= 3, "用法：/ew enchant <附魔ID> [等级]");
                    Player player = player(sender);
                    int level = args.length == 3 ? positive(args[2], 255) : 1;
                    ItemStack result = plugin.enchants().apply(player.getInventory().getItemInMainHand(), id(args[1]), level);
                    player.getInventory().setItemInMainHand(result);
                    message(sender, "已为主手物品添加附魔。");
                }
                case "remove" -> {
                    require(args.length == 2, "用法：/ew remove <附魔ID>");
                    Player player = player(sender);
                    player.getInventory().setItemInMainHand(plugin.enchants().remove(player.getInventory().getItemInMainHand(), id(args[1])));
                    message(sender, "已移除指定附魔。");
                }
                case "reload" -> {
                    require(args.length == 1, "用法：/ew reload");
                    plugin.reloadEffects();
                    message(sender, "效果及通用配置已重新加载。");
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException ex) {
            error(sender, ex.getMessage());
        } catch (Exception ex) {
            error(sender, "操作失败，当前配置保持不变：" + ex.getMessage());
            plugin.getLogger().warning("附魔命令失败：" + ex);
        }
    }

    private void list(CommandSender sender) {
        message(sender, "自定义附魔（点击查看详情）：");
        plugin.settings().enchantments().values().forEach(d -> sender.sendMessage(
                Component.text(" • " + d.name() + " [" + d.id() + "] · " + d.maxLevel() + " 级" + (d.enabled() ? "" : "（已停用）"), NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(Component.text(d.description())))
                        .clickEvent(ClickEvent.runCommand("/ew info " + d.id()))));
    }

    private static void help(CommandSender sender) {
        message(sender, "Enchantment Workshop / 附魔工坊 · /ew list · /ew info <附魔ID>");
        if (sender.hasPermission("enchantmentworkshop.admin")) {
            message(sender, "/ew editor — 打开附魔工坊；创建、改名、组合效果、保存");
            message(sender, "/ew create <新ID> <模板ID> [名称] · /ew copy <源ID> <新ID>");
            message(sender, "/ew set <ID> <字段> <值> · /ew effect <ID> add|set|remove ...");
            message(sender, "/ew delete <ID> [confirm] · 删除本插件的附魔定义（先预览再确认）");
            message(sender, "/ew validate · /ew status · /ew cancel");
            message(sender, "/ew give <玩家> <附魔ID> [等级] [数量]");
            message(sender, "/ew enchant <附魔ID> [等级] · /ew remove <附魔ID>");
            message(sender, "/ew reload — 重载效果；新增附魔需要重启服务器");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!canUse(source.getSender())) return List.of();
        boolean admin = source.getSender().hasPermission("enchantmentworkshop.admin");
        if (args.length <= 1) return match(admin ? List.of("help", "list", "info", "give", "enchant", "remove", "reload", "editor", "create", "copy", "set", "effect", "validate", "status", "cancel", "delete")
                : List.of("help", "list", "info"), args.length == 0 ? "" : args[0]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (admin && EditorCommands.ACTIONS.contains(action)) return match(EditorCommands.suggest(plugin, args), args[args.length - 1]);
        if (args.length == 2 && (action.equals("info") || admin && List.of("enchant", "remove").contains(action))
                || admin && args.length == 3 && action.equals("give"))
            return match(plugin.settings().enchantments().keySet(), args[args.length - 1]);
        if (admin && args.length == 2 && action.equals("give")) return match(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        int idIndex = action.equals("give") ? 2 : 1;
        if (admin && ((action.equals("give") && args.length == 4) || (action.equals("enchant") && args.length == 3))) {
            EnchantDefinition def = plugin.settings().enchantments().get(id(args[idIndex]));
            if (def == null) return List.of();
            List<String> levels = new ArrayList<>();
            for (int i = 1; i <= def.maxLevel(); i++) levels.add(Integer.toString(i));
            return match(levels, args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> match(Collection<String> values, String prefix) {
        return values.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
    private static String id(String value) { return value.startsWith("enchantmentworkshop:") ? value.substring("enchantmentworkshop:".length()) : value; }
    private static Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new IllegalArgumentException("此命令只能由玩家执行。");
    }
    private static int positive(String text, int max) {
        try { int value = Integer.parseInt(text); if (value >= 1 && value <= max) return value; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("需要 1 至 " + max + " 之间的整数。");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void message(CommandSender sender, String text) { sender.sendMessage(Component.text("[附魔] " + text, NamedTextColor.GREEN)); }
    private static void error(CommandSender sender, String text) { sender.sendMessage(Component.text("[附魔] " + text, NamedTextColor.RED)); }
}
