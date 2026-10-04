package createflyconfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.BooleanToggleBuilder;
import me.shedaniel.clothconfig2.impl.builders.EnumSelectorBuilder;
import me.shedaniel.clothconfig2.impl.builders.FloatFieldBuilder;
import me.shedaniel.clothconfig2.impl.builders.IntFieldBuilder;
import me.shedaniel.clothconfig2.impl.builders.IntSliderBuilder;
import me.shedaniel.clothconfig2.impl.builders.StringFieldBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * 用 Cloth Config 把 Create 的配置渲染成多个 tab。
 *
 * <p>布局：<b>每个配置分组（Create 的 ConfigGroup / 嵌套 ConfigBase）单独一个 tab</b>，
 * tab 名形如 {@code 服务端·移动装置}，所以单页列表很短、不用长距离滚动。
 * 界面透明（不铺任何背景贴图），tab 与列表都关闭滚动动画、滚轮直接跟手。
 *
 * <p>界面以中文为主、英文注释为辅（中文来自 {@link I18n}）：选项名与说明是中文，
 * 下面附 Create 原始的英文注释与原始键名，方便对照 wiki 与上游文档。
 *
 * <p>保存流程刻意与 Cloth 的调用顺序解耦：编辑时只把 (项, 新值) 记进 pending，
 * 真正的"写内存 + 落盘"发生在 savingRunnable 里，保证 App 顺序是先 set() 再 Builder.save()。
 */
public final class ConfigScreenBuilder {

    private static final int SLIDER_MAX_SPAN = 4096;

    private record Pending(Option option, Object value, ConfigTree tree) {
    }

    private ConfigScreenBuilder() {
    }

    public static Screen create(Screen parent) {
        try {
            return build(parent);
        } catch (Throwable t) {
            Cf.LOG.error("[create-fly-config] Failed to build the config screen", t);
            return errorScreen(parent, String.valueOf(t));
        }
    }

    private static Screen build(Screen parent) {
        List<ConfigTree> trees = new ArrayList<>();
        for (ConfigSource source : ConfigSource.values()) {
            ConfigTree tree = ConfigTree.load(source);
            if (tree != null) {
                trees.add(tree);
            }
        }

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("§6Create 配置界面 §r§8Create Fly Config"))
                // 透明背景：不画任何贴图，直接透出下层界面
                .setTransparentBackground(true)
                .setDoesConfirmSave(false)
                // 关掉滚动动画：实机反馈"滚动慢且卡"，这里让滚轮/拖动立即跟手
                .setShouldTabsSmoothScroll(false)
                .setShouldListSmoothScroll(false);
        ConfigEntryBuilder eb = builder.entryBuilder();

        if (trees.isEmpty()) {
            ConfigCategory category = builder.getOrCreateCategory(Text.literal("§c未找到 Create"));
            category.addEntry(eb.startTextDescription(Text.literal(
                    "§f没找到 Create (create-fly) 的配置，本页无法显示任何设置。")).build());
            category.addEntry(eb.startTextDescription(Text.literal(
                    "§7请确认已安装 create-fly；若升级后突然不显示，多半是配置项改名了，细节见 latest.log。")).build());
            category.addEntry(eb.startTextDescription(Text.literal(
                    "§8Create (create-fly) config classes were not found; see latest.log.")).build());
            return builder.build();
        }

