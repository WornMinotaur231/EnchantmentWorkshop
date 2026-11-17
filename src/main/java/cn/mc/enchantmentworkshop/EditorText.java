package cn.mc.enchantmentworkshop;

import java.util.*;

/** Display-only translations. Configuration keys remain stable and portable. */
public final class EditorText {
    private EditorText() {}
    private static final Map<String, String> WORDS = Map.ofEntries(
        Map.entry("none", "无条件（清空条件限制）"), Map.entry("ATTACK", "攻击命中"), Map.entry("HURT", "受到伤害"), Map.entry("BREAK", "破坏方块"),
        Map.entry("KILL", "击杀生物"), Map.entry("INTERACT", "交互 / 右键"), Map.entry("SNEAK", "开始潜行"), Map.entry("PERIODIC", "定期检查"),
        Map.entry("SELF", "自己"), Map.entry("OTHER", "攻击目标 / 伤害来源"),
        Map.entry("POTION", "施加药水效果"), Map.entry("EXPLOSION", "产生爆炸"), Map.entry("FLIGHT", "装备飞行"),
        Map.entry("HEAL", "恢复生命"), Map.entry("IGNITE", "点燃目标"), Map.entry("LAUNCH", "向上弹射"),
        Map.entry("ALL", "满足全部条件"), Map.entry("ANY", "满足任一条件"),
        Map.entry("sneaking", "正在潜行"), Map.entry("sprinting", "正在疾跑"), Map.entry("swimming", "正在游泳"),
        Map.entry("on-ground", "站在地面"), Map.entry("burning", "正在燃烧"), Map.entry("health-below", "生命值低于"),
        Map.entry("food-below", "饥饿值低于"), Map.entry("world", "所在世界"), Map.entry("biome", "所在生物群系"),
        Map.entry("weather", "天气"), Map.entry("time", "世界时间范围"), Map.entry("height", "高度范围"),
        Map.entry("box", "坐标区域"), Map.entry("permission", "拥有权限"), Map.entry("target-type", "目标实体类型"), Map.entry("gamemode", "游戏模式"),
        Map.entry("CLEAR", "晴天"), Map.entry("RAIN", "下雨"), Map.entry("THUNDER", "雷暴"),
        Map.entry("SURVIVAL", "生存模式"), Map.entry("CREATIVE", "创造模式"), Map.entry("ADVENTURE", "冒险模式"), Map.entry("SPECTATOR", "旁观模式"),
        Map.entry("true", "是 / 开启"), Map.entry("false", "否 / 关闭"),
        Map.entry("mainhand", "主手"), Map.entry("offhand", "副手"), Map.entry("hand", "双手"),
        Map.entry("head", "头部"), Map.entry("chest", "胸部"), Map.entry("legs", "腿部"), Map.entry("feet", "脚部"), Map.entry("armor", "全部护甲"), Map.entry("any", "任意槽位"),
        Map.entry("minecraft:swords", "剑类"), Map.entry("minecraft:axes", "斧类"), Map.entry("minecraft:pickaxes", "镐类"),
        Map.entry("minecraft:enchantable/mining", "采矿工具"), Map.entry("minecraft:enchantable/durability", "可附耐久的物品"),
        Map.entry("minecraft:enchantable/head_armor", "头盔"), Map.entry("minecraft:enchantable/chest_armor", "胸甲"),
        Map.entry("minecraft:enchantable/leg_armor", "护腿"), Map.entry("minecraft:enchantable/foot_armor", "靴子"), Map.entry("minecraft:enchantable/armor", "全部护甲"),
        Map.entry("minecraft:speed", "速度"), Map.entry("minecraft:slowness", "缓慢"), Map.entry("minecraft:haste", "急迫"),
        Map.entry("minecraft:mining_fatigue", "挖掘疲劳"), Map.entry("minecraft:strength", "力量"), Map.entry("minecraft:instant_health", "瞬间治疗"),
        Map.entry("minecraft:instant_damage", "瞬间伤害"), Map.entry("minecraft:jump_boost", "跳跃提升"), Map.entry("minecraft:nausea", "反胃"),
        Map.entry("minecraft:regeneration", "生命恢复"), Map.entry("minecraft:resistance", "抗性提升"), Map.entry("minecraft:fire_resistance", "抗火"),
        Map.entry("minecraft:water_breathing", "水下呼吸"), Map.entry("minecraft:invisibility", "隐身"), Map.entry("minecraft:blindness", "失明"),
        Map.entry("minecraft:night_vision", "夜视"), Map.entry("minecraft:hunger", "饥饿"), Map.entry("minecraft:weakness", "虚弱"),
        Map.entry("minecraft:poison", "中毒"), Map.entry("minecraft:wither", "凋零"), Map.entry("minecraft:health_boost", "生命提升"),
        Map.entry("minecraft:absorption", "伤害吸收"), Map.entry("minecraft:saturation", "饱和"), Map.entry("minecraft:glowing", "发光"),
        Map.entry("minecraft:levitation", "漂浮"), Map.entry("minecraft:luck", "幸运"), Map.entry("minecraft:unluck", "霉运"),
        Map.entry("minecraft:slow_falling", "缓降"), Map.entry("minecraft:conduit_power", "潮涌能量"), Map.entry("minecraft:dolphins_grace", "海豚的恩惠"),
        Map.entry("minecraft:bad_omen", "不祥之兆"), Map.entry("minecraft:hero_of_the_village", "村庄英雄"), Map.entry("minecraft:darkness", "黑暗"),
        Map.entry("minecraft:trial_omen", "试炼之兆"), Map.entry("minecraft:raid_omen", "袭击之兆"), Map.entry("minecraft:wind_charged", "蓄风"),
        Map.entry("minecraft:weaving", "盘丝"), Map.entry("minecraft:oozing", "渗浆"), Map.entry("minecraft:infested", "虫蚀")
    );
    public static String text(Object value) {
        if (value == null) return "默认";
        if (value instanceof Map<?, ?> map) return map.isEmpty() ? "无条件" : String.join("；", map.entrySet().stream().map(e -> text(e.getKey()) + "：" + text(e.getValue())).toList());
        if (value instanceof Collection<?> list) return list.isEmpty() ? "无" : String.join("、", list.stream().map(EditorText::text).toList());
        return WORDS.getOrDefault(value.toString(), value.toString());
    }
}
