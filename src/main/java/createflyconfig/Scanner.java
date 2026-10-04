package createflyconfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个 Create 的 ConfigBase 实例扫成 UI 用的树，并还原它写进 JSON 的层级。
 *
 * <p>不靠字段声明顺序猜，而是逐条复刻 Create 自己的注册行为（javap 反汇编确认）：
 * <ul>
 *   <li>以 {@code ConfigBase.allValues}（构造器里的注册顺序）为权威顺序遍历；</li>
 *   <li>{@code ConfigGroup.register(b)} = <i>if (base.depth &gt; groupDepth) b.pop(base.depth - groupDepth);
 *       base.depth = groupDepth; b.push(name); base.depth++</i> —— 即"回退到 groupDepth 层再压入自己"；</li>
 *   <li>嵌套子配置（{@code nested(depth, factory, ...)}）会先注册一个同名的 ConfigGroup（压入子配置名），
 *       再跑子配置的 registerAll，最后 <i>if (child.depth &gt; depth) b.pop(child.depth - depth)</i>。</li>
 * </ul>
 * 这样得到的 path 与 {@code config/create/*.json} 的键路径逐字一致（注释 tooltip 也才能对上）。
 */
public final class Scanner {

    /** 扫描结果。 */
    public static final class Result {
        public final List<Node> nodes = new ArrayList<>();
        public Object anyInnerValue;
    }

    /** 扫描过程中的绝对路径 / 分组栈。 */
    private static final class Ctx {
        final List<Node> root = new ArrayList<>();
        final List<Section> sections = new ArrayList<>();
        final List<String> path = new ArrayList<>();
        Object anyInnerValue;

        List<Node> container() {
            return sections.isEmpty() ? root : sections.get(sections.size() - 1).children;
        }

        void push(String name) {
            // Create 的 Builder.push 会按 "." 拆成多层
            for (String seg : name.split("\\.")) {
                if (seg.isEmpty()) {
                    continue;
                }
                path.add(seg);
                Section s = new Section(seg, String.join(".", path));
                container().add(s);
                sections.add(s);
            }
        }

        void pop(int levels) {
            for (int i = 0; i < levels && !path.isEmpty(); i++) {
                path.remove(path.size() - 1);
                if (!sections.isEmpty()) {
                    sections.remove(sections.size() - 1);
                }
            }
        }
    }

    private static Class<?> cValueBase;
    private static Class<?> configBase;
    private static Class<?> groupClass;
    private static Field allValuesField;
    private static Field childrenField;
    private static final Map<Class<?>, Method> NAME_METHODS = new HashMap<>();

    private Scanner() {
    }

    public static Result scanRoot(Object root) {
        Result r = new Result();
        Ctx ctx = new Ctx();
        try {
            scanBase(root, ctx);
        } catch (Throwable t) {
            Cf.LOG.warn("[create-fly-config] Error while scanning the config tree (remaining entries skipped): {}", t.toString());
        }
        prune(ctx.root);
        r.nodes.addAll(ctx.root);
        r.anyInnerValue = ctx.anyInnerValue;
        return r;
    }

    /** 入口：由 ConfigTree 设定本次扫描用哪份 json 的注释。 */
    public static Result scanRoot(Object root, String jsonFileName) {
        currentFileName = jsonFileName == null ? "" : jsonFileName;
        CommentsCache.reset();
        return scanRoot(root);
    }

    private static void scanBase(Object base, Ctx ctx) {
        Class<?> baseClass = configBase();
        if (baseClass == null || !baseClass.isInstance(base)) {
            return;
        }
        List<?> allValues = asList(Cf.get(allValuesField(), base));
        List<?> children = asList(Cf.get(childrenField(), base));

        int depth = 0;            // 复刻 ConfigBase.depth（新建对象时为 0）
        int lastGroupDepth = 0;   // 最近一个 ConfigGroup 的 groupDepth
        int childIndex = 0;

        for (Object cValue : allValues) {
            String name = nameOf(cValue);
            if (name == null) {
                continue;
            }
            if (isGroup(cValue)) {
                int groupDepth = Cf.intField(cValue, "groupDepth", 0);
                if (depth > groupDepth) {
                    ctx.pop(depth - groupDepth);
                }
                depth = groupDepth;
                ctx.push(name);
                depth++;
                lastGroupDepth = groupDepth;
                continue;
            }
            if (innerValueOf(cValue) == null) {
                // nested() 生成的子配置条目：provider 返回 null，真正的配置在 children 里按序对应
                if (children == null || childIndex >= children.size()) {
                    continue;
                }
                Object child = children.get(childIndex++);
                try {
                    scanBase(child, ctx);
                    int childDepth = Cf.intField(child, "depth", 0);
                    if (childDepth > lastGroupDepth) {
                        ctx.pop(childDepth - lastGroupDepth);
                    }
                } catch (Throwable t) {
                    Cf.LOG.warn("[create-fly-config] Failed to scan sub-config {}, skipping: {}", name, t.toString());
                }
                continue;
            }
            Option option = Option.create(cValue, commentFor(ctx.path, name));
            if (option == null) {
                continue;
            }
            List<String> full = new ArrayList<>(ctx.path);
            full.add(option.key);
            option.setPath(String.join(".", full));
            if (ctx.anyInnerValue == null) {
                ctx.anyInnerValue = option.inner;
            }
            ctx.container().add(option);
        }
    }

    private static List<?> asList(Object o) {
        return o instanceof List ? (List<?>) o : null;
    }

    private static boolean isGroup(Object value) {
        Class<?> group = groupClass();
        return group != null && group.isInstance(value);
    }

    private static Object innerValueOf(Object cValue) {
        try {
            Class<?> c = cValueBase();
            if (c == null) {
                return null;
            }
            return Cf.get(Cf.findField(c, "value"), cValue);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 去掉没有任何可编辑项的空分组。 */
    private static void prune(List<Node> nodes) {
        nodes.removeIf(n -> {
            if (n instanceof Section s) {
                prune(s.children);
                return s.children.isEmpty();
            }
            return false;
        });
    }

    private static String[] commentFor(List<String> parentPath, String key) {
        try {
            JsonComments comments = CommentsCache.get(currentFileName);
            if (comments == null) {
                return new String[0];
            }
            List<String> full = new ArrayList<>(parentPath);
            full.add(key);
            return comments.lookup(String.join(".", full), key);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static String currentFileName = "";

    private static final class CommentsCache {
        private static String loadedFor = null;
        private static JsonComments instance;

        static JsonComments get(String fileName) {
            if (fileName == null || fileName.isEmpty()) {
                return null;
            }
            if (!fileName.equals(loadedFor)) {
                instance = JsonComments.load(fileName);
                loadedFor = fileName;
            }
            return instance;
        }

        static void reset() {
            loadedFor = null;
            instance = null;
        }
    }

    private static String nameOf(Object value) {
        try {
            Method m = NAME_METHODS.computeIfAbsent(value.getClass(), c -> Cf.findMethod(c, "getName"));
            return Cf.callString(m, value);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> cValueBase() {
        if (cValueBase == null) {
            cValueBase = Cf.findClass(Cf.CLS_CVALUE);
        }
        return cValueBase;
    }

    private static Class<?> configBase() {
        if (configBase == null) {
            configBase = Cf.findClass(Cf.CLS_CONFIG_BASE);
        }
        return configBase;
    }

    private static Class<?> groupClass() {
        if (groupClass == null) {
            groupClass = Cf.findClass(Cf.CLS_GROUP);
        }
        return groupClass;
    }

    private static Field allValuesField() {
        if (allValuesField == null) {
            allValuesField = Cf.findField(configBase(), "allValues");
        }
        return allValuesField;
    }

    private static Field childrenField() {
        if (childrenField == null) {
            childrenField = Cf.findField(configBase(), "children");
        }
        return childrenField;
    }
}