        List<Pending> pending = new ArrayList<>();
        // 先把所有分组（含嵌套）摊平，按 tabGroups 指定的合并关系归页
        List<Placed> placed = new ArrayList<>();
        for (ConfigTree tree : trees) {
            collectSections(tree, tree.nodes, placed);
        }
        java.util.Map<String, Placed> byPath = new java.util.LinkedHashMap<>();
        for (Placed p : placed) {
            byPath.put(p.section().path, p);
        }
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (I18n.TabGroup group : I18n.tabGroups()) {
            List<Placed> members = new ArrayList<>();
            for (String path : group.sections()) {
                Placed p = byPath.get(path);
                if (p != null && used.add(path)) {
                    members.add(p);
                } else if (p == null) {
                    Cf.LOG.warn("[create-fly-config] tabGroup {} references unknown section {}", group.title(), path);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            ConfigCategory category = builder.getOrCreateCategory(Text.literal(group.title()));
            category.setDescription(() -> Optional.of(groupDescription(group, members)));
            fillCategory(category, eb, members, pending);
        }
        // 兜底：没被任何 tabGroup 收录的分组（Create 升级新增）各自一页，不至于消失
        for (Placed p : placed) {
            if (used.contains(p.section().path) || directCount(p.section()) == 0) {
                continue;
            }
            ConfigCategory category = builder.getOrCreateCategory(
                    Text.literal(prefix(p.source()) + "·" + groupName(p.section())));
            category.setDescription(() -> Optional.of(otherDescription(p.source())));
            fillCategory(category, eb, List.of(p), pending);
        }
        // 直接挂在源根上的零星项
        for (ConfigTree tree : trees) {
            List<Option> loose = new ArrayList<>();
            for (Node node : tree.nodes) {
                if (!(node instanceof Section)) {
                    loose.add((Option) node);
                }
            }
            if (loose.isEmpty()) {
                continue;
            }
            ConfigCategory category = builder.getOrCreateCategory(Text.literal(prefix(tree.source) + "·其它"));
            category.setDescription(() -> Optional.of(otherDescription(tree.source)));
            for (Option option : loose) {
                AbstractConfigListEntry entry = buildEntry(eb, tree, option, pending);
                if (entry != null) {
                    category.addEntry(entry);
                }
            }
        }
        builder.setSavingRunnable(() -> save(pending));
        return builder.build();
    }

    /** 一个分组 + 它属于哪个配置源。 */
    private record Placed(ConfigTree tree, Section section, ConfigSource source) {
    }

    private static void collectSections(ConfigTree tree, List<Node> nodes, List<Placed> out) {
        for (Node node : nodes) {
            if (node instanceof Section section) {
                out.add(new Placed(tree, section, tree.source));
                collectSections(tree, section.children, out);
            }
        }
    }

    private static int directCount(Section section) {
        int n = 0;
        for (Node child : section.children) {
            if (!(child instanceof Section)) {
                n++;
            }
        }
        return n;
    }

    private static String groupName(Section section) {
        I18n.GroupText group = I18n.group(section.path);
        return group == null || group.name() == null ? section.name : group.name();
    }

    /** 填充一页；一页含多个分组时，每个分组前插一行小标题，便于分辨。 */
    private static void fillCategory(ConfigCategory category, ConfigEntryBuilder eb, List<Placed> members,
                                    List<Pending> pending) {
        boolean headers = members.size() > 1;
        for (Placed p : members) {
            if (headers) {
                category.addEntry(eb.startTextDescription(Text.literal(
                        "§b" + groupName(p.section()) + " §8· " + p.section().name)).build());
            }
            for (Node child : p.section().children) {
                if (child instanceof Section) {
                    continue; // 子分组由 tabGroups 单独归页
                }
                AbstractConfigListEntry entry = buildEntry(eb, p.tree(), (Option) child, pending);
                if (entry != null) {
                    category.addEntry(entry);
                }
            }
        }
    }

    private static String prefix(ConfigSource source) {
        switch (source) {
            case CLIENT:
                return "客户端";
            case SERVER:
                return "服务端";
            case COMMON:
            default:
                return "通用";
        }
    }

    /** tab 悬停说明：标题 + 各分组项数 + 该文件的中英说明（+ 联机限制）。 */
    private static Text[] groupDescription(I18n.TabGroup group, List<Placed> members) {
        List<Text> lines = new ArrayList<>();
        int total = 0;
        StringBuilder parts = new StringBuilder();
        StringBuilder paths = new StringBuilder();
        for (Placed p : members) {
            int n = directCount(p.section());
            total += n;
            if (parts.length() > 0) {
                parts.append(" · ");
                paths.append(", ");
            }
            parts.append(groupName(p.section())).append(' ').append(n);
            paths.append(p.section().path);
        }
        ConfigSource source = members.get(0).source();
        lines.add(Text.literal("§e" + group.title() + " §8共 " + total + " 项"));
        lines.add(Text.literal("§7" + parts));
        lines.addAll(sourceNotes(source));
        lines.add(Text.literal("§8config/create/" + source.fileName() + ".json → " + paths));
        return lines.toArray(new Text[0]);
    }

    private static Text[] otherDescription(ConfigSource source) {
        List<Text> lines = new ArrayList<>(sourceNotes(source));
        lines.add(Text.literal("§8config/create/" + source.fileName() + ".json"));
        return lines.toArray(new Text[0]);
    }

    private static List<Text> sourceNotes(ConfigSource source) {
        List<Text> lines = new ArrayList<>();
        I18n.TabText tab = I18n.tab(source.fileName());
        if (tab != null) {
            for (String line : tab.zh()) {
                lines.add(Text.literal("§f" + line));
            }
            for (String line : tab.en()) {
                lines.add(Text.literal("§7" + line));
            }
        }
        if (!source.clientSide()) {
            lines.add(Text.literal("§c⚠ 联机时以服务端为准：服务端读自己的 config/create/"
                    + source.fileName() + ".json，本页只在单人世界内立即生效。"));
            lines.add(Text.literal("§8⚠ Not synced server→client in multiplayer; the server's file wins."));
        }
        return lines;
    }

    /** 单个配置项的控件映射；任何异常都只记日志并跳过该项。 */
    private static AbstractConfigListEntry buildEntry(ConfigEntryBuilder eb, ConfigTree tree, Option option,
                                                     List<Pending> pending) {
        I18n.OptionText text = I18n.option(option.path());
        String zhName = text == null ? null : text.name();
        Text label = zhName == null || zhName.isEmpty()
                ? Text.literal(option.key)
                : Text.literal("§f" + zhName + " §8· " + option.key);
        Text[] tooltip = tooltip(option, tree.source, text);
        try {
            switch (option.kind) {
                case BOOL: {
                    BooleanToggleBuilder b = eb.startBooleanToggle(label, (Boolean) option.current());
                    b.setDefaultValue((Boolean) option.defaultValue());
                    b.setSaveConsumer(v -> pending.add(new Pending(option, v, tree)));
                    if (tooltip.length > 0) {
                        b.setTooltip(tooltip);
                    }
                    return b.build();
                }
                case INT: {
                    int current = ((Number) option.current()).intValue();
                    int def = ((Number) option.defaultValue()).intValue();
                    // 范围很宽时滑条每像素跨太多（例如 256~32767），用带范围校验的数字框更实用
                    boolean slider = option.hasIntRange && (long) option.max - option.min <= SLIDER_MAX_SPAN;
                    if (slider) {
                        IntSliderBuilder b = eb.startIntSlider(label, clamp(current, option.min, option.max),
                                option.min, option.max);
                        b.setDefaultValue(clamp(def, option.min, option.max));
                        b.setSaveConsumer(v -> pending.add(new Pending(option, v, tree)));
                        if (tooltip.length > 0) {
                            b.setTooltip(tooltip);
                        }
                        return b.build();
                    }
                    // 范围未知：退化成数字输入框，不瞎猜范围
                    IntFieldBuilder b = eb.startIntField(label, current);
                    if (option.hasIntRange) {
                        b.setMin(option.min);
                        b.setMax(option.max);
                    }
                    b.setDefaultValue(def);
                    b.setSaveConsumer(v -> pending.add(new Pending(option, v, tree)));
                    if (tooltip.length > 0) {
                        b.setTooltip(tooltip);
                    }
                    return b.build();
                }
                case FLOAT: {
                    float current = ((Number) option.current()).floatValue();
                    float def = ((Number) option.defaultValue()).floatValue();
                    // Cloth Config 21.x 没有 float 滑条，用带范围校验的数字框
                    FloatFieldBuilder b = eb.startFloatField(label, current);
                    if (option.hasFloatRange) {
                        b.setMin(option.fmin);
                        b.setMax(option.fmax);
                    }
                    b.setDefaultValue(def);
                    b.setSaveConsumer(v -> pending.add(new Pending(option, v, tree)));
                    if (tooltip.length > 0) {
                        b.setTooltip(tooltip);
                    }
                    return b.build();
                }
                case STRING: {
                    String current = String.valueOf(option.current());
                    String def = option.defaultValue() == null ? "" : String.valueOf(option.defaultValue());
                    StringFieldBuilder b = eb.startStrField(label, current);
                    b.setDefaultValue(def);
                    b.setSaveConsumer(v -> pending.add(new Pending(option, v, tree)));
                    if (tooltip.length > 0) {
                        b.setTooltip(tooltip);
                    }
                    return b.build();
                }
                case ENUM:
                default: {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    EnumSelectorBuilder b = eb.startEnumSelector(label, (Class) option.enumClass,
                            (Enum) option.current());
                    b.setDefaultValue((Enum) option.defaultValue());
                    b.setSaveConsumer(v -> pending.add(new Pending(option, v, tree)));
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    EnumSelectorBuilder ignored = b.setEnumNameProvider(e -> {
                        Enum<?> value = (Enum<?>) e;
                        String zhValue = I18n.enumValue(option.path(), value.name());
                        return Text.literal(zhValue == null
                                ? value.name()
                                : "§f" + zhValue + " §8· " + value.name());
                    });
                    if (tooltip.length > 0) {
                        b.setTooltip(tooltip);
                    }
                    return b.build();
                }
            }
        } catch (Throwable t) {
            Cf.LOG.warn("[create-fly-config] Failed to build the widget for {}, skipping: {}", option.path(),
                    t.toString());
            return null;
        }
    }

    /** 悬停提示：中文说明 → 英文原文 → 取值范围 → 对应 json 路径（+ 联机提示）。 */
    private static Text[] tooltip(Option option, ConfigSource source, I18n.OptionText text) {
        List<Text> lines = new ArrayList<>();
        String[] zh = text == null ? new String[0] : text.desc();
        for (String line : zh) {
            if (isDefaultLine(line)) {
                continue; // 默认值靠"重置"按钮体现，别在提示里堆一遍
            }
            lines.add(Text.literal("§f" + line));
        }
        String[] en = text == null || text.en().length == 0 ? option.comments : text.en();
        List<String> enLines = new ArrayList<>();
        for (String line : en) {
            if (isDefaultLine(line)) {
                continue;
            }
            enLines.add(line);
        }
        if (!enLines.isEmpty()) {
            if (!lines.isEmpty()) {
                lines.add(Text.literal(""));
                lines.add(Text.literal("§8— Create 原始说明 / original —"));
            }
            for (String line : enLines) {
                lines.add(Text.literal("§7" + line));
            }
        }
        if (option.hasIntRange) {
            lines.add(Text.literal("§8取值范围: " + option.min + " ~ " + option.max));
        } else if (option.hasFloatRange) {
            lines.add(Text.literal("§8取值范围: " + option.fmin + " ~ " + option.fmax));
        }
        if (!source.clientSide()) {
            lines.add(Text.literal("§e联机时此项由服务器决定（服务端 config/create/" + source.fileName()
                    + ".json）；本界面只在单人世界内立即生效"));
            lines.add(Text.literal("§8Server-side value wins in multiplayer."));
        }
        lines.add(Text.literal("§8文件: config/create/" + source.fileName() + ".json"));
        return lines.toArray(new Text[0]);
    }

    private static boolean isDefaultLine(String line) {
        String t = line.trim();
        return t.startsWith("Default:") || t.startsWith("默认:") || t.startsWith("默认：");
    }

    private static void save(List<Pending> pending) {
        try {
            Set<ConfigTree> touched = new LinkedHashSet<>();
            for (Pending p : pending) {
                p.option().apply(p.value());
                touched.add(p.tree());
            }
            int ok = 0;
            for (ConfigTree tree : touched) {
                if (tree.save()) {
                    ok++;
                } else {
                    tree.saveFallback();
                }
            }
            Cf.LOG.info("[create-fly-config] Applied {} change(s), wrote {} config file(s)", pending.size(), ok);
            notifyPlayer(pending.size());
        } catch (Throwable t) {
            Cf.LOG.error("[create-fly-config] Error while saving the config", t);
        } finally {
            pending.clear();
        }
    }

    private static void notifyPlayer(int changed) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.sendMessage(Text.literal("§6[Create 配置] §f已保存 " + changed
                        + " 项改动；标注为服务端的设置联机时以服务端为准，部分项需重连或重启才生效"), false);
                mc.player.sendMessage(Text.literal("§8[Create Fly Config] saved " + changed
                        + " change(s); server-side entries follow the server, some need a relog/restart."), false);
            }
        } catch (Throwable ignored) {
            // 不在游戏内（例如主菜单）就不提示
        }
    }

    private static Screen errorScreen(Screen parent, String message) {
        try {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Text.literal("§6Create 配置界面"))
                    .setTransparentBackground(true);
            ConfigCategory category = builder.getOrCreateCategory(Text.literal("§c界面构建失败"));
            category.addEntry(builder.entryBuilder().startTextDescription(
                    Text.literal("§f构建 Create 配置界面时出错：" + message)).build());
            category.addEntry(builder.entryBuilder().startTextDescription(
                    Text.literal("§8Failed to build the config screen; see latest.log for the stack trace.")).build());
            return builder.build();
        } catch (Throwable t) {
            return parent;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
