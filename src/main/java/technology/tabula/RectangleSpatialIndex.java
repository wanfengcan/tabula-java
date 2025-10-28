package technology.tabula;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * 基于 STRtree 的矩形空间索引。
 *
 * <p>
 * 存储实现了 Rectangle 的对象，并使用 JTS 的 STRtree 提供快速的空间查询（点查找、相交查询等）。
 * 同时维护一个原始对象列表以便计算总体边界（bounding box）。
 * </p>
 *
 * @param <T> 存储的矩形类型，必须继承自 Rectangle
 */
public class RectangleSpatialIndex<T extends Rectangle> {

    /**
     * 后端的空间索引（R-tree 实现）。
     */
    private final STRtree si = new STRtree();

    /**
     * 存放所有添加到索引中的矩形对象（用于计算整体边界等）。
     */
    private final List<T> rectangles = new ArrayList<>();

    /**
     * 将一个矩形对象加入索引。
     *
     * 该方法会：
     * - 将对象加入内部列表 rectangles
     * - 将对象的包围盒作为 Envelope 插入到 STRtree 中（用于后续空间查询）
     *
     * @param te 要加入索引的矩形对象
     */
    public void add(T te) {
        rectangles.add(te);
        si.insert(new Envelope(te.getLeft(), te.getRight(), te.getBottom(), te.getTop()), te);
    }

    /**
     * 返回完全包含在给定矩形 r 内的所有对象列表。
     *
     * <p>
     * 实现步骤：
     * </p>
     * <ol>
     * <li>先使用 STRtree.query 获取与 r 的 Envelope 相交的候选集合（快速但可能包含部分相交项）</li>
     * <li>遍历候选集合，筛选出真正被 r 完全包含的对象（r.contains(ir)）</li>
     * <li>按照已弃用的矩形顺序对结果进行排序以保证稳定性</li>
     * </ol>
     *
     * @param r 用于包含测试的矩形区域
     * @return 被 r 完全包含的对象列表（可能为空）
     */
    public List<T> contains(Rectangle r) {
        List<T> intersection = si.query(new Envelope(r.getLeft(), r.getRight(), r.getTop(), r.getBottom()));
        List<T> rv = new ArrayList<T>();

        for (T ir : intersection) {
            if (r.contains(ir)) {
                rv.add(ir);
            }
        }

        Utils.sort(rv, Rectangle.ILL_DEFINED_ORDER);
        return rv;
    }

    /**
     * 返回与给定矩形 r 空间上相交的所有对象（基于 STRtree 的快速查询）。
     *
     * <p>
     * 注意：此方法直接返回 STRtree.query 的结果，不对结果做进一步筛选或排序，
     * 可能包含部分相交的对象。
     * </p>
     *
     * @param r 要查询的矩形区域
     * @return 与 r 相交的对象列表（未经过精确包含检查）
     */
    public List<T> intersects(Rectangle r) {
        return si.query(new Envelope(r.getLeft(), r.getRight(), r.getTop(), r.getBottom()));
    }

    /**
     * 返回索引中所有矩形对象的最小外接矩形（bounding box）。
     *
     * @return 包含索引中所有矩形的最小 Rectangle。如果索引为空则可能抛出异常（调用方需注意）
     */
    public Rectangle getBounds() {
        return Rectangle.boundingBoxOf(rectangles);
    }

}