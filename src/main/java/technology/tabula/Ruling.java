package technology.tabula;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@SuppressWarnings("serial")
/**
 * 表示页面上的一条检测到的线段（ruling）。
 *
 * 该类包装了 Line2D.Float 并增加了：
 * - 对近似水平/近似垂直线的归一化（normalize）
 * - 方向判断（vertical/horizontal/oblique）
 * - 面向方向的访问器（position/start/end 等，仅对水平或垂直线有意义）
 * - 容错相交判断（nearlyIntersects / expand）
 * - 与矩形裁剪与求交点的工具（intersect / intersectionPoint）
 * - 批处理算法：裁剪列表、寻找所有水平-垂直交点、以及按方向折叠相近线段
 */
public class Ruling extends Line2D.Float {

    // 对于正交（垂直-水平）相交时使用的膨胀像素量（用于容忍少量偏差）
    private static int PERPENDICULAR_PIXEL_EXPAND_AMOUNT = 2;
    // 对于共线或平行线段比较时使用的膨胀像素量（通常为 1，因两端膨胀是可累加的）
    private static int COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT = 1;

    // 内部用于排序/扫描时表明对象类型
    private enum SOType {
        VERTICAL, HRIGHT, HLEFT
    }

    public Ruling(float top, float left, float width, float height) {
        this(new Point2D.Float(left, top), new Point2D.Float(left + width, top + height));
    }

    public Ruling(Point2D p1, Point2D p2) {
        super(p1, p2);
        this.normalize();
    }

    /**
     * 将几乎水平或几乎垂直的线段纠正为严格水平/严格垂直（减少数值噪声）。
     *
     * 如果角度接近 0 或 180 则把 y2 设为 y1；
     * 如果角度接近 90 或 270 则把 x2 设为 x1。
     */
    public void normalize() {

        double angle = this.getAngle();
        if (Utils.within(angle, 0, 1) || Utils.within(angle, 180, 1)) { // almost horizontal
            this.setLine(this.x1, this.y1, this.x2, this.y1);
        } else if (Utils.within(angle, 90, 1) || Utils.within(angle, 270, 1)) { // almost vertical
            this.setLine(this.x1, this.y1, this.x1, this.y2);
        }
    }

    /**
     * 是否严格为垂直线（x1 == x2，且非零长度）。
     */
    public boolean vertical() {
        return this.length() > 0 && Utils.feq(this.x1, this.x2); // diff < ORIENTATION_CHECK_THRESHOLD;
    }

    /**
     * 是否严格为水平线（y1 == y2，且非零长度）。
     */
    public boolean horizontal() {
        return this.length() > 0 && Utils.feq(this.y1, this.y2); // diff < ORIENTATION_CHECK_THRESHOLD;
    }

    /**
     * 是否为斜线（既非水平也非垂直）。
     */
    public boolean oblique() {
        return !(this.vertical() || this.horizontal());
    }

    // attributes that make sense only for non-oblique lines
    // these are used to have a single collapse method (in page, currently)

    /**
     * 返回线的位置（垂直线返回 x，水平线返回 y）。
     *
     * 对斜线调用会抛出 UnsupportedOperationException。
     */
    public float getPosition() {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        return this.vertical() ? this.getLeft() : this.getTop();
    }

