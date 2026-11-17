# Enchantment Workshop / 附魔工坊 1.0.0

适用于 **Minecraft Java / Paper 26.3、Java 25** 的自定义附魔插件。玩家使用原版客户端即可，无需安装客户端模组或资源包。

内置 10 种战斗、采矿和装备附魔，支持通过游戏内编辑器、命令或 YAML 配置创建新附魔。使用 Paper 原生附魔注册接口，物品上的附魔 ID 为 `enchantmentworkshop:<ID>`，可用于附魔书、铁砧、附魔台和砂轮。

## 主要功能

- 从空白创建或复制附魔，设置名称、适用物品、等级和获取方式。
- 多选生效装备槽位和冲突附魔，支持保存前预览。
- 组合 8 类基础效果，或使用 `PROGRAM` 规则设置触发行为、条件和动作。
- 条件支持玩家状态、位置、天气等；选择「无条件」可清空条件限制。
- 装备飞行无固定时限，仅在每次起飞时消耗耐久；有效装备同时提供摔落伤害免疫。删除附魔有预览和确认步骤。
- 保存时校验配置、备份文件并检查多人编辑冲突。

快速开始见 [使用说明](docs/使用说明.md)，完整编辑流程见 [附魔工坊入门](docs/附魔工坊入门.md)。

## 安装

1. 将 `EnchantmentWorkshop-1.0.0.jar` 放进服务端的 `plugins` 文件夹。
2. **完整重启** Paper 26.3 服务端。首次启动生成 `plugins/EnchantmentWorkshop/enchantments.yml`。
3. 游戏内输入 `/ew list` 查看附魔，OP 可执行 `/ew give 你的名字 lifesteal 3` 获取吸血 III 附魔书。
4. 在铁砧左侧放适用装备、右侧放附魔书，正常消耗经验附魔。两本同等级附魔书可合成更高一级，不能超过配置上限。

服务端验证版本为 **Paper 26.3 build 252**。Purpur 26.3 继承 Paper API，预计可用，但未单独验证。不支持 Spigot、Folia、Fabric 或 NeoForge。不要将 `EnchantmentWorkshopProbe.jar` 测试工具放入正式服。

## 插件标识

对外名称为 **Enchantment Workshop / 附魔工坊**，服务端插件标识和数据目录名为 `EnchantmentWorkshop`。主命令为 `/ew`，别名为 `/enchantmentworkshop`。附魔 ID 使用 `enchantmentworkshop:<ID>`，权限为 `enchantmentworkshop.use` 和 `enchantmentworkshop.admin`。

## 内置附魔

| ID | 名称 | 上限 | 默认适用物品 | 默认效果 |
|---|---|---:|---|---|
| `lifesteal` | 吸血 | III | 剑 | 蓄力近战命中时 35% 概率恢复有效伤害的 8% / 12% / 16%，冷却 1 秒 |
| `frostbite` | 冰霜 | III | 剑 | 20% / 30% / 40% 概率使目标缓慢 I，持续 3 秒，冷却 2 秒 |
| `critical` | 致命一击 | III | 剑 | 20% 概率增加 35% / 50% / 65% 近战伤害，冷却 1 秒 |
| `smelting` | 自动熔炼 | I | 镐 | 粗铁→铁锭、粗金→金锭、粗铜→铜锭、远古残骸→下界合金碎片 |
| `telekinesis` | 磁力 | I | 采矿工具 | 采矿掉落收入背包，放不下的部分保留为地面掉落 |
| `wisdom` | 博学 | III | 主手持有的可附耐久物品 | 采矿和击杀生物时，增加 20% / 40% / 60% 原有掉落经验 |
| `night_vision` | 夜视 | I | 头盔 | 穿戴时持续获得夜视 |
| `haste` | 急迫 | II | 主手采矿工具 | 获得急迫 I / II |
| `swiftness` | 疾行 | II | 靴子 | 获得速度 I / II |
| `guardian` | 守护 | III | 胸甲 | 降低 4% / 8% / 12% 近战、横扫及弹射物伤害 |

