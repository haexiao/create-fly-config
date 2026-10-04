package createflyconfig;

import java.util.List;

/** 配置树节点：分组（{@link Section}）或可编辑项（{@link Option}）。 */
public abstract class Node {

    /** 显示名 / JSON 键名（来自 Create 的 CValue.getName()）。 */
    public final String name;

    protected Node(String name) {
        this.name = name;
    }

    public static int countOptions(List<Node> nodes) {
        int n = 0;
        for (Node node : nodes) {
            if (node instanceof Section s) {
                n += countOptions(s.children);
            } else {
                n++;
            }
        }
        return n;
    }
}