    /**
     * 将线沿垂直或水平方向平移到新的位置（只对非斜线有效）。
     */
    public void setPosition(float v) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        if (this.vertical()) {
            this.setLeft(v);
            this.setRight(v);
        } else {
            this.setTop(v);
            this.setBottom(v);
        }
    }

    /**
     * 返回线段在其方向上的起点（垂直线返回 top，水平线返回 left）。
     */
    public float getStart() {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        return this.vertical() ? this.getTop() : this.getLeft();
    }

    /**
     * 设置线段在其方向上的起点（只对非斜线有效）。
     */
    public void setStart(float v) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        if (this.vertical()) {
            this.setTop(v);
        } else {
            this.setLeft(v);
        }
    }

    /**
     * 返回线段在其方向上的终点（垂直线返回 bottom，水平线返回 right）。
     */
    public float getEnd() {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        return this.vertical() ? this.getBottom() : this.getRight();
    }

    /**
     * 设置线段在其方向上的终点（只对非斜线有效）。
     */
    public void setEnd(float v) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        if (this.vertical()) {
            this.setBottom(v);
        } else {
            this.setRight(v);
        }
    }

    /**
     * 内部用：同时设置 start 和 end（仅对非斜线）。
     */
    private void setStartEnd(float start, float end) {
        if (this.oblique()) {
            throw new UnsupportedOperationException();
        }
        if (this.vertical()) {
            this.setTop(start);
            this.setBottom(end);
        } else {
            this.setLeft(start);
            this.setRight(end);
        }
    }

    // ----- utility predicates -----

    /**
     * 判断两条线是否正交（即一条垂直且另一条水平）。
     */
    public boolean perpendicularTo(Ruling other) {
        return this.vertical() == other.horizontal();
    }

    /**
     * 判断给定点是否在该线的包围盒范围内（对直线段的端点-包围盒检查）。
     */
    public boolean colinear(Point2D point) {
        return point.getX() >= this.x1
                && point.getX() <= this.x2
                && point.getY() >= this.y1
                && point.getY() <= this.y2;
    }

    /**
     * 判断两条线是否“近似相交”，会考虑不同情形使用不同的膨胀策略：
     * - 若已严格相交（intersectsLine）直接返回 true
     * - 若两线正交，则把当前线膨胀 PERPENDICULAR_PIXEL_EXPAND_AMOUNT 后判断相交
     * - 否则（共线或平行）使用较小的膨胀量并同时膨胀两条线后判断相交
     *
     * 该策略用于容忍 PDF 中的小量舍入/扫描误差。
     */
    public boolean nearlyIntersects(Ruling another) {
        return this.nearlyIntersects(another, COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT);
    }

    /**
     * nearlyIntersects 的可配置版本，允许指定共线/平行情形下的膨胀量。
     */
    public boolean nearlyIntersects(Ruling another, int colinearOrParallelExpandAmount) {
        if (this.intersectsLine(another)) {
            return true;
        }

        boolean rv = false;
        if (this.perpendicularTo(another)) {
            rv = this.expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT).intersectsLine(another);
        } else {
            rv = this.expand(colinearOrParallelExpandAmount)
                    .intersectsLine(another.expand(colinearOrParallelExpandAmount));
        }

        return rv;
    }

    /**
     * 返回线段的欧氏长度。
     */
    public double length() {
        return Math.sqrt(Math.pow(this.x1 - this.x2, 2) + Math.pow(this.y1 - this.y2, 2));
    }

    /**
     * 将本线剪裁到给定矩形内，返回剪裁后的 Ruling（若裁剪改变了线段则返回新 Ruling）。
     *
     * 使用 Cohen–Sutherland 裁剪算法实现。
     */
    public Ruling intersect(Rectangle2D clip) {
        Line2D.Float clipee = (Line2D.Float) this.clone();
        boolean clipped = new CohenSutherlandClipping(clip).clip(clipee);
        if (clipped) {
            return new Ruling(clipee.getP1(), clipee.getP2());
        } else {
            return this;
        }
    }

    /**
     * 沿线方向将起点向后、终点向前扩展 amount（单位：同坐标单位）。
     *
     * 返回扩展后的新 Ruling（不修改原对象）。
     */
    public Ruling expand(float amount) {
        Ruling r = (Ruling) this.clone();
        r.setStart(this.getStart() - amount);
        r.setEnd(this.getEnd() + amount);
        return r;
    }

    /**
     * 计算两条正交线段的交点（在使用 PERPENDICULAR_PIXEL_EXPAND_AMOUNT 膨胀后）。
     *
     * 仅对一条水平、一条垂直的线有意义；否则抛出 IllegalArgumentException 或返回 null（若不相交）。
     */
    public Point2D intersectionPoint(Ruling other) {
        Ruling this_l = this.expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT);
        Ruling other_l = other.expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT);
        Ruling horizontal, vertical;

        if (!this_l.intersectsLine(other_l)) {
            return null;
        }

        if (this_l.horizontal() && other_l.vertical()) {
            horizontal = this_l;
            vertical = other_l;
        } else if (this_l.vertical() && other_l.horizontal()) {
            vertical = this_l;
            horizontal = other_l;
        } else {
            throw new IllegalArgumentException("lines must be orthogonal, vertical and horizontal");
        }
        return new Point2D.Float(vertical.getLeft(), horizontal.getTop());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;

        if (!(other instanceof Ruling))
            return false;

        Ruling o = (Ruling) other;
        return this.getP1().equals(o.getP1()) && this.getP2().equals(o.getP2());
    }

    // --- Convenience accessors mirroring Rectangle style (top/left/bottom/right)
    // ---
    public float getTop() {
        return this.y1;
    }

    public void setTop(float v) {
        setLine(this.getLeft(), v, this.getRight(), this.getBottom());
    }

    public float getLeft() {
        return this.x1;
    }

    public void setLeft(float v) {
        setLine(v, this.getTop(), this.getRight(), this.getBottom());
    }

    public float getBottom() {
        return this.y2;
    }

    public void setBottom(float v) {
        setLine(this.getLeft(), this.getTop(), this.getRight(), v);
    }

    public float getRight() {
        return this.x2;
    }

    public void setRight(float v) {
        setLine(this.getLeft(), this.getTop(), v, this.getBottom());
    }

    public float getWidth() {
        return this.getRight() - this.getLeft();
    }

    public float getHeight() {
        return this.getBottom() - this.getTop();
    }

    /**
     * 以度为单位返回向量与 x 轴的夹角（范围 0-360）。
     */
    public double getAngle() {
        double angle = Math.toDegrees(Math.atan2(this.getP2().getY() - this.getP1().getY(),
                this.getP2().getX() - this.getP1().getX()));

        if (angle < 0) {
            angle += 360;
        }
        return angle;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Formatter formatter = new Formatter(sb);
        String rv = formatter.format(Locale.US, "%s[x1=%f y1=%f x2=%f y2=%f]", this.getClass().toString(), this.x1,
                this.y1, this.x2, this.y2).toString();
        formatter.close();
        return rv;
    }

    /**
     * 将给定 rulings 列表裁剪到指定区域内，返回裁剪后的列表（如果一条线与区域相交则使用 intersect）。
     */
    public static List<Ruling> cropRulingsToArea(List<Ruling> rulings, Rectangle2D area) {
        ArrayList<Ruling> rv = new ArrayList<>();
        for (Ruling r : rulings) {
            if (r.intersects(area)) {
                rv.add(r.intersect(area));
            }
        }
        return rv;
    }

    /**
     * 使用扫描线算法查找所有水平线与垂直线的交点（复杂度接近 O(n log n)）。
     *
     * 返回一个按坐标排序的 Map：交点 -> {horizontalRulingExpanded, verticalRulingExpanded}
     * 扩展版本在插入时对水平线进行小量膨胀以容忍接近交点的情况。
     */
    public static Map<Point2D, Ruling[]> findIntersections(List<Ruling> horizontals, List<Ruling> verticals) {

        class SortObject {
            protected SOType type;
            protected float position;
            protected Ruling ruling;

            public SortObject(SOType type, float position, Ruling ruling) {
                this.type = type;
                this.position = position;
                this.ruling = ruling;
            }
        }

        List<SortObject> sos = new ArrayList<>();

        // 活动水平线的有序集合（按 top 排序），用于在扫描垂直线时快速检查交点
        TreeMap<Ruling, Boolean> tree = new TreeMap<>(new Comparator<Ruling>() {
            @Override
            public int compare(Ruling o1, Ruling o2) {
                return java.lang.Double.compare(o1.getTop(), o2.getTop());
            }
        });

        // 结果按 Y、X 排序的映射
        TreeMap<Point2D, Ruling[]> rv = new TreeMap<>(new Comparator<Point2D>() {
            @Override
            public int compare(Point2D o1, Point2D o2) {
                if (o1.getY() > o2.getY())
                    return 1;
                if (o1.getY() < o2.getY())
                    return -1;
                if (o1.getX() > o2.getX())
                    return 1;
                if (o1.getX() < o2.getX())
                    return -1;
                return 0;
            }
        });

        // 为每条水平线加入两个事件：左端进入 (HLEFT)，右端离开 (HRIGHT)
        for (Ruling h : horizontals) {
            sos.add(new SortObject(SOType.HLEFT, h.getLeft() - PERPENDICULAR_PIXEL_EXPAND_AMOUNT, h));
            sos.add(new SortObject(SOType.HRIGHT, h.getRight() + PERPENDICULAR_PIXEL_EXPAND_AMOUNT, h));
        }

        // 为每条垂直线加入一个事件（VERTICAL）
        for (Ruling v : verticals) {
            sos.add(new SortObject(SOType.VERTICAL, v.getLeft(), v));
        }

        // 根据 position 排序事件，position 相同时通过类型规则决定顺序（避免边界模糊）
        Collections.sort(sos, new Comparator<SortObject>() {
            @Override
            public int compare(SortObject a, SortObject b) {
                int rv;
                if (Utils.feq(a.position, b.position)) {
                    if (a.type == SOType.VERTICAL && b.type == SOType.HLEFT) {
                        rv = 1;
                    } else if (a.type == SOType.VERTICAL && b.type == SOType.HRIGHT) {
                        rv = -1;
                    } else if (a.type == SOType.HLEFT && b.type == SOType.VERTICAL) {
                        rv = -1;
                    } else if (a.type == SOType.HRIGHT && b.type == SOType.VERTICAL) {
                        rv = 1;
                    } else {
                        rv = java.lang.Double.compare(a.position, b.position);
                    }
                } else {
                    return java.lang.Double.compare(a.position, b.position);
                }
                return rv;
            }
        });

        // 扫描事件，维护活动的水平线集合；当遇到垂直线事件时与所有活动水平线计算交点
        for (SortObject so : sos) {
            switch (so.type) {
                case VERTICAL:
                    for (Map.Entry<Ruling, Boolean> h : tree.entrySet()) {
                        Point2D i = h.getKey().intersectionPoint(so.ruling);
                        if (i == null) {
                            continue;
                        }
                        rv.put(i,
                                new Ruling[] { h.getKey().expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT),
                                        so.ruling.expand(PERPENDICULAR_PIXEL_EXPAND_AMOUNT) });
                    }
                    break;
                case HRIGHT:
                    tree.remove(so.ruling);
                    break;
                case HLEFT:
                    tree.put(so.ruling, true);
                    break;
            }
        }

        return rv;

    }

    /**
     * 按方向合并（collapse）一组同向线段的便捷方法，使用默认的共线/平行膨胀量。
     */
    public static List<Ruling> collapseOrientedRulings(List<Ruling> lines) {
        return collapseOrientedRulings(lines, COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT);
    }

    /**
     * 将一组同向（均为水平或均为垂直）线段按位置排序并合并那些位置接近且在长度上“近似相交”的线段。
     *
     * 合并策略：
     * - 先按照 position, start 排序
     * - 依次检查当前线与结果列表的最后一条线：若位置相同且 nearlyIntersects 则合并为一个更长或更短（根据方向）线段
     * - 跳过零长度的线
     *
     * 该方法用于清理在 PDF 中检测到的断裂或重复线段。
     */
    public static List<Ruling> collapseOrientedRulings(List<Ruling> lines, int expandAmount) {
        ArrayList<Ruling> rv = new ArrayList<>();
        Collections.sort(lines, new Comparator<Ruling>() {
            @Override
            public int compare(Ruling a, Ruling b) {
                final float diff = a.getPosition() - b.getPosition();
                return java.lang.Float.compare(diff == 0 ? a.getStart() - b.getStart() : diff, 0f);
            }
        });

        for (Ruling next_line : lines) {
            Ruling last = rv.isEmpty() ? null : rv.get(rv.size() - 1);
            // if current line colinear with next, and are "close enough": expand current
            // line
            if (last != null && Utils.feq(next_line.getPosition(), last.getPosition())
                    && last.nearlyIntersects(next_line, expandAmount)) {
                final float lastStart = last.getStart();
                final float lastEnd = last.getEnd();

                final boolean lastFlipped = lastStart > lastEnd;
                final boolean nextFlipped = next_line.getStart() > next_line.getEnd();

                boolean differentDirections = nextFlipped != lastFlipped;
                float nextS = differentDirections ? next_line.getEnd() : next_line.getStart();
                float nextE = differentDirections ? next_line.getStart() : next_line.getEnd();

                final float newStart = lastFlipped ? Math.max(nextS, lastStart) : Math.min(nextS, lastStart);
                final float newEnd = lastFlipped ? Math.min(nextE, lastEnd) : Math.max(nextE, lastEnd);
                last.setStartEnd(newStart, newEnd);
                assert !last.oblique();
            } else if (next_line.length() == 0) {
                continue;
            } else {
                rv.add(next_line);
            }
        }
        return rv;
    }
}