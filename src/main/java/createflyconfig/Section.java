package createflyconfig;

import java.util.ArrayList;
import java.util.List;

/** 一个分组：Create 的 ConfigGroup，或 CRecipes / CWorldGen 这种嵌套 ConfigBase。 */
public final class Section extends Node {

    public final List<Node> children = new ArrayList<>();

    /** 该分组在 config/create/*.json 里的完整键路径（用于查中文文案）。 */
    public final String path;

    public Section(String name, String path) {
        super(name);
        this.path = path;
    }
}
