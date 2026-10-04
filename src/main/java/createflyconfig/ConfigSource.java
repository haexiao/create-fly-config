package createflyconfig;

/** Create 的三个配置源（对应的 json 在 config/create/ 下）。 */
public enum ConfigSource {

    /** client.json → AllConfigs.client()（CClient）。改完立刻生效。 */
    CLIENT("client", "Create · 客户端", true),
    /** server.json → AllConfigs.server()（CServer）。 */
    SERVER("server", "Create · 服务端", false),
    /** common.json → AllConfigs.common()（CCommon）。 */
    COMMON("common", "Create · 通用", false);

    private final String fileName;
    private final String title;
    private final boolean clientSide;

    ConfigSource(String fileName, String title, boolean clientSide) {
        this.fileName = fileName;
        this.title = title;
        this.clientSide = clientSide;
    }

    public String fileName() {
        return fileName;
    }

    public String title() {
        return title;
    }

    /** 只有 client.json 的项是纯客户端的；server/common 联机时以服务端文件为准。 */
    public boolean clientSide() {
        return clientSide;
    }

    /** 拿这个源的 ConfigBase 实例；Create 不在 / 类改名时返回 null。 */
    public Object resolve() {
        switch (this) {
            case CLIENT:
                // com.zurrtum.create.client.infrastructure.config.AllConfigs.client()
                return Cf.staticCall(Cf.CLS_CLIENT_ALL, "client");
            case SERVER:
                // com.zurrtum.create.infrastructure.config.AllConfigs.server()
                return Cf.staticCall(Cf.CLS_INFRA_ALL, "server");
            case COMMON:
            default:
                // com.zurrtum.create.infrastructure.config.AllConfigs.common()
                return Cf.staticCall(Cf.CLS_INFRA_ALL, "common");
        }
    }
}
