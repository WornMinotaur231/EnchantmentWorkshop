package cn.mc.enchantmentworkshop;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EnchantService {
    private final EnchantmentWorkshopPlugin plugin;
    private final Map<String, Enchantment> registered = new LinkedHashMap<>();

    EnchantService(EnchantmentWorkshopPlugin plugin) {
        this.plugin = plugin;
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        plugin.settings().enchantments().keySet().forEach(id -> registered.put(id,
                registry.getOrThrow(EnchantmentWorkshopBootstrap.typedKey(id))));
    }

    public Enchantment enchantment(String id) {
        Enchantment enchantment = registered.get(id);
        if (enchantment == null) throw new IllegalArgumentException("未知附魔：" + id + "，使用 /ew list 查看列表。");
        return enchantment;
    }

    public EnchantDefinition definition(String id) {
        enchantment(id);
        return plugin.settings().enchantments().get(id);
    }

    public int level(ItemStack item, EnchantDefinition def) {
        if (!def.enabled() || item == null || item.getType().isAir()) return 0;
        Enchantment enchantment = registered.get(def.id());
        if (!enchantment.canEnchantItem(item)) return 0;
        return Math.min(item.getEnchantmentLevel(enchantment), def.maxLevel());
    }

    public ItemStack book(String id, int level) {
        return apply(new ItemStack(Material.ENCHANTED_BOOK), id, level);
    }

    public ItemStack apply(ItemStack source, String id, int level) {
        EnchantDefinition def = definition(id);
        if (!def.enabled()) throw new IllegalArgumentException("此附魔已停用。");
        if (level < 1 || level > def.maxLevel()) throw new IllegalArgumentException("等级范围为 1 至 " + def.maxLevel() + "。");
        if (source == null || source.getType().isAir()) throw new IllegalArgumentException("请先在主手拿着物品。");
        ItemStack result = source.clone();
        if (result.getType() == Material.BOOK) result = result.withType(Material.ENCHANTED_BOOK);
        var meta = result.getItemMeta();
        Enchantment enchantment = enchantment(id);
        Map<Enchantment, Integer> existing = meta instanceof EnchantmentStorageMeta book ? book.getStoredEnchants() : meta.getEnchants();
        for (Enchantment other : existing.keySet()) {
            if (!other.equals(enchantment) && (other.conflictsWith(enchantment) || enchantment.conflictsWith(other)))
                throw new IllegalArgumentException("与现有附魔冲突：" + other.key().asString());
        }
        if (meta instanceof EnchantmentStorageMeta book) book.addStoredEnchant(enchantment, level, false);
        else {
            if (!enchantment.canEnchantItem(result)) throw new IllegalArgumentException("此物品不适用该附魔（" + def.itemTag() + "）。");
            meta.addEnchant(enchantment, level, false);
        }
        result.setItemMeta(meta);
        return result;
    }

    public ItemStack remove(ItemStack source, String id) {
        Enchantment enchantment = enchantment(id);
        if (source == null || source.getType().isAir()) throw new IllegalArgumentException("请先在主手拿着物品。");
        ItemStack result = source.clone();
        var meta = result.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta book) book.removeStoredEnchant(enchantment);
        else meta.removeEnchant(enchantment);
        result.setItemMeta(meta);
        return result;
    }
}
