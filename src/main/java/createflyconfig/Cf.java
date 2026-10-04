package createflyconfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * create-fly 配置 API 的反射入口。
 *
 * <p>本 mod 对 Create 是<b>零编译期依赖</b>：所有类型都通过 {@link Class#forName} 拿，
 * 字段名/方法名对不上（Create 升级改名、或根本没装 Create）时只记一条日志并跳过，
 * 不允许抛出异常到游戏主循环。
 */
public final class Cf {

    public static final Logger LOG = LoggerFactory.getLogger("create-fly-config");

    /** 服务端/通用配置总线（有 common() / server() 静态方法）。 */
    public static final String CLS_INFRA_ALL = "com.zurrtum.create.infrastructure.config.AllConfigs";
    /** 客户端配置总线（有 client() 静态方法，返回 CClient）。 */
    public static final String CLS_CLIENT_ALL = "com.zurrtum.create.client.infrastructure.config.AllConfigs";
    /** 配置基类，所有 CWorldGen / CRecipes 这类子配置都继承它。 */
    public static final String CLS_CONFIG_BASE = "com.zurrtum.create.catnip.config.ConfigBase";
    /** 配置值的外层封装（ConfigBool / ConfigInt / ConfigFloat / ConfigString / ConfigEnum / ConfigGroup）。 */
    public static final String CLS_CVALUE = "com.zurrtum.create.catnip.config.ConfigBase$CValue";
    /** 纯 UI 分组（"client" / "kinetics" 这种），本身不可编辑。 */
    public static final String CLS_GROUP = "com.zurrtum.create.catnip.config.ConfigBase$ConfigGroup";
    /** 配置值的内层实现（BooleanValue / IntValue / FloatValue / StringValue / EnumValue），持有 builder。 */
    public static final String CLS_CONFIG_VALUE = "com.zurrtum.create.catnip.config.ConfigValue";

    private Cf() {
    }

    /** 找类，失败返回 null。 */
    public static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            LOG.warn("[create-fly-config] Class {} not found (Create missing or renamed), skipping: {}", name, t.toString());
            return null;
        }
    }

    /** 调静态无参方法，失败返回 null。 */
    public static Object staticCall(String className, String methodName) {
        try {
            Class<?> c = Class.forName(className);
            Method m = c.getMethod(methodName);
            return m.invoke(null);
        } catch (Throwable t) {
            LOG.warn("[create-fly-config] Call to {}.{}() failed, skipping: {}", className, methodName, t.toString());
            return null;
        }
    }

    /** 找 public 方法（含继承），失败返回 null。 */
    public static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            Method m = owner.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 找字段（含父类私有字段，需要时逐个父类找），失败返回 null。 */
    public static Field findField(Class<?> owner, String name) {
        Class<?> c = owner;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 读字段，失败返回 null。 */
    public static Object get(Field f, Object target) {
        if (f == null || target == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调方法，失败返回 null。 */
    public static Object call(Method m, Object target, Object... args) {
        if (m == null || target == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            LOG.warn("[create-fly-config] Call to {} failed, skipping: {}", m, t.toString());
            return null;
        }
    }

    /** 调方法并当成字符串（用于 getName()），失败返回 null。 */
    public static String callString(Method m, Object target) {
        Object o = call(m, target);
        return o instanceof String ? (String) o : null;
    }

    /** 读 public 数值字段（IntValue.min / FloatValue.max 这种），不存在返回兜底值。 */
    public static int intField(Object target, String name, int fallback) {
        Object o = get(findField(target == null ? null : target.getClass(), name), target);
        return o instanceof Number ? ((Number) o).intValue() : fallback;
    }

    public static float floatField(Object target, String name, float fallback) {
        Object o = get(findField(target == null ? null : target.getClass(), name), target);
        return o instanceof Number ? ((Number) o).floatValue() : fallback;
    }
}