默认可通过附魔台、普通村民附魔交易及使用相应原版随机附魔标签的战利品获取。`random-loot` 是参与原版随机附魔候选池，不会给每个宝箱额外塞一本书。已经生成的村民交易不会自动刷新；实验性村民交易平衡和自定义战利品数据包可能采用不同候选池。

采矿效果仅对生存/冒险模式生效。基础攻击效果（吸血、额外伤害、命中药水）仅由玩家主手直接近战触发，不由箭矢或横扫的附带伤害触发；守护可以抵挡弹射物。基础攻击效果默认要求攻击蓄力达到 85%。`PROGRAM` 使用独立触发规则，详见 [可编程附魔说明](docs/可编程附魔说明.md)。

## 命令与权限

`/enchantmentworkshop` 是 `/ew` 的别名。查询、给予和物品附魔命令中的 ID 可省略 `enchantmentworkshop:` 前缀；编辑器命令使用不带前缀的代码名称，例如 `lifesteal`。

| 命令 | 用途 | 权限 |
|---|---|---|
| `/ew`、`/ew help` | 查看帮助 | `enchantmentworkshop.use`，默认所有玩家 |
| `/ew list` | 可点击的附魔列表 | 同上 |
| `/ew info <ID>` | 查看效果、适用标签和获取方式 | 同上 |
| `/ew give <在线玩家> <ID> [等级] [数量]` | 给予原生附魔书，默认等级与数量均为 1 | `enchantmentworkshop.admin`，默认 OP |
| `/ew enchant <ID> [等级]` | 为主手物品附魔；校验适用物品、等级和冲突 | 同上 |
| `/ew remove <ID>` | 从主手装备或附魔书移除指定附魔 | 同上 |
| `/ew reload` | 校验并重载运行时效果与通用设置 | 同上 |
| `/ew editor` | 打开游戏内附魔工坊 | 同上 |
| `/ew create <新ID> <模板ID> [名称]` | 从模板创建附魔 | 同上 |
| `/ew copy <源ID> <新ID> [名称]` | 复制附魔 | 同上 |
| `/ew set <ID> <字段> <值>` | 修改附魔属性 | 同上 |
| `/ew effect <ID> add <类型>` | 添加效果 | 同上 |
| `/ew effect <ID> set <序号> <字段> <值>` | 修改效果参数 | 同上 |
| `/ew effect <ID> remove <序号>` | 删除一条效果 | 同上 |
| `/ew delete <ID>` | 预览删除整个附魔 | 同上 |
| `/ew delete <ID> confirm` | 确认刚刚预览的删除 | 同上 |
| `/ew validate`、`/ew status` | 检查配置与生效状态 | 同上 |
| `/ew cancel` | 取消编辑器聊天输入 | 同上 |

命令支持 Tab 补全。给予附魔书前会检查目标背包空槽，空间不足则拒绝整个操作。

示例：

```text
/ew give Steve lifesteal 3
/ew give Steve smelting 1 2
/ew enchant telekinesis
/ew info guardian
```

## 添加自己的附魔

编辑 **服务端生成的** `plugins/EnchantmentWorkshop/enchantments.yml`，在 `enchantments:` 下添加同级条目。例如增加有概率让目标虚弱的剑附魔：

```yaml
  weakening:
    name: 虚弱打击
    description: 近战命中时有概率让目标虚弱，随等级提高触发概率。
    enabled: true
    max-level: 3
    weight: 4
    anvil-cost: 4
    item-tag: minecraft:swords
    active-slots: [mainhand]
    conflicts: []
    min-cost: 10
    cost-per-level: 10
    cost-span: 20
    acquisition:
      enchanting-table: true
      villager-trade: true
      random-loot: true
    effects:
      - type: POTION_ON_HIT
        potion: minecraft:weakness
        duration-ticks: 80
        amplifier: 0
        chance: 0.20
        chance-per-level: 0.10
        cooldown-ticks: 40
```

