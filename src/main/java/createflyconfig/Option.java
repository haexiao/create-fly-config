package createflyconfig;

import java.lang.reflect.Method;

/**
 * 一个可编辑的 Create 配置值（反射包装 Create 的
 * {@code ConfigBase$ConfigBool / ConfigInt / ConfigFloat / ConfigString / ConfigEnum}）。
 *
 * <p>值的读写全部走外层 {@code CValue.get() / CValue.set(Object)}；内层 {@code ConfigValue} 只用来拿
 * 默认值与所属 {@code Builder}（保存落盘用）。
 */
public final class Option extends Node {

    public enum Kind { BOOL, INT, FLOAT, STRING, ENUM }

    public final Kind kind;
    /** 该值在 config/create/*.json 里的键名。 */
    public final String key;
    /** Create 写在 JSON 里的注释（可能为空）。 */
    public final String[] comments;

    /** 外层封装：ConfigBase$CValue 实例。 */
    final Object cValue;
    /** 内层实现：ConfigValue 实例（IntValue / FloatValue / ...）。 */
    final Object inner;

    private final Method setter;
    private final Object current;
    private final Object defaultValue;

    /** INT 范围（拿不到时 hasIntRange=false）。 */
    public final boolean hasIntRange;
    public final int min;
    public final int max;
    /** FLOAT 范围（拿不到时 hasFloatRange=false）。 */
    public final boolean hasFloatRange;
    public final float fmin;
    public final float fmax;

    /** ENUM 的可选值类型；其它类型为 null。 */
    public final Class<?> enumClass;

    /** 该值在 JSON 里的路径，例如 client.goggleOverlay.overlayOffsetX（仅日志/注释匹配用）。 */
    private String path = "";

    private Option(Kind kind, String key, String[] comments, Object cValue, Object inner,
                   Method setter, Object current, Object defaultValue,
                   boolean hasIntRange, int min, int max,
                   boolean hasFloatRange, float fmin, float fmax,
                   Class<?> enumClass) {
        super(key);
        this.kind = kind;
        this.key = key;
        this.comments = comments;
        this.cValue = cValue;
        this.inner = inner;
        this.setter = setter;
        this.current = current;
        this.defaultValue = defaultValue;
        this.hasIntRange = hasIntRange;
        this.min = min;
        this.max = max;
        this.hasFloatRange = hasFloatRange;
        this.fmin = fmin;
        this.fmax = fmax;
        this.enumClass = enumClass;
    }

    /**
     * 尝试把一个 {@code CValue} 字段包装成 Option。任何一步反射失败都返回 null（调用方跳过该项）。
     */
    public static Option create(Object cValue, String[] comments) {
        try {
            Class<?> cValueClass = cValue.getClass();
            Class<?> cValueBase = Class.forName(Cf.CLS_CVALUE);
            if (!cValueBase.isAssignableFrom(cValueClass)) {
                return null;
            }
            Method getNameM = Cf.findMethod(cValueClass, "getName");
            String key = Cf.callString(getNameM, cValue);
            if (key == null || key.isEmpty()) {
                return null;
            }
            Method getter = Cf.findMethod(cValueClass, "get");
            Method setter = Cf.findMethod(cValueClass, "set", Object.class);
            if (getter == null || setter == null) {
                return null;
            }
            Object inner = Cf.get(Cf.findField(cValueBase, "value"), cValue);
            if (inner == null) {
                // register() 还没跑（配置尚未初始化）——跳过，不要报错
                Cf.LOG.debug("[create-fly-config] {} is not registered yet, skipping", key);
                return null;
            }
            Object current = Cf.call(getter, cValue);
            if (current == null) {
                return null;
            }
            Method defaultGetter = Cf.findMethod(inner.getClass(), "getDefault");
            Object def = Cf.call(defaultGetter, inner);

            String innerName = simpleName(inner.getClass());
            Kind kind;
            boolean hasIntRange = false;
            int min = Integer.MIN_VALUE;
            int max = Integer.MAX_VALUE;
            boolean hasFloatRange = false;
            float fmin = -Float.MAX_VALUE;
            float fmax = Float.MAX_VALUE;
            Class<?> enumClass = null;

            if (innerName.contains("BooleanValue")) {
                kind = Kind.BOOL;
            } else if (innerName.contains("IntValue")) {
                kind = Kind.INT;
                // IntValue.min / IntValue.max 是 public 字段，直接读（无需解析注释里的 "Range: a ~ b"）
                int iMin = Cf.intField(inner, "min", Integer.MIN_VALUE);
                int iMax = Cf.intField(inner, "max", Integer.MAX_VALUE);
                // Create 用 ±Integer.MAX_VALUE 表示"无边界"，这种就不画范围
                hasIntRange = iMin > Integer.MIN_VALUE && iMax < Integer.MAX_VALUE && iMin < iMax;
                min = iMin;
                max = iMax;
            } else if (innerName.contains("FloatValue")) {
                kind = Kind.FLOAT;
                float fLo = Cf.floatField(inner, "min", -Float.MAX_VALUE);
                float fHi = Cf.floatField(inner, "max", Float.MAX_VALUE);
                hasFloatRange = fHi < Float.MAX_VALUE / 2f && fLo > -Float.MAX_VALUE / 2f && fLo < fHi;
                fmin = fLo;
                fmax = fHi;
            } else if (innerName.contains("StringValue")) {
                kind = Kind.STRING;
            } else if (innerName.contains("EnumValue")) {
                kind = Kind.ENUM;
                enumClass = current.getClass();
            } else {
                // DoubleRawValue 之类没有 CValue 外壳的类型不会走到这里
                return null;
            }

            return new Option(kind, key, comments, cValue, inner, setter,
                    current, def, hasIntRange, min, max, hasFloatRange, fmin, fmax, enumClass);
        } catch (Throwable t) {
            Cf.LOG.warn("[create-fly-config] Failed to read a config entry, skipping: {}", t.toString());
            return null;
        }
    }

    private static String simpleName(Class<?> c) {
        String s = c.getSimpleName();
        return s == null || s.isEmpty() ? c.getName() : s;
    }

    public Object current() {
        return current;
    }

    public Object defaultValue() {
        return defaultValue;
    }

    public Object[] enumConstants() {
        return enumClass == null ? new Object[0] : enumClass.getEnumConstants();
    }

    /** 写回内存中的配置值（Create 立刻按新值工作，但还没落盘）。 */
    public void apply(Object value) {
        Cf.call(setter, cValue, value);
    }

    /** 内层 ConfigValue 实例（ConfigTree 用它取所属 Builder）。 */
    Object innerValue() {
        return inner;
    }

    void setPath(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
