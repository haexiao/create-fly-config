# Create Fly Config

给 [Create-Fly](https://github.com/ZurrTum/Create-Fly)（Create 的 Fabric 移植版，mod id 是 `create`）补上它一直缺失的游戏内配置界面：用 [Cloth Config](https://modrinth.com/mod/cloth-config) 做一个 [Mod Menu](https://modrinth.com/mod/modmenu) 配置入口，让你不用手改 JSON 就能改 Create 的设置。

Create-Fly `1.21.11-6.0.9-5` **完全没有配置界面**——作者公告里写明移植版 "configuration screens and custom commands are missing"：`fabric.mod.json` 里没有 `modmenu` 入口点，70 个客户端 mixin 里没有 `TitleScreen`/`PauseScreen` 注入，`mainMenuConfigButtonRow` / `ingameMenuConfigButtonRow` 这些配置项是从上游 Create 抄过来的死配置。但配置的**数据层是完整且公开的**，所以本 mod 在它上面补了缺失的界面层。

## 功能

- 给 **Create 的 Mod Menu 条目**加"配置"按钮（走 Mod Menu 的 `getProvidedConfigScreenFactories` API，因为 Create-Fly 自己没有实现 `ModMenuApi`），本 mod 自己的条目上也有一个。
- **10 个 tab，按主题合并**：`客户端·总设置`、`客户端·显示与音效`、`客户端·联动与地图`、`服务端·基础与配方`、`服务端·动力机械`、`服务端·移动装置`、`服务端·流体与物流`、`服务端·蓝图与装备`、`服务端·列车`、`通用·世界生成` —— Create-Fly 6.0.9-5 的 129 项合并到单页最多 24 行；一页里含多个分组时，分组前会打一行青色小标题。tab 太多时横向滚动（左右箭头/滚轮）。合并关系写在资源文件的 `tabGroups` 里，想再拆分或合并**不用改代码**，改这个就行。
- **透明背景**：条目背后不画任何贴图，直接透出下层界面；tab 与列表的滚动动画也关掉了，滚轮立即跟手。
- 按类型映射控件：布尔 → 开关；能取到范围的整数 → 滑条；取不到范围的整数/浮点 → 数字框；字符串 → 文本框；枚举 → 下拉框。
- 把 Create 自己写在 JSON 里的注释、解析出的取值范围、所属 JSON 文件路径显示为 tooltip；分组说明与联机限制挪到 tab 的悬停说明里，页面更短。
- 读写全部走 Create 自己的 API（`ConfigValue.get/set`、`Builder.save`），绝不绕开它直接改文件。

## 环境要求

| 组件 | 版本 |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.5+ |
| Fabric API | 不需要 |
| Mod Menu | 17.0.1-beta.1（17.0.1 亦可） |
| Cloth Config | 21.11.153 |
| Create-Fly | `create-fly-1.21.11-6.0.9-5.jar`（推荐，非强制） |

纯客户端。Create 写在 `recommends` 而不是 `depends`：万一没装 Create、或它升级后类名变了，本 mod 仍能加载，只是每个配置源记一条日志并显示"未找到"提示，不会让游戏起不来。

## 构建

```bash
./gradlew build          # -> build/libs/create-fly-config-fabric-mc1.21.11-1.0.0.jar
```

Gradle 9.x + JDK 21 toolchain（用 JDK 25 启动、`sourceCompatibility`/`targetCompatibility` 指向 21 也可以）。Create-Fly **不是**构建依赖——所有 Create 类型都是反射访问。

## 安装

把 jar 放进 `.minecraft/mods/`，启动 `1.21.11` 的 Fabric 配置，打开 Mod Menu，在 Create 条目（或 *Create Fly Config* 条目）上点"配置"。

## 实现要点

- **对 Create 零编译期依赖**：类、字段、方法全部用 `Class.forName` / `Field.get` / `Method.invoke` 拿。任何一环失败都只记一条日志并跳过对应项/页，不让异常逃到游戏主循环。
- **客户端配置实例**：`com.zurrtum.create.client.infrastructure.config.AllConfigs.client()`（移植版其实留了这个访问器）。服务端/通用走 `com.zurrtum.create.infrastructure.config.AllConfigs.server()/common()`。
- **树结构复刻 Create 自己的注册过程**：按 `ConfigBase#allValues`（构造器注册顺序）遍历，并重放 `ConfigGroup` 的压栈/回退算术（`if (base.depth > groupDepth) builder.pop(base.depth - groupDepth); base.depth = groupDepth; push(name); depth++`）以及嵌套 `ConfigBase` 的规则。这样还原出的键路径与 `config/create/*.json` 逐字一致，也才能把 JSON 里的注释取回来。
- **取值域**用 `IntValue`/`FloatValue` 上的 public `min`/`max` 字段，不靠正则解析注释里的 `Range: a ~ b`。
- **保存**：先把改动写进 Create 的内存值对象，再调 Create 的 `Builder.save()`（它会把整棵 JSON 重新写到 `config/create/<type>.json`）。拿不到 `Builder` 时退化成逐个 `ConfigValue.save()`。
- 编辑先暂存，点保存时才应用，保证顺序是"先写值 → 再写文件"。

## 界面：中文为主，英文注释在下面

界面整体中文优先，原始英文保留在下一层，方便对照英文 wiki：

- tab、分组标题、每个选项名都是中文；选项名后面还挂着 Create 原始键名（淡灰，如 `传动带最大长度 · maxBeltLength`），便于搜索和对照。
- 悬停提示：中文说明 → `— Create 原始说明 / original —` → Create 自己的英文原文 → 取值范围 → 对应的 json 文件。
- 枚举取值也译成中文（如 `双向同步 · SYNC_BOTH`）。
- 分组说明与联机限制放在 tab 的悬停说明里（中英双语），所以列表保持短。

这些全是**数据不是代码**：`src/main/resources/assets/create-fly-config/i18n/zh_cn.json` 里按 json 路径（`kinetics.maxBeltLength`）组织 `tabs` / `groups` / `options` 三块。加一行、改一句即可，其余代码不用动；查不到的项自动回退成 Create 原名与原文。

## 重要：联机时的服务端 / 通用设置

Fabric 版 Create **不做 server→client 配置同步**——同生态的 *Create: Connected (Fly Port)* 把它写在明面上：*"Config is not synced server→client in multiplayer, so toggles can disagree between sides."* 所以：

- `client.json`（Create · 客户端）→ 保存后立刻生效。
- `server.json` / `common.json` → **只在单人世界有效**。联机时以服务端自己的 `config/create/server.json` 为准，本地改这些不影响服务端。mod 在这两页的顶部提示和每一项的 tooltip 里都写了这一点。

## 已知限制

- 移植版以后新增的配置项会先以英文键名/原文显示，等往 `i18n/zh_cn.json` 补一行才有中文（刻意如此：Create 升级永远不会让界面崩或错位）。
- Cloth Config 21.x 没有浮点滑条，浮点项渲染成带范围校验的数字框。
- `overlayBackgroundColor` 这类颜色值仍是普通整数（ARGB），没挂取色器。
- Create 只在启动时读一次的设置（世界生成、部分配方开关）需要重启游戏或重连才生效；保存后 mod 会提示，但没法强制重载。

## 验收情况

完整验收要在游戏里做（Mod Menu 按钮、三个 tab 内容、改完保存退出再进）。反射层与中文资源另外做了离线验收：直接加载真实的 `create-fly-1.21.11-6.0.9-5.jar`，129 个配置项的路径与值全部和 `config/create/*.json` 一致；129 项都有中文名与中文说明、21 个分组有中文名、8 个枚举的全部取值都有中文；并且"改值 → 保存 → 读文件确认 → 改回"整条链路跑通。

## 许可证

MIT。
