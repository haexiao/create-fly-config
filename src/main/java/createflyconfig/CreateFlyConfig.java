package createflyconfig;

import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端初始化：只做一次探测日志，方便排查（真正的界面在 Mod Menu 里按需构建）。
 */
public final class CreateFlyConfig implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Cf.LOG.info("[create-fly-config] Create Fly Config loaded (Mod Menu config screen for create-fly)");
        try {
            for (ConfigSource source : ConfigSource.values()) {
                Object base = source.resolve();
                Cf.LOG.info("[create-fly-config] {} -> {}", source.fileName(),
                        base == null ? "unavailable (Create missing or classes renamed)" : base.getClass().getName());
            }
        } catch (Throwable t) {
            Cf.LOG.warn("[create-fly-config] Startup probe failed (harmless): {}", t.toString());
        }
    }
}
