package cn.mc.enchantmentworkshop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import java.util.*;

public record ProgramRule(String trigger, String target, String action, boolean any, Map<String,String> conditions) {
    public static final List<String> TRIGGERS = List.of("ATTACK", "HURT", "BREAK", "KILL", "INTERACT", "SNEAK", "PERIODIC");
    public static final List<String> ACTIONS = List.of("POTION", "EXPLOSION", "FLIGHT", "HEAL", "IGNITE", "LAUNCH");
    public static final List<String> CONDITIONS = List.of("sneaking", "sprinting", "swimming", "on-ground", "burning", "health-below", "food-below", "world", "biome", "weather", "time", "height", "box", "permission", "target-type", "gamemode");
    public ProgramRule { conditions = Map.copyOf(conditions); }
    public static ProgramRule parse(ConfigurationSection s) {
        String trigger=s.getString("trigger", "ATTACK"), target=s.getString("target", "SELF"), action=s.getString("action", "POTION");
        if (!TRIGGERS.contains(trigger) || !List.of("SELF", "OTHER").contains(target) || !ACTIONS.contains(action)) throw new IllegalArgumentException("未知触发器、目标或动作");
        if (target.equals("OTHER") && !List.of("ATTACK", "HURT").contains(trigger)) throw new IllegalArgumentException("只有攻击命中 / 受到伤害时，才能选择攻击目标或伤害来源");
        if (action.equals("FLIGHT") && !target.equals("SELF")) throw new IllegalArgumentException("飞行只能作用于自己");
        Map<String,String> map=new LinkedHashMap<>();
        ConfigurationSection conditions=s.getConfigurationSection("conditions");
        if (s.contains("conditions") && conditions==null) throw new IllegalArgumentException("conditions 必须是配置对象");
        if(conditions!=null) for(String key:conditions.getKeys(false)) {
            if(!CONDITIONS.contains(key)) throw new IllegalArgumentException("未知条件 "+key);
            String value=Objects.toString(conditions.get(key)); validate(key,value); map.put(key,value);
        }
        String mode=s.getString("condition-mode","ALL");
        if(!List.of("ALL","ANY").contains(mode)) throw new IllegalArgumentException("条件组合应为 ALL（全部满足）或 ANY（任一满足）");
        return new ProgramRule(trigger,target,action,mode.equals("ANY"),map);
    }
    public static void validate(String key,String value) {
        switch(key) {
            case "sneaking","sprinting","swimming","on-ground","burning" -> { if(!List.of("true","false").contains(value)) throw new IllegalArgumentException("状态条件请输入 true（是）或 false（否）"); }
            case "weather" -> { if(!List.of("CLEAR","RAIN","THUNDER").contains(value)) throw new IllegalArgumentException("天气：CLEAR（晴天）/ RAIN（下雨）/ THUNDER（雷暴）"); }
            case "gamemode" -> org.bukkit.GameMode.valueOf(value);
            case "health-below","food-below" -> numbers(value,1);
            case "time","height" -> { double[] n=numbers(value,2); if(n[0]>n[1])throw new IllegalArgumentException("范围下界不能大于上界"); }
            case "box" -> { double[] n=numbers(value,6); for(int i=0;i<3;i++)if(n[i]>n[i+3])throw new IllegalArgumentException("区域格式：x1,y1,z1,x2,y2,z2（小坐标在前）"); }
            default -> { if(value.isBlank())throw new IllegalArgumentException("条件值不能为空"); }
        }
    }
    private static double[] numbers(String value,int count) {
        try { double[] n=Arrays.stream(value.split(",")).map(String::trim).mapToDouble(Double::parseDouble).toArray();
            if(n.length!=count || Arrays.stream(n).anyMatch(x->!Double.isFinite(x)))throw new IllegalArgumentException(); return n;
        } catch(Exception ex) {throw new IllegalArgumentException("需要 "+count+" 个有限数字，以英文逗号分隔");}
    }
    public boolean matches(Player p, LivingEntity other) {
        if(conditions.isEmpty())return true;
        return any ? conditions.entrySet().stream().anyMatch(e->test(p,other,e.getKey(),e.getValue())) : conditions.entrySet().stream().allMatch(e->test(p,other,e.getKey(),e.getValue()));
    }
    private boolean test(Player p,LivingEntity other,String key,String v) {
        var l=p.getLocation();
        return switch(key) {
            case "sneaking" -> p.isSneaking()==Boolean.parseBoolean(v);
            case "sprinting" -> p.isSprinting()==Boolean.parseBoolean(v);
            case "swimming" -> p.isSwimming()==Boolean.parseBoolean(v);
            case "on-ground" -> p.isOnGround()==Boolean.parseBoolean(v);
            case "burning" -> (p.getFireTicks()>0)==Boolean.parseBoolean(v);
            case "health-below" -> p.getHealth()<Double.parseDouble(v);
            case "food-below" -> p.getFoodLevel()<Double.parseDouble(v);
            case "world" -> p.getWorld().getName().equals(v);
            case "biome" -> l.getBlock().getBiome().getKey().toString().equals(v);
            case "weather" -> v.equals(p.getWorld().isThundering()?"THUNDER":p.getWorld().hasStorm()?"RAIN":"CLEAR");
            case "time" -> {double[] n=numbers(v,2); yield p.getWorld().getTime()>=n[0]&&p.getWorld().getTime()<=n[1];}
            case "height" -> {double[] n=numbers(v,2);yield l.getY()>=n[0]&&l.getY()<=n[1];}
            case "box" -> {double[] n=numbers(v,6);yield l.getX()>=n[0]&&l.getY()>=n[1]&&l.getZ()>=n[2]&&l.getX()<=n[3]&&l.getY()<=n[4]&&l.getZ()<=n[5];}
            case "permission" -> p.hasPermission(v);
            case "target-type" -> other!=null && other.getType().getKey().toString().equals(v);
            case "gamemode" -> p.getGameMode().name().equals(v);
            default -> false;
        };
    }
}
