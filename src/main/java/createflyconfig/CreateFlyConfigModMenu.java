package createflyconfig;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.util.Collections;
import java.util.Map;
import net.minecraft.client.gui.screen.Screen;

/**
 * Mod Menu 入口。
 *
 * <p>除了给自己一个"配置"按钮，还通过 {@code getProvidedConfigScreenFactories()} 把界面挂到
 * <b>Create 自己的 Mod Menu 条目</b>上——create-fly 的 fabric.mod.json 里没有 modmenu 入口点，
 * 所以只能由第三方 mod 代它提供（Mod Menu 官方就是这么给 "minecraft" 提供配置界面的）。
 */
public final class CreateFlyConfigModMenu implements ModMenuApi {

    private static final ConfigScreenFactory<Screen> FACTORY = ConfigScreenBuilder::create;

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return FACTORY;
    }

    @Override
    public Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
        // create-fly 的 mod id 是 "create"
        return Collections.singletonMap("create", FACTORY);
    }
}
