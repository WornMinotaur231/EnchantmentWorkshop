package cn.mc.enchantmentworkshop;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.*;

public final class EditorCommands {
    public static final List<String> ACTIONS = List.of("editor", "create", "copy", "set", "effect", "validate", "status", "cancel", "delete");
    private EditorCommands() {}
    public static void execute(EnchantmentWorkshopPlugin plugin, CommandSender sender, String[] args) throws Exception {
        if (!sender.hasPermission("enchantmentworkshop.admin")) throw new IllegalArgumentException("需要 enchantmentworkshop.admin 权限。");
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("editor") || action.equals("cancel")) {
            if (!(sender instanceof Player player)) throw new IllegalArgumentException("图形编辑器仅限游戏内玩家；控制台可使用 create/set/effect。");
            if (action.equals("editor")) plugin.menus().open(player); else plugin.menus().cancel(player);
            return;
        }
        var editor = plugin.editor(); var draft = editor.read();
        switch (action) {
            case "delete" -> {
                require(args.length == 2 || (args.length == 3 && (args[2].equalsIgnoreCase("confirm") || args[2].equals("确认"))), "/ew delete <ID> [confirm/确认]");
                String senderKey = sender instanceof Player p ? "player:" + p.getUniqueId() : "sender:" + sender.getName();
                if (args.length == 3) sender.sendMessage("[附魔工坊] " + editor.confirmDelete(senderKey, args[1]));
                else {
                    var deletion = editor.requestDelete(senderKey, args[1]);
                    sender.sendMessage("[附魔工坊] 即将删除「" + deletion.name() + "」（" + deletion.id() + "）。关联冲突引用：" + deletion.references());
                    sender.sendMessage("[附魔工坊] 将备份配置；完整重启后移除注册。已有物品上的该附魔可能丢失，请先备份存档。只支持本插件的附魔。");
                    sender.sendMessage("[附魔工坊] 2 分钟内执行 /ew delete " + args[1] + " confirm 确认；不确认不会删除。");
                }
                return;
            }
            case "create", "copy" -> {
                require(args.length >= 3, action.equals("create") ? "/ew create <新ID> <模板ID> [名称]" : "/ew copy <源ID> <新ID> [名称]");
                boolean copy = action.equals("copy");
                editor.create(draft, args[copy ? 2 : 1], args[copy ? 1 : 2], args.length > 3 ? tail(args, 3) : "", copy);
            }
            case "set" -> {
                require(args.length >= 4, "/ew set <ID> <字段> <值>");
                editor.set(draft, args[1], args[2], tail(args, 3));
            }
            case "effect" -> {
                require(args.length >= 4, "/ew effect <ID> add <类型> | set <序号> <字段> <值> | remove <序号>");
                switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "add" -> editor.addEffect(draft, args[1], EffectType.valueOf(args[3].toUpperCase(Locale.ROOT)));
                    case "remove" -> editor.removeEffect(draft, args[1], index(args[3]));
                    case "set" -> { require(args.length >= 6, "/ew effect <ID> set <序号> <字段> <值>"); editor.setEffect(draft, args[1], index(args[3]), args[4], tail(args, 5)); }
                    default -> throw new IllegalArgumentException("效果操作为 add、set 或 remove。");
                }
            }
            case "validate" -> {
                EnchantmentWorkshopPlugin.validateDefinitions(editor.settings(draft));
                sender.sendMessage("[附魔工坊] 配置校验通过。" + (plugin.canReload(editor.settings(draft)) ? "可热重载。" : "有注册变更，需完整重启。")); return;
            }
            case "status" -> {
                for (var def : editor.settings(draft).enchantments().values()) sender.sendMessage("[附魔工坊] " + def.id() + " · " + def.name() + " · " + editor.state(def));
                return;
            }
            default -> throw new IllegalArgumentException("未知编辑命令。");
        }
        sender.sendMessage("[附魔工坊] " + editor.save(draft));
    }
    private static String tail(String[] args, int from) { return String.join(" ", Arrays.copyOfRange(args, from, args.length)); }
    private static int index(String value) {
        try { return Integer.parseInt(value) - 1; } catch (NumberFormatException ex) { throw new IllegalArgumentException("效果序号从 1 开始。"); }
    }
    private static void require(boolean ok, String usage) { if (!ok) throw new IllegalArgumentException("用法：" + usage); }
    public static Collection<String> suggest(EnchantmentWorkshopPlugin plugin, String[] args) {
        try {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && List.of("copy", "set", "effect", "delete").contains(action)) return plugin.editor().ids(plugin.editor().read());
            if (args.length == 3 && action.equals("delete")) return List.of("confirm", "确认");
            if (args.length == 3 && action.equals("create")) return plugin.editor().templates();
            if (args.length == 3 && action.equals("set")) return DefinitionEditor.FIELDS;
            if (action.equals("effect")) {
                if (args.length == 3) return List.of("add", "set", "remove");
                if (args.length == 4 && args[2].equals("add")) return Arrays.stream(EffectType.values()).map(Enum::name).toList();
                if (args.length == 4) return List.of("1", "2", "3", "4");
                if (args.length == 5 && args[2].equals("set")) return DefinitionEditor.EFFECT_FIELDS;
            }
        } catch (Exception ignored) { }
        return List.of();
    }
}