保存后完整重启服务端，使用 `/ew give 你的名字 weakening 3`。同一条目的 `effects` 可以包含多个效果，最多 16 个。除了下表的基础效果，还可用 `PROGRAM` 配置触发行为、条件和动作，无需以模板为起点。详细字段和示例见 [可编程附魔说明](docs/可编程附魔说明.md)。超出已有动作能力的行为需要扩展 Java 实现。

### 效果字段

| 类型 | 效果 | 常用字段 |
|---|---|---|
| `LIFESTEAL` | 根据有效伤害恢复生命 | `value`、`per-level`、概率、冷却 |
| `BONUS_DAMAGE` | 增加基础事件伤害的比例 | `value`、`per-level`、概率、冷却 |
| `POTION_ON_HIT` | 给近战目标施加药水 | `potion`、`duration-ticks`、等级、概率、冷却 |
| `AUTO_SMELT` | 熔炼上述四类采矿掉落 | 无额外参数 |
| `TELEKINESIS` | 收集采矿掉落 | 无额外参数 |
| `EXPERIENCE_BOOST` | 增加原本应掉落的采矿/生物经验 | `value`、`per-level` |
| `PASSIVE_POTION` | 在指定装备槽位上持续施加药水 | `potion`、`duration-ticks`、等级 |
| `DAMAGE_REDUCTION` | 降低玩家受到的近战/弹射物伤害 | `value`、`per-level` |
| `PROGRAM` | 按触发行为和条件执行药水、治疗、飞行等动作 | `trigger`、`target`、`action`、`conditions` 及动作参数 |

- 数值公式：`value + per-level × (附魔等级 - 1)`。比例 `0.20` 表示 20%。
- 攻击触发概率：`chance + chance-per-level × (等级 - 1)`，最终限制在 0～1；默认 `chance: 1`。
- `cooldown-ticks` 适用于三种基础攻击效果和 `PROGRAM`；20 刻 = 1 秒。基础攻击效果默认 0，可编程规则的实际冷却至少为 20 刻。
- 药水等级：`amplifier + amplifier-per-level × (等级 - 1)`，`amplifier: 0` 是 I 级。
- `duration-ticks` 控制药水以及部分可编程动作的持续时间；被动药水每秒刷新，持续时间必须为 40～300 刻。不同动作的限制见可编程附魔说明。
- 总减伤最多 80%，总额外伤害/经验最多 +1000%；吸血不超过目标本次实际可扣除的生命，也不超过攻击者生命上限。
- 经验不足整数时采用概率取整，例如 1 点经验的 +20% 是 20% 概率额外 1 点，不是每次都加 1 点。

### 注册字段

`item-tag` 可写单项物品键（如 `@minecraft:diamond_sword`），也可使用现有的物品标签，如 `minecraft:swords`、`minecraft:axes`、`minecraft:pickaxes`、`minecraft:enchantable/mining`、`minecraft:enchantable/head_armor`、`minecraft:enchantable/chest_armor`、`minecraft:enchantable/foot_armor`。

`active-slots` 支持 `mainhand`、`offhand`、`hand`、`head`、`chest`、`legs`、`feet`、`armor`、`any`。基础攻击/采矿效果从主手读取；被动药水、守护和可编程规则会检查六个手持/装备槽。编辑器提供多选菜单，多个槽位取并集。

`conflicts` 使用完整附魔键，如 `minecraft:silk_touch` 或 `enchantmentworkshop:lifesteal`。`weight` 决定原版随机选择的相对权重；`min-cost + cost-per-level × (等级 - 1)` 是附魔强度下界，上界再加 `cost-span`。`anvil-cost` 是原版铁砧计算的附魔成本系数，最终费用仍包括原版修复惩罚等因素。

