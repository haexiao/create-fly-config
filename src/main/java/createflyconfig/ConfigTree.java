package createflyconfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** 一个配置源扫描后的结果，加上从 Create 自己的 Builder 拿到的保存入口。 */
public final class ConfigTree {

    public final ConfigSource source;
    public final List<Node> nodes;

    /** create 的 com.zurrtum.create.catnip.config.Builder。 */
    private final Object builder;

    private ConfigTree(ConfigSource source, List<Node> nodes, Object builder) {
        this.source = source;
        this.nodes = nodes;
        this.builder = builder;
    }

    /** 扫描一个配置源；Create 缺失或该类字段全变了 ⇒ 返回 null（UI 就少一个 tab，不炸）。 */
    public static ConfigTree load(ConfigSource source) {
        try {
            Object base = source.resolve();
            if (base == null) {
                Cf.LOG.warn("[create-fly-config] No config instance for {} (Create missing or classes renamed), skipping this tab",
                        source.fileName());
                return null;
            }
            Scanner.Result result = Scanner.scanRoot(base, source.fileName());
            if (result.nodes.isEmpty()) {
                Cf.LOG.warn("[create-fly-config] No options found for {}, skipping this tab", source.fileName());
                return null;
            }
            Object builder = null;
            if (result.anyInnerValue != null) {
                Field f = Cf.findField(Class.forName(Cf.CLS_CONFIG_VALUE), "builder");
                builder = Cf.get(f, result.anyInnerValue);
            }
            Cf.LOG.info("[create-fly-config] {}: {} options (builder {})", source.fileName(),
                    Node.countOptions(result.nodes), builder == null ? "unavailable, will save per value" : "available");
            return new ConfigTree(source, result.nodes, builder);
        } catch (Throwable t) {
            Cf.LOG.warn("[create-fly-config] Failed to scan {}, skipping this tab: {}", source.fileName(), t.toString());
            return null;
        }
    }

    public int optionCount() {
        return Node.countOptions(nodes);
    }

    /**
     * 用 Create 自己的 {@code Builder.save()} 落盘：它会把 JSON 层级 pop 回根，再把整份 JSON 写成
     * {@code config/create/<source>.json}。
     *
     * <p>不直接写文件的原因：Create 运行时持有内存副本，外部改文件它不会自动重载。
     */
    public boolean save() {
        if (builder == null) {
            return false;
        }
        try {
            Method m = builder.getClass().getMethod("save");
            m.invoke(builder);
            return true;
        } catch (Throwable t) {
            Cf.LOG.warn("[create-fly-config] Failed to save {}: {}", source.fileName(), t.toString());
            return false;
        }
    }

    /** 没有任何可保存入口（拿不到 Builder）时，退化成单独调每个内层 ConfigValue.save()。 */
    public void saveFallback() {
        for (Node node : nodes) {
            saveNodeFallback(node);
        }
    }

    private void saveNodeFallback(Node node) {
        if (node instanceof Section s) {
            for (Node child : s.children) {
                saveNodeFallback(child);
            }
            return;
        }
        if (!(node instanceof Option o)) {
            return;
        }
        try {
            Method m = Cf.findMethod(o.inner.getClass(), "save");
            Cf.call(m, o.inner);
        } catch (Throwable t) {
            Cf.LOG.debug("[create-fly-config] Per-value save failed: {}", t.toString());
        }
    }
}
