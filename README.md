# Create Fly Config

Gives [Create-Fly](https://github.com/ZurrTum/Create-Fly) (the Fabric port of Create, mod id `create`) the in-game configuration screen it never shipped: it provides a [Mod Menu](https://modrinth.com/mod/modmenu) config button for Create, built with [Cloth Config](https://modrinth.com/mod/cloth-config), so you can edit Create's settings without touching JSON by hand.

Create-Fly `1.21.11-6.0.9-5` has **no configuration UI at all** — the announcement says ports are missing "configuration screens and custom commands", its `fabric.mod.json` has no `modmenu` entrypoint, the port's 70 client mixins contain no `TitleScreen`/`PauseScreen` hooks and the `mainMenuConfigButtonRow` / `ingameMenuConfigButtonRow` options are dead leftovers from upstream Create. The *data* layer of the config, however, is complete and public, so this mod supplies the missing view/controller on top of it.

## What it does

- Adds a **Config** button to Create's Mod Menu entry (Mod Menu's `getProvidedConfigScreenFactories` API) and one to this mod's own entry.
- **10 tabs, grouped by theme**: `客户端·总设置`, `客户端·显示与音效`, `客户端·联动与地图`, `服务端·基础与配方`, `服务端·动力机械`, `服务端·移动装置`, `服务端·流体与物流`, `服务端·蓝图与装备`, `服务端·列车`, `通用·世界生成` — the 129 options of Create-Fly 6.0.9-5 merged so that no page holds more than 24 rows. A page combining several groups prints a small cyan header per group. The tab bar scrolls horizontally when it overflows, and the merge map is data (`tabGroups` in the i18n file), so tabs can be re-split or merged without touching code.
- **Transparent background**: nothing is drawn behind the entries, so the screen underneath stays visible. Tab and list scrolling animations are off — the wheel moves the list immediately.
- Maps each value to the right Cloth Config widget: booleans → toggles, ints with a known range → sliders, ints/floats without one → numeric fields, strings → text fields, enums → dropdowns.
- Shows Create's own JSON comments (and the parsed min/max range, the JSON file path) as tooltips; each group's note and the multiplayer warning live in the tab's hover description.
- Reads and writes through Create's own API (`ConfigValue.get/set`, `Builder.save`), never by editing JSON behind Create's back.

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.5+ |
| Fabric API | not required |
| Mod Menu | 17.0.1-beta.1 (17.0.1 works too) |
| Cloth Config | 21.11.153 |
| Create-Fly | `create-fly-1.21.11-6.0.9-5.jar` (recommended, not mandatory) |

Client-side only. Create is declared with `recommends` rather than `depends`: if it is missing or its classes are renamed, the mod still loads, logs one line per config source and shows a short "not found" screen instead of refusing to start the game.

## Building

```bash
./gradlew build          # -> build/libs/create-fly-config-fabric-mc1.21.11-1.0.0.jar
```

Gradle 9.x + JDK 21 toolchain (a JDK 25 launcher with `sourceCompatibility`/`targetCompatibility` 21 also works). Create-Fly is *not* a build dependency — every Create type is reached through reflection.

## Installation

Drop the jar into `.minecraft/mods/`, launch the `1.21.11` Fabric profile, open Mod Menu and press **Config** on the Create entry (or on *Create Fly Config*).

## How it works

- **Zero compile-time dependency on Create.** Classes, fields and methods are resolved with `Class.forName` / `Field.get` / `Method.invoke`. Anything that fails is logged once and the affected entry/tab is skipped — never an exception into the game loop.
- **Client config instance**: `com.zurrtum.create.client.infrastructure.config.AllConfigs.client()` (the port *does* keep a `client()` accessor, unlike what the missing UI suggests). Server/common come from `com.zurrtum.create.infrastructure.config.AllConfigs.server()/common()`.
- **Tree reconstruction mirrors Create's own registration**: iterate `ConfigBase#allValues` (the order the constructor registered things in) and replay `ConfigGroup`'s push/pop arithmetic (`if (base.depth > groupDepth) builder.pop(base.depth - groupDepth); base.depth = groupDepth; push(name); depth++`) plus the nested-`ConfigBase` rule. The resulting key paths match `config/create/*.json` exactly, which is what makes the JSON comments resolvable.
- **Ranges** come from the public `min`/`max` fields on `IntValue`/`FloatValue` — no regex scraping of `Range: a ~ b` comments.
- **Saving** applies each edited value to Create's in-memory value object, then calls Create's `Builder.save()` (it re-serialises the whole JSON tree to `config/create/<type>.json`). If a `Builder` cannot be resolved, it falls back to per-value `ConfigValue.save()`.
- Edits are staged until the screen is saved, so App order is always *write value → write file*.

## Interface: Chinese first, English underneath

The screen is Chinese-first with the original English kept one layer down, so it stays usable as a reference for the English wiki:

- Tabs, group headers and every option label are Chinese; the label also carries Create's raw key in grey (`传动带最大长度 · maxBeltLength`) for searching and cross-checking.
- Tooltips show the Chinese description, then `— Create 原始说明 / original —` and Create's own English text, then the value range and the JSON file it maps to.
- Enum values are translated too (`双向同步 · SYNC_BOTH`).
- Each tab's hover description carries the group note plus the multiplayer warning in both languages, so the lists stay short.

All of this is data, not code: `src/main/resources/assets/create-fly-config/i18n/zh_cn.json` holds `tabs`, `groups` and `options` keyed by the JSON path (`kinetics.maxBeltLength`). Add a key, edit a string, no rebuild of anything else — anything missing simply falls back to Create's original name and comment.

## Important: server / common settings in multiplayer

Fabric Create performs **no server→client config sync** — the sibling port *Create: Connected (Fly Port)* states it outright: *"Config is not synced server→client in multiplayer, so toggles can disagree between sides."* Therefore:

- `client.json` (`Create · 客户端`) → applies immediately after saving.
- `server.json` / `common.json` → **singleplayer only**. On a server the server's own `config/create/server.json` wins; editing these locally changes nothing on the server. The mod prints this warning on both tabs and in every tooltip of those pages.

## Known limitations

- Options added by a future Create version appear with their English key/comment until a line is added to `i18n/zh_cn.json` (by design — a Create update can never break the screen).
- Cloth Config 21.x has no float slider, so float options render as range-validated numeric fields.
- Values such as `overlayBackgroundColor` stay plain integers (ARGB) — no colour picker is attached to them.
- Changes that Create only reads at startup (world-gen, some recipe toggles) need a game restart or a reconnect; the mod says so after saving but cannot force a reload.

## Verification

Full acceptance requires running the game (Mod Menu button, tab contents, save-and-relaunch). The reflection layer and the Chinese resources were additionally verified offline against the real `create-fly-1.21.11-6.0.9-5.jar`: all 129 option paths and values matched `config/create/*.json` exactly; all 129 options have a Chinese name and description, all 21 groups have a Chinese name and every value of all 8 enums is translated; and a write-save-readround cycle changed the file and restored it.

## License

MIT.