## 配置重载与存档

- 修改 `effects`、`description` 或 `settings`：使用 `/ew reload`。整个配置通过验证后才替换，失败会继续使用旧配置。
- 新增/删除 ID，修改名字、等级、适用物品、冲突、槽位、权重、附魔成本、获取途径或 `enabled`：必须完整重启。`/ew reload` 会明确拒绝这些变化。
- 若只是临时停用，建议保留已经投入使用的 ID，设置 `enabled: false` 并重启：保留原生注册和物品数据，关闭插件效果和本插件添加的获取标签。既有附魔书/物品依然遵循原版铁砧与砂轮规则。
- 移除插件、删除已有 ID 或更改 ID，可能使世界物品中的自定义附魔在重新加载时丢失。应先备份世界与玩家数据。不要使用 PlugMan 等工具热卸载，也不要用服务端 `/reload` 代替完整重启。
- `settings.disabled-worlds` 可按世界名称禁用运行时效果；`allow-pvp-effects: false` 可关闭本插件对玩家间战斗的攻击和防御效果。这些设置不移除物品附魔，也不禁用附魔台/交易获取。

自动熔炼保留时运已生成的数量，不额外制造熔炉经验；与精准采集冲突，即使其他插件强制合并了两者也优先保留精准采集行为。磁力只处理本次破坏产生的掉落，不扫描附近物品。取消破坏、取消伤害、被盾牌完全挡下的命中不会绕过这些限制。

装备药水效果在卸下后自然到期，速度/急迫默认最多约 3 秒，夜视最多约 12 秒。这样可以避免直接移除同类型效果而抹掉玩家饮用的药水或其他插件提供的效果。

## 构建

使用 JDK 25 和项目内的 Gradle Wrapper：

```powershell
$env:JAVA_HOME = '你的 JDK 25 文件夹'
.\gradlew.bat build
```

产物为 `build/libs/EnchantmentWorkshop-1.0.0.jar`。也可运行 `build.ps1 -JdkHome '你的 JDK 25 文件夹'`，成功后会将正式 JAR 复制到项目目录的上一级文件夹。

依赖锁定为 `io.papermc.paper:paper-api:26.3.build.252-stable`。插件 JAR 不包含服务端或第三方运行库，运行时由 Paper 提供 API；插件不访问外部网络。

`build.ps1 -Offline` 仅适用于自行准备好本地依赖的开发环境，仓库不附带这些依赖；普通构建请使用上面的联网方式。

## 测试

`src/test` 包含配置校验、迁移和规则参数的 JUnit 测试。运行 `gradlew build` 会执行这些测试，也可单独运行：

```powershell
.\gradlew.bat test
```

测试报告生成在 `build/reports/tests/test/index.html`，不提交到仓库。

`src/integration` 保留独立的服务端测试插件源码，需要自行准备隔离的 Paper 测试环境及对应编译依赖。仓库不提供测试服启动脚本或运行记录；普通构建不依赖测试服务器。测试工具 `EnchantmentWorkshopProbe.jar` 不用于正式服。

参考：[Paper 注册接口](https://docs.papermc.io/paper/dev/registries/)、[Paper 项目配置](https://docs.papermc.io/paper/dev/project-setup/)。

## 仓库文档

- [快速使用说明](docs/使用说明.md)
- [附魔工坊入门](docs/附魔工坊入门.md)
- [可编程附魔与飞行耐久](docs/可编程附魔说明.md)
- [附魔配置示例](examples/附魔配置示例.yml) / [通用配置示例](examples/通用配置示例.yml)

源码遵循 [MIT License](LICENSE)，第三方组件说明见 [NOTICE.md](NOTICE.md)。仓库包含 Gradle Wrapper 和测试源码；服务器、世界存档、缓存、构建产物及运行日志不纳入版本控制。正式 JAR 作为 Release 附件发布。
