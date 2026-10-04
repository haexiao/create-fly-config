package createflyconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 中文界面文案（{@code assets/create-fly-config/i18n/zh_cn.json}）。
 *
 * <p>界面以中文为主、英文注释为辅：本资源里同时打包了 Create 原始的英文注释，
 * 这样即使玩家把 config/create/*.json 换成了中文注释版，英文行也仍然显示得出来。
 * 任何键查不到都退化成"显示 Create 原名"，不会因为 Create 升级加项而报错。
 */
public final class I18n {

    private static final String RESOURCE = "/assets/create-fly-config/i18n/zh_cn.json";

    /** 单个选项的文案。 */
    public record OptionText(String name, String[] desc, String[] en) {
    }

    /** 分组（子分类）文案。 */
    public record GroupText(String name, String en) {
    }

    /** 一个 tab 的标题与说明。 */
    public record TabText(String title, String[] zh, String[] en) {
    }

    /** 界面上的一个 tab：来源（client/server/common）+ 标题 + 合并进来的分组路径（有序）。 */
    public record TabGroup(String source, String title, java.util.List<String> sections) {
    }

    private static final Map<String, OptionText> OPTIONS = new HashMap<>();
    private static final Map<String, GroupText> GROUPS = new HashMap<>();
    private static final Map<String, TabText> TABS = new HashMap<>();
    private static final Map<String, Map<String, String>> ENUMS = new HashMap<>();
    private static final java.util.List<TabGroup> TAB_GROUPS = new java.util.ArrayList<>();
    private static boolean loaded;

    private I18n() {
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (InputStream in = I18n.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Cf.LOG.warn("[create-fly-config] Chinese resource {} not found; falling back to Create's original names", RESOURCE);
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.has("options")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("options").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    Map<String, String> enums = new HashMap<>();
                    if (o.has("enums")) {
                        for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("enums").entrySet()) {
                            enums.put(en.getKey(), en.getValue().getAsString());
                        }
                    }
                    if (!enums.isEmpty()) {
                        ENUMS.put(e.getKey(), enums);
                    }
                    OPTIONS.put(e.getKey(), new OptionText(str(o, "zh"), lines(o, "desc"), lines(o, "en")));
                }
            }
            if (root.has("groups")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("groups").entrySet()) {
                    JsonObject g = e.getValue().getAsJsonObject();
                    GROUPS.put(e.getKey(), new GroupText(str(g, "zh"), str(g, "en")));
                }
            }
            if (root.has("tabs")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("tabs").entrySet()) {
                    JsonObject t = e.getValue().getAsJsonObject();
                    TABS.put(e.getKey(), new TabText(str(t, "title"), lines(t, "zh"), lines(t, "en")));
                }
            }
            if (root.has("tabGroups")) {
                for (JsonElement el : root.getAsJsonArray("tabGroups")) {
                    JsonObject g = el.getAsJsonObject();
                    java.util.List<String> sections = new java.util.ArrayList<>();
                    if (g.has("sections")) {
                        for (JsonElement sec : g.getAsJsonArray("sections")) {
                            sections.add(sec.getAsString());
                        }
                    }
                    TAB_GROUPS.add(new TabGroup(str(g, "source"), str(g, "title"), sections));
                }
            }
            Cf.LOG.info("[create-fly-config] Chinese resource loaded: {} options / {} groups / {} tabs / {} tabGroups",
                    OPTIONS.size(), GROUPS.size(), TABS.size(), TAB_GROUPS.size());
        } catch (Throwable t) {
            Cf.LOG.warn("[create-fly-config] Failed to read the Chinese resource (falling back to English): {}", t.toString());
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String[] lines(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return new String[0];
        }
        if (e.isJsonArray()) {
            JsonArray arr = e.getAsJsonArray();
            String[] out = new String[arr.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = arr.get(i).getAsString();
            }
            return out;
        }
        return new String[]{e.getAsString()};
    }

    /** 配置项文案（中文名 + 中文说明 + 打包好的英文注释）；查不到返回 null。 */
    public static OptionText option(String path) {
        ensureLoaded();
        return OPTIONS.get(path);
    }

    /** 分组文案；查不到返回 null。 */
    public static GroupText group(String path) {
        ensureLoaded();
        return GROUPS.get(path);
    }

    /** tab 文案（按 client / server / common 键）；查不到返回 null。 */
    public static TabText tab(String key) {
        ensureLoaded();
        return TABS.get(key);
    }

    /** 界面 tab 布局（把细分组合并成一页），顺序即 tab 顺序。 */
    public static java.util.List<TabGroup> tabGroups() {
        ensureLoaded();
        return TAB_GROUPS;
    }

    /** 枚举值中文名；查不到返回 null。 */
    public static String enumValue(String path, String constant) {
        ensureLoaded();
        Map<String, String> m = ENUMS.get(path);
        return m == null ? null : m.get(constant);
    }
}
