package cn.mc.enchantmentworkshop;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Chest-style editor. No client mod, web server, or external program required. */
public final class EditorMenu implements Listener {
    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Input { void accept(String value) throws Exception; }
    private record Prompt(long created, Input input, Action back) {}
    private final EnchantmentWorkshopPlugin plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("name", "显示名称"), Map.entry("description", "说明"), Map.entry("enabled", "启用"),
            Map.entry("max-level", "最高等级"), Map.entry("weight", "随机获取权重"), Map.entry("anvil-cost", "铁砧费用系数"),
            Map.entry("item-tag", "适用物品标签"), Map.entry("active-slots", "生效装备槽位"), Map.entry("conflicts", "冲突附魔"),
            Map.entry("min-cost", "附魔强度起点"), Map.entry("cost-per-level", "每级附魔强度增加"), Map.entry("cost-span", "附魔强度区间"),
            Map.entry("acquisition.enchanting-table", "附魔台获取"), Map.entry("acquisition.villager-trade", "村民交易获取"),
            Map.entry("acquisition.random-loot", "随机战利品获取"), Map.entry("value", "效果数值 / 比例"), Map.entry("per-level", "每级数值增加"),
            Map.entry("chance", "触发概率（0～1）"), Map.entry("chance-per-level", "每级概率增加"), Map.entry("cooldown-ticks", "冷却（20 刻 = 1 秒）"),
            Map.entry("potion", "药水效果 ID"), Map.entry("duration-ticks", "持续时间（刻）"), Map.entry("amplifier", "药水等级（0 = I 级）"),
            Map.entry("amplifier-per-level", "每级药水等级增加"), Map.entry("flight-durability-cost", "每次起飞消耗耐久（0 = 关闭）"));

    public static final class Page implements InventoryHolder {
        private final UUID owner;
        private final Inventory inventory;
        private final Map<Integer, Action> actions = new HashMap<>();
        Page(Player owner, String title) {
            this.owner = owner.getUniqueId();
            inventory = Bukkit.createInventory(this, 54, Component.text(title));
        }
        @Override public Inventory getInventory() { return inventory; }
        void button(int slot, Material material, String name, List<String> lines, Action action) {
            button(slot, material, Component.text(name), lines, action);
        }
        void button(int slot, Material material, Component name, List<String> lines, Action action) {
            ItemStack item = new ItemStack(material);
            var meta = item.getItemMeta();
            meta.displayName(name.color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
            item.setItemMeta(meta); inventory.setItem(slot, item);
            if (action != null) actions.put(slot, action);
        }
    }
    public EditorMenu(EnchantmentWorkshopPlugin plugin) { this.plugin = plugin; }
    public void open(Player player) throws Exception {
        if (!player.hasPermission("enchantmentworkshop.admin")) throw new IllegalArgumentException("需要管理员权限。");
        prompts.remove(player.getUniqueId()); list(player, 0);
    }
    public void cancel(Player player) { prompts.remove(player.getUniqueId()); say(player, "已取消输入；未保存的修改不会写入文件。"); }
    private DefinitionEditor editor() { return plugin.editor(); }
    private void list(Player player, int requested) throws Exception {
        var draft = editor().read(); var settings = editor().settings(draft);
        List<EnchantDefinition> entries = new ArrayList<>(settings.enchantments().values());
        int page = Math.clamp(requested, 0, (entries.size() - 1) / 45);
        Page menu = new Page(player, "附魔工坊 · " + (page + 1));
        for (int i = page * 45; i < Math.min(entries.size(), (page + 1) * 45); i++) {
            var d = entries.get(i);
            menu.button(i % 45, Material.ENCHANTED_BOOK, d.name(), List.of("ID: " + d.id(), editor().state(d),
                    "最高 " + d.maxLevel() + " 级 · " + d.effects().size() + " 个效果", "点击编辑"), () -> edit(player, draft, d.id()));
        }
        menu.button(45, Material.ARROW, "上一页", List.of(), () -> list(player, page - 1));
        menu.button(46,Material.NETHER_STAR,"从空白创建",List.of("名称 → ID → 对象 → 条件与行为 → 效果 → 预览"),()->ask(player,"第一步：输入显示名称。",name->ask(player,"第二步：输入唯一英文 ID。",id->{
            if(!id.matches("[a-z0-9_][a-z0-9_/-]*")||editor().ids(draft).contains(id))throw new IllegalArgumentException("ID 无效或重复");
            String root="enchantments."+id;
            draft.yaml().set(root+".name",name);draft.yaml().set(root+".max-level",1);
            draft.yaml().set(root+".item-tag","minecraft:enchantable/durability");draft.yaml().set(root+".active-slots",List.of("mainhand"));
            draft.yaml().set(root+".acquisition",Map.of("enchanting-table",false,"villager-trade",false,"random-loot",false));
            draft.yaml().set(root+".effects",List.of(DefinitionEditor.effect(EffectType.PROGRAM)));targets(player,draft,id);
        },()->list(player,0)),()->list(player,0)));
        menu.button(48, Material.EMERALD, "从模板新建", List.of("选择模板 → 输入 ID → 调整数值 → 保存"), () -> templates(player, draft));
        menu.button(49, Material.BOOK, "保存与生效", List.of("效果数值：保存后自动生效", "新增 / 注册属性：保存后完整重启", "编辑页面点击保存才写入文件"), null);
        menu.button(53, Material.ARROW, "下一页", List.of(), () -> list(player, page + 1));
        player.openInventory(menu.inventory);
    }
    private void templates(Player player, DefinitionEditor.Draft draft) throws Exception {
        Page menu = new Page(player, "选择一种附魔模板"); int slot = 0;
        for (String template : editor().templates()) {
            menu.button(slot++, Material.ENCHANTED_BOOK, templateName(template), List.of(template, "以此为起点，可修改名称和组合效果"), () ->
                    ask(player, "输入新的英文 ID，例如 venom_blade（小写，不能重复）。", id -> {
                        editor().create(draft, id, template, "新附魔 " + id, false); edit(player, draft, id);
                    }, () -> templates(player, draft)));
        }
        menu.button(49, Material.ARROW, "返回", List.of(), () -> list(player, 0));
        player.openInventory(menu.inventory);
    }
    private void edit(Player player, DefinitionEditor.Draft draft, String id) throws Exception {
        Page menu = new Page(player, "编辑附魔 · " + id);
        var section = draft.yaml().getConfigurationSection("enchantments." + id);
        int slot = 0;
        for (String field : DefinitionEditor.FIELDS) {
            Object current = section.get(field);
            menu.button(slot++, Material.PAPER, LABELS.get(field), List.of("当前：" + EditorText.text(current),
                    "点击修改 · 需要保存", field), () -> {
                if (field.equals("item-tag")) {
                    targets(player, draft, id);
                } else if (field.equals("active-slots") || field.equals("conflicts")) {
                    selections(player, draft, id, field, 0);
                } else if (field.equals("enabled") || field.startsWith("acquisition.")) {
                    editor().set(draft, id, field, Boolean.toString(!section.getBoolean(field, true))); edit(player, draft, id);
                } else ask(player, "输入「" + LABELS.get(field) + "」；列表用英文逗号分隔，[] 清空列表。", text -> {
                    editor().set(draft, id, field, text); edit(player, draft, id);
                }, () -> edit(player, draft, id));
            });
        }
        var effects = editor().effects(draft, id);
        for (int i = 0; i < effects.size(); i++) {
            int index = i;
            menu.button(18 + i, Material.BLAZE_POWDER, "效果 " + (i + 1) + " · " + effectName(effects.get(i).get("type").toString()),
                    List.of("点击调整参数 / 删除效果"), () -> effect(player, draft, id, index));
        }
        menu.button(36, Material.SLIME_BALL, "添加一个效果", List.of("支持多个效果组合，最多 16 个"), () -> addEffect(player, draft, id));
        menu.button(38, Material.WRITABLE_BOOK, "复制为新附魔", List.of("复制当前草稿，使用新的 ID"), () ->
                ask(player, "输入副本的新 ID。", newId -> {
                    // Copy into a fresh snapshot; unsaved edits on the source should not be saved by the copy.
                    var copy = editor().read();
                    var snapshot = draft.yaml().getConfigurationSection("enchantments." + id);
                    var source = copy.yaml().get("enchantments." + id);
                    copy.yaml().set("enchantments." + id, snapshot);
                    editor().create(copy, newId, id, section.getString("name") + " 副本", true);
                    copy.yaml().set("enchantments." + id, source);
                    edit(player, copy, newId);
                }, () -> edit(player, draft, id)));
        if (editor().ids(editor().read()).contains(id))
            menu.button(53, Material.TNT, "删除整个附魔", List.of("删除已保存的定义，下一页确认", "本页未保存的修改不会被保存", "完整重启后生效"), () -> deletePreview(player, draft, id));
        menu.button(45, Material.ARROW, "放弃草稿并返回", List.of("不会保存修改"), () -> list(player, 0));
        menu.button(49, Material.LIME_CONCRETE, "校验并保存", List.of("通过校验后写入 enchantments.yml", "自动备份上一份配置", "新增 / 等级 / 名称等变更需重启"), () -> {
            preview(player,draft,id);
        });
        player.openInventory(menu.inventory);
    }
    private void deletePreview(Player player, DefinitionEditor.Draft previous, String id) throws Exception {
        var deletion = editor().prepareDelete(id);
        Page menu = new Page(player, "确认删除附魔");
        List<String> lines = new ArrayList<>(List.of("代码名称：" + deletion.id(), "完整重启后移除注册，重启前仍可能生效",
                "已有物品上的该附魔可能丢失，请先备份存档", "配置自动备份到 enchantments.yml.bak",
                "同时清理以下附魔的冲突引用："));
        lines.addAll(deletion.references().isEmpty() ? List.of("无") : deletion.references());
        menu.button(13, Material.ENCHANTED_BOOK, "删除「" + deletion.name() + "」", lines, null);
        menu.button(45, Material.ARROW, "取消删除", List.of("返回原草稿，不修改配置"), () -> edit(player, previous, id));
        menu.button(49, Material.RED_CONCRETE, "确认删除整个附魔", List.of("只删除这个附魔，不保存其他草稿修改"), () -> {
            say(player, editor().delete(deletion)); list(player, 0);
        });
        player.openInventory(menu.inventory);
    }
    private void selections(Player player, DefinitionEditor.Draft draft, String id, String field, int requested) {
        boolean slots = field.equals("active-slots");
        Map<String, Component> options = new LinkedHashMap<>();
        if (slots) {
            for (String key : List.of("mainhand", "offhand", "head", "chest", "legs", "feet", "hand", "armor", "any"))
                options.put(key, Component.text(EditorText.text(key)));
        } else {
            var registry = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
            registry.stream().sorted(Comparator.comparing(e -> e.getKey().toString())).forEach(e -> {
                // A deleted custom entry can still exist in the live registry until restart.
                if (!e.getKey().getNamespace().equals("enchantmentworkshop")) options.put(e.getKey().toString(), e.description());
            });
            for (String other : editor().ids(draft)) if (!other.equals(id))
                options.put("enchantmentworkshop:" + other, Component.text(draft.yaml().getString("enchantments." + other + ".name", other)));
        }
        String path = "enchantments." + id + "." + field;
        var selected = new LinkedHashSet<>(draft.yaml().getStringList(path));
        if (slots && !draft.yaml().contains(path)) selected.add("mainhand");
        var keys = new ArrayList<>(options.keySet());
        int page = Math.clamp(requested, 0, Math.max(0, (keys.size() - 1) / 45));
        Page menu = new Page(player, LABELS.get(field) + " · 多选 · " + (page + 1));
        for (int i = page * 45; i < Math.min(keys.size(), (page + 1) * 45); i++) {
            String key = keys.get(i); boolean checked = selected.contains(key);
            menu.button(i % 45, checked ? Material.LIME_DYE : Material.GRAY_DYE,
                    Component.text(checked ? "☑ " : "☐ ").append(options.get(key)), List.of("代码：" + key, "点击勾选 / 取消勾选", "仅修改草稿，返回后保存才生效"), () -> {
                if (checked && slots && selected.size() == 1) throw new IllegalArgumentException("至少选择一个生效槽位。");
                if (checked) selected.remove(key); else selected.add(key);
                draft.yaml().set(path, new ArrayList<>(selected)); selections(player, draft, id, field, page);
            });
        }
        menu.button(45, Material.ARROW, "上一页", List.of(), () -> selections(player, draft, id, field, page - 1));
        menu.button(48, Material.MILK_BUCKET, slots ? "重置为主手" : "无冲突 / 清空选择", List.of("只修改当前草稿"), () -> {
            draft.yaml().set(path, slots ? List.of("mainhand") : List.of()); selections(player, draft, id, field, page);
        });
        menu.button(49, Material.LIME_CONCRETE, "完成选择并返回", List.of("已选择 " + selected.size() + " 项", slots ? "多个槽位取并集，至少保留一项" : "可选择多个冲突附魔，也可不选"), () -> edit(player, draft, id));
        menu.button(53, Material.ARROW, "下一页", List.of(), () -> selections(player, draft, id, field, page + 1));
        player.openInventory(menu.inventory);
    }
    private void targets(Player player, DefinitionEditor.Draft draft, String id) {
        Page menu = new Page(player, "选择适用装备");
        String[][] choices = {{"剑", "swords", "mainhand"}, {"斧", "axes", "mainhand"}, {"镐", "pickaxes", "mainhand"},
                {"所有采矿工具", "enchantable/mining", "mainhand"}, {"头盔", "enchantable/head_armor", "head"},
                {"胸甲", "enchantable/chest_armor", "chest"}, {"护腿", "enchantable/leg_armor", "legs"},
                {"靴子", "enchantable/foot_armor", "feet"}, {"全部护甲", "enchantable/armor", "armor"},
                {"可附耐久的主手物品", "enchantable/durability", "mainhand"}};
        for (int i = 0; i < choices.length; i++) {
            String[] choice = choices[i];
            menu.button(i, Material.DIAMOND, choice[0], List.of("同时设置对应的生效槽位"), () -> {
                editor().set(draft, id, "item-tag", "minecraft:" + choice[1]);
                editor().set(draft, id, "active-slots", choice[2]); edit(player, draft, id);
            });
        }
        menu.button(48, Material.WRITABLE_BOOK, "高级：手动输入标签", List.of("例如 minecraft:axes；槽位请单独调整"), () ->
                ask(player, "输入物品标签完整 ID。", text -> { editor().set(draft, id, "item-tag", text); edit(player, draft, id); }, () -> targets(player, draft, id)));
        menu.button(49, Material.ARROW, "返回", List.of(), () -> edit(player, draft, id));
        menu.button(50, Material.COMPASS, "服务器全部物品类别", List.of("实时读取注册表，可翻页浏览"), () -> {
            var registry=io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ITEM);
            choose(player,"服务器物品类别",registry.getTags().stream().map(t->t.tagKey().key().asString()).sorted().toList(),0,tag->{editor().set(draft,id,"item-tag",tag);edit(player,draft,id);},()->targets(player,draft,id));
        });
        menu.button(51,Material.DIAMOND_SWORD,"服务器全部单项物品",List.of("不限模板；选择后请检查生效槽位"),()->choose(player,"全部物品",io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ITEM).stream().map(t->t.getKey().toString()).sorted().toList(),0,v->{editor().set(draft,id,"item-tag","@"+v);edit(player,draft,id);},()->targets(player,draft,id)));
        player.openInventory(menu.inventory);
    }
    private void addEffect(Player player, DefinitionEditor.Draft draft, String id) {
        Page menu = new Page(player, "添加效果"); int slot = 0;
        for (EffectType type : EffectType.values()) {
            menu.button(slot++, Material.BLAZE_POWDER, effectName(type.name()), List.of(type.name(), "点击添加默认参数，然后继续编辑"), () -> {
                editor().addEffect(draft, id, type); edit(player, draft, id);
            });
        }
        menu.button(49, Material.ARROW, "返回", List.of(), () -> edit(player, draft, id));
        player.openInventory(menu.inventory);
    }
    private void effect(Player player, DefinitionEditor.Draft draft, String id, int index) {
        var data = editor().effects(draft, id).get(index);
        EffectType type = EffectType.valueOf(data.get("type").toString());
        if(type==EffectType.PROGRAM){program(player,draft,id,index);return;}
        Page menu = new Page(player, "效果 " + (index + 1) + " · " + effectName(type.name()));
        List<String> fields = new ArrayList<>();
        if (List.of(EffectType.LIFESTEAL, EffectType.BONUS_DAMAGE, EffectType.EXPERIENCE_BOOST, EffectType.DAMAGE_REDUCTION).contains(type))
            fields.addAll(List.of("value", "per-level"));
        if (type == EffectType.PASSIVE_POTION || type == EffectType.POTION_ON_HIT)
            fields.addAll(List.of("potion", "duration-ticks", "amplifier", "amplifier-per-level"));
        if (List.of(EffectType.LIFESTEAL, EffectType.BONUS_DAMAGE, EffectType.POTION_ON_HIT).contains(type))
            fields.addAll(List.of("chance", "chance-per-level", "cooldown-ticks"));
        int slot = 0;
        for (String field : fields) {
            menu.button(slot++, Material.PAPER, LABELS.get(field), List.of("当前：" + EditorText.text(data.get(field)),
                    "比例 0.2 = 20% · 等级从 0 开始", "点击输入；default 恢复默认"), () ->
                    ask(player, "输入「" + LABELS.get(field) + "」的新值（default 恢复默认）。", value -> {
                        editor().setEffect(draft, id, index, field, value); effect(player, draft, id, index);
                    }, () -> effect(player, draft, id, index)));
        }
        if (fields.isEmpty()) menu.button(22, Material.BOOK, "此效果无需额外参数", List.of("返回后保存即可"), null);
        menu.button(45, Material.ARROW, "返回附魔草稿", List.of(), () -> edit(player, draft, id));
        menu.button(53, Material.BARRIER, "删除这个效果", List.of("仅修改草稿，保存才生效", "至少保留一个效果"), () -> {
            editor().removeEffect(draft, id, index); edit(player, draft, id);
        });
        player.openInventory(menu.inventory);
    }
    private void ask(Player player, String question, Input input, Action back) {
        prompts.put(player.getUniqueId(), new Prompt(System.currentTimeMillis(), input, back));
        player.closeInventory(); say(player, question + " 输入“取消”返回。此条回复不会公开，2 分钟内有效。");
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncChatEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt == null) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> perform(event.getPlayer(), () -> {
            if (input.equals("取消") || input.equalsIgnoreCase("cancel")) { prompt.back().run(); return; }
            if (System.currentTimeMillis() - prompt.created() > 120_000) { say(event.getPlayer(), "输入已过期，请重新编辑。"); prompt.back().run(); return; }
            try { prompt.input().accept(input); }
            catch (Exception ex) { say(event.getPlayer(), ex.getMessage()); prompt.back().run(); }
        }));
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Page page)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !page.owner.equals(player.getUniqueId())) return;
        Action action = page.actions.get(event.getRawSlot());
        if (action != null) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() == page) perform(player, action);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) { if (event.getView().getTopInventory().getHolder() instanceof Page) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { prompts.remove(event.getPlayer().getUniqueId()); }
    private void perform(Player player, Action action) {
        if (!player.isOnline()) return;
        if (!player.hasPermission("enchantmentworkshop.admin")) { player.closeInventory(); say(player, "需要管理员权限。"); return; }
        try { action.run(); } catch (Exception ex) { say(player, "未保存：" + ex.getMessage()); }
    }
    public void shutdown() {
        prompts.clear();
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof Page) player.closeInventory();
    }
    public static String effectName(String type) {
        return switch (EffectType.valueOf(type)) {
            case LIFESTEAL -> "吸血"; case BONUS_DAMAGE -> "额外伤害"; case POTION_ON_HIT -> "命中施加药水";
            case AUTO_SMELT -> "自动熔炼"; case TELEKINESIS -> "磁力收集"; case EXPERIENCE_BOOST -> "经验增幅";
            case PASSIVE_POTION -> "装备药水增益"; case DAMAGE_REDUCTION -> "战斗减伤";
            case PROGRAM -> "可编程规则（触发 → 条件 → 动作）";
        };
    }
    private void choose(Player p,String title,List<String> options,int requested,Input selected,Action back){
        int page=Math.clamp(requested,0,Math.max(0,(options.size()-1)/45)); Page m=new Page(p,title+" · "+(page+1));
        for(int i=page*45;i<Math.min(options.size(),page*45+45);i++){String value=options.get(i);m.button(i%45,Material.PAPER,title.equals("世界")?value:EditorText.text(value),List.of("代码："+value,"点击选择"),()->selected.accept(value));}
        m.button(45,Material.ARROW,"上一页",List.of(),()->choose(p,title,options,page-1,selected,back));
        m.button(49,Material.ARROW,"返回",List.of(),back);
        m.button(53,Material.ARROW,"下一页",List.of(),()->choose(p,title,options,page+1,selected,back));p.openInventory(m.inventory);
    }
    private void ruleSet(DefinitionEditor.Draft d,String id,int index,String key,Object value){
        var all=editor().effects(d,id);var map=new LinkedHashMap<Object,Object>(all.get(index));
        if(key.equals("action") && !"FLIGHT".equals(value)){map.remove("flight-durability-cost");map.remove("flight-durability-interval-ticks");}
        if(value==null)map.remove(key);else map.put(key,value);all.set(index,map);d.yaml().set("enchantments."+id+".effects",all);
    }
    private void program(Player p,DefinitionEditor.Draft d,String id,int index){
        var data=editor().effects(d,id).get(index);Page m=new Page(p,"规则 "+(index+1)+" · 触发 / 条件 / 效果");
        String[] keys={"trigger","target","action","condition-mode"};
        List<List<String>> options=List.of(ProgramRule.TRIGGERS,List.of("SELF","OTHER"),ProgramRule.ACTIONS,List.of("ALL","ANY"));
        String[] labels={"触发行为","作用目标（自己 / 目标或来源）","执行动作","条件组合（全部 / 任一）"};
        for(int i=0;i<keys.length;i++){String key=keys[i];List<String> opts=options.get(i);m.button(i,Material.COMPARATOR,labels[i],List.of("当前："+EditorText.text(data.get(key))),()->choose(p,labels[Arrays.asList(keys).indexOf(key)],opts,0,v->{ruleSet(d,id,index,key,v);program(p,d,id,index);},()->program(p,d,id,index)));}
        m.button(9,Material.REDSTONE,"添加 / 修改条件",List.of("状态、世界、坐标区域、天气、时间、实体类型等"),()->choose(p,"选择条件",java.util.stream.Stream.concat(java.util.stream.Stream.of("none"),ProgramRule.CONDITIONS.stream()).toList(),0,key->{
            if(key.equals("none")){ruleSet(d,id,index,"conditions",Map.of());program(p,d,id,index);return;}
            Input save=v->{Map<String,Object> conditions=new LinkedHashMap<>();if(data.get("conditions") instanceof Map<?,?> old)old.forEach((k,x)->conditions.put(k.toString(),x));
                if(v.equals("删除"))conditions.remove(key);else{ProgramRule.validate(key,v);conditions.put(key,v);}ruleSet(d,id,index,"conditions",conditions);program(p,d,id,index);};
            if(key.equals("target-type"))choose(p,"服务器实体类型",Arrays.stream(org.bukkit.entity.EntityType.values()).filter(t->t!=org.bukkit.entity.EntityType.UNKNOWN).map(t->t.getKey().toString()).sorted().toList(),0,save,()->program(p,d,id,index));
            else if(key.equals("world"))choose(p,"世界",Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList(),0,save,()->program(p,d,id,index));
            else if(key.equals("gamemode"))choose(p,"游戏模式",List.of("SURVIVAL","CREATIVE","ADVENTURE","SPECTATOR"),0,save,()->program(p,d,id,index));
            else if(key.equals("weather"))choose(p,"天气",List.of("CLEAR","RAIN","THUNDER"),0,save,()->program(p,d,id,index));
            else if(List.of("sneaking","sprinting","swimming","on-ground","burning").contains(key))choose(p,"状态",List.of("true","false"),0,save,()->program(p,d,id,index));
            else ask(p,"输入「"+EditorText.text(key)+"」：范围用 min,max；区域 x1,y1,z1,x2,y2,z2；输入 删除 可移除此条件。",save,()->program(p,d,id,index));
        },()->program(p,d,id,index)));
        m.button(10,Material.MILK_BUCKET,"无条件",List.of("清空所有条件，仍按所选行为触发", "当前："+EditorText.text(data.containsKey("conditions")?data.get("conditions"):Map.of())),()->{ruleSet(d,id,index,"conditions",Map.of());program(p,d,id,index);});
        if(!"FLIGHT".equals(data.get("action")))m.button(12,Material.POTION,"选择药水效果",List.of("读取服务器全部药水效果，施加药水动作使用"),()->choose(p,"服务器药水效果",org.bukkit.Registry.POTION_EFFECT_TYPE.stream().map(t->t.getKey().toString()).sorted().toList(),0,v->{ruleSet(d,id,index,"potion",v);program(p,d,id,index);},()->program(p,d,id,index)));
        boolean flight="FLIGHT".equals(data.get("action"));
        int slot=18;
        List<String> fields=flight?List.of("chance","chance-per-level","cooldown-ticks"):List.of("value","per-level","chance","chance-per-level","cooldown-ticks","duration-ticks","amplifier","amplifier-per-level");
        for(String key:fields)m.button(slot++,Material.PAPER,LABELS.get(key),List.of("当前："+EditorText.text(data.get(key))),()->ask(p,"输入 "+LABELS.get(key),v->{ruleSet(d,id,index,key,DefinitionEditor.value(key,v));program(p,d,id,index);},()->program(p,d,id,index)));
        if(flight) {
            String key="flight-durability-cost";Object current=data.containsKey(key)?data.get(key):0;
            m.button(26,Material.ANVIL,LABELS.get(key),List.of("当前："+current,"每次从非飞行切换为飞行时消耗一次","飞行途中、落地和重复触发规则不消耗","整数 0～1000；遵循原版耐久与不可破坏属性"),()->ask(p,"输入 "+LABELS.get(key),v->{
                editor().setEffect(d,id,index,key,v);program(p,d,id,index);
            },()->program(p,d,id,index)));
            m.button(27,Material.FEATHER,"持续飞行与摔落保护",List.of("成功触发后无时限，耐久必须大于零","卸下装备或条件失效后结束飞行","装备在生效槽位时免疫摔落伤害","背包中存放的装备不提供保护"),null);
        }
        m.button(45,Material.ARROW,"返回",List.of(),()->edit(p,d,id));
        m.button(53,Material.BARRIER,"移除此规则",List.of("至少保留一条效果"),()->{editor().removeEffect(d,id,index);edit(p,d,id);});p.openInventory(m.inventory);
    }
    private void preview(Player p,DefinitionEditor.Draft d,String id)throws Exception{
        var settings=editor().settings(d);EnchantmentWorkshopPlugin.validateDefinitions(settings);var def=settings.enchantments().get(id);
        Page m=new Page(p,"预览与确认 · "+id);
        m.button(4,Material.ENCHANTED_BOOK,def.name(),List.of("ID: "+id,"适用："+EditorText.text(def.itemTag()),"槽位："+EditorText.text(def.activeSlots()),"等级：1～"+def.maxLevel(),plugin.canReload(settings)?"保存后立即生效":"保存后需完整重启"),null);
        for(int i=0;i<def.effects().size();i++){var e=def.effects().get(i);List<String> lines=new ArrayList<>();
            if(e.program()!=null){var r=e.program();lines.add("触发："+EditorText.text(r.trigger())+" → "+EditorText.text(r.target())+" → "+EditorText.text(r.action()));lines.add(r.conditions().isEmpty()?"无条件":r.any()?"任一条件满足":"全部条件满足");r.conditions().forEach((k,v)->lines.add(EditorText.text(k)+"："+EditorText.text(v)));}
            lines.add("概率："+e.chance()+" 冷却："+e.cooldownTicks()+" 刻");
            if(e.program()!=null && e.program().action().equals("FLIGHT")){
                lines.add(e.flightDurabilityCost()==0?"起飞耐久消耗：关闭":"每次起飞消耗 "+e.flightDurabilityCost()+" 点耐久");
                lines.add("飞行无时限；装备耐久必须大于零");lines.add("装备在生效槽位时免疫摔落伤害");
            } else {lines.add("数值："+e.value()+" 每级 +"+e.perLevel());lines.add("药水："+EditorText.text(e.potion())+" / "+e.durationTicks()+" 刻");}
            m.button(18+i,Material.BOOK,"效果 "+(i+1)+" · "+effectName(e.type().name()),lines,null);}
        m.button(45,Material.ARROW,"返回修改",List.of(),()->edit(p,d,id));m.button(49,Material.LIME_CONCRETE,"确认保存",List.of("预览不执行动作，不改变世界"),()->{say(p,editor().save(d));list(p,0);});p.openInventory(m.inventory);
    }
    private static String templateName(String id) {
        return switch (id) {
            case "lifesteal" -> "吸血之剑"; case "frostbite" -> "冰霜之剑"; case "critical" -> "暴击之剑";
            case "smelting" -> "自动熔炼镐"; case "telekinesis" -> "磁力工具"; case "wisdom" -> "经验工具";
            case "night_vision" -> "夜视头盔"; case "haste" -> "急迫工具"; case "swiftness" -> "疾行靴子";
            case "guardian" -> "守护胸甲"; default -> id;
        };
    }
    private static void say(Player player, String text) { player.sendMessage(Component.text("[附魔工坊] " + text, NamedTextColor.YELLOW)); }
}
