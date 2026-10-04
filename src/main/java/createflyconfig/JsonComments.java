package createflyconfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 从 {@code config/create/<type>.json} 里读回 Create 自己写的注释，作为 UI tooltip。
 *
 * <p>纯锦上添花：文件不存在 / 解析失败 / 路径对不上都只是没有 tooltip，不影响读写配置。
 * 之所以做"按路径 + 按键名"两级匹配，是因为分组名与字段名不一定一致
 * （例如 CClient 字段 {@code overlay} 在 JSON 里叫 {@code goggleOverlay}），路径对不上时按键名兜底。
 */
public final class JsonComments {

    private final Map<String, String[]> byPath = new HashMap<>();
    private final Map<String, String[]> byKey = new HashMap<>();

    private JsonComments() {
    }

    public static JsonComments load(String fileName) {
        JsonComments c = new JsonComments();
        try {
            File dir = new File(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile(), "create");
            File f = new File(dir, fileName + ".json");
            if (!f.isFile()) {
                return c;
            }
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (root != null && root.isJsonObject()) {
                c.walk(root.getAsJsonObject(), "");
            }
        } catch (Throwable t) {
            Cf.LOG.debug("[create-fly-config] Failed to read comments from {} (ignored): {}", fileName, t.toString());
        }
        return c;
    }

    private void walk(JsonObject obj, String prefix) {
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            String key = e.getKey();
            if ("comment".equals(key) || !e.getValue().isJsonObject()) {
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            JsonObject child = e.getValue().getAsJsonObject();
            if (child.has("value")) {
                String[] comment = readComment(child.get("comment"));
                if (comment.length > 0) {
                    byPath.putIfAbsent(path, comment);
                    byKey.putIfAbsent(key, comment);
                }
            }
            walk(child, path);
        }
    }

    private static String[] readComment(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return new String[0];
        }
        if (e.isJsonArray()) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (JsonElement el : e.getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
            return out.toArray(new String[0]);
        }
        if (e.isJsonPrimitive()) {
            return new String[]{e.getAsString()};
        }
        return new String[0];
    }

    /** 先按 JSON 路径找，找不到再按最后一段键名找。 */
    public String[] lookup(String path, String key) {
        String[] c = path == null ? null : byPath.get(path);
        if (c != null) {
            return c;
        }
        c = byKey.get(key);
        return c == null ? new String[0] : c;
    }
}
