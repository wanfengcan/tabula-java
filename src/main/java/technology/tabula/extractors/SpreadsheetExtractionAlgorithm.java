package technology.tabula.extractors;

import technology.tabula.*;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * 基于检测到的直线（rulings）从页面中提取“栅格/电子表格式”表格的算法实现（lattice extraction）。
 *
 * 主要流程：
 * - 将 rulings 分为水平与垂直两组并折叠（collapse）接近/重叠的线段
 * - 计算水平与垂直线的交点，基于交点寻找单元格（Cell）
 * - 把相邻单元格合并成更大的“表格区域”（Rectangle），并为每个区域填充单元格文本与对应的 rulings
 *
 * 本类同时包含几个静态工具方法，用于在点集合/单元格集合之间推断表格区域。
 */
public class SpreadsheetExtractionAlgorithm implements ExtractionAlgorithm {

    // 用于判定 isTabular 的启发式阈值
    private static final float MAGIC_HEURISTIC_NUMBER = 0.65f;

    /**
     * 按 Y 优先的点比较器（用于对交点按行再列排序）。
     * 先比较 Y（经过四舍五入），Y 相等时比较 X。
     */
    private static final Comparator<Point2D> Y_FIRST_POINT_COMPARATOR = (point1, point2) -> {
        int compareY = compareRounded(point1.getY(), point2.getY());
        if (compareY == 0) {
            return compareRounded(point1.getX(), point2.getX());
        }
        return compareY;
    };

    /**
     * 按 X 优先的点比较器（用于在合并顶点时按列优先排序）。
     * 先比较 X（经过四舍五入），X 相等时比较 Y。
     */
    private static final Comparator<Point2D> X_FIRST_POINT_COMPARATOR = (point1, point2) -> {
        int compareX = compareRounded(point1.getX(), point2.getX());
        if (compareX == 0) {
            return compareRounded(point1.getY(), point2.getY());
        }
        return compareX;
    };

    /**
     * 对两个 double 值先按小数点后 2 位四舍五入再比较（用于稳定的点位置比较）。
     */
    private static int compareRounded(double d1, double d2) {
        float d1Rounded = Utils.round(d1, 2);
        float d2Rounded = Utils.round(d2, 2);

        return Float.compare(d1Rounded, d2Rounded);
    }

    @Override
    public List<Table> extract(Page page) {
        // 默认从 page.getRulings() 中提取表格
        return extract(page, page.getRulings());
    }

    /**
     * 使用给定的 rulings（通常来自 Page）在页面上提取表格。
     *
     * 主要步骤：
     * 1. 将 rulings 划分为水平和垂直集合，并对同向线段进行折叠（collapse）
     * 2. 基于交点构造单元格（Cell）
     * 3. 将相邻的单元格合并为表格区域（spreadsheetAreas）
     * 4. 为每个区域收集包含的单元格、以及该区域内的水平/垂直 rulings，构造 TableWithRulingLines
     *
     * @param page    页面对象（用于获取文本和页码）
     * @param rulings 页面上检测到的线列表
     * @return 在页面上识别出的表格列表（按视觉顺序排序）
     */
    public List<Table> extract(Page page, List<Ruling> rulings) {
        // split rulings into horizontal and vertical
        List<Ruling> horizontalR = new ArrayList<>();
        List<Ruling> verticalR = new ArrayList<>();

        for (Ruling r : rulings) {
            if (r.horizontal()) {
                horizontalR.add(r);
            } else if (r.vertical()) {
                verticalR.add(r);
            }
        }
        // 合并近似共线或重叠的水平/垂直线以得到“干净”的线集合
        horizontalR = Ruling.collapseOrientedRulings(horizontalR);
        verticalR = Ruling.collapseOrientedRulings(verticalR);

        // 基于交点寻找单元格
        List<Cell> cells = findCells(horizontalR, verticalR);
        // 再把这些单元格组合成更高层次的表格区域
        List<Rectangle> spreadsheetAreas = findSpreadsheetsFromCells(cells);

        List<Table> spreadsheets = new ArrayList<>();
        for (Rectangle area : spreadsheetAreas) {

            List<Cell> overlappingCells = new ArrayList<>();
            // 收集与当前表格区域相交的单元格，并为其产生文本（merge words）
            for (Cell c : cells) {
                if (c.intersects(area)) {
                    // 使用 page.getText(c) 获取单元格内的 TextElement 列表，再合并成 word-chunks
                    c.setTextElements(TextElement.mergeWords(page.getText(c)));
                    overlappingCells.add(c);
                }
            }

            // 收集穿过该区域的 rulings（用于构造 TableWithRulingLines）
            List<Ruling> horizontalOverlappingRulings = new ArrayList<>();
            for (Ruling hr : horizontalR) {
                if (area.intersectsLine(hr)) {
                    horizontalOverlappingRulings.add(hr);
                }
            }
            List<Ruling> verticalOverlappingRulings = new ArrayList<>();
            for (Ruling vr : verticalR) {
                if (area.intersectsLine(vr)) {
                    verticalOverlappingRulings.add(vr);
                }
            }

            TableWithRulingLines t = new TableWithRulingLines(area, overlappingCells, horizontalOverlappingRulings,
                    verticalOverlappingRulings, this, page.getPageNumber());
            spreadsheets.add(t);
        }
        // 按照 Rectangle.ILL_DEFINED_ORDER 对表格做稳定排序，便于输出与测试
        Utils.sort(spreadsheets, Rectangle.ILL_DEFINED_ORDER);
        return spreadsheets;
    }

    /**
     * 判断页面是否“更像是”基于 rulings 的表格（lattice），用于在不同提取算法间做选择。
     *
     * 策略：
     * - 先裁剪到包含所有文本的最小区域
     * - 同时用 lattice（本算法）和 basic（无线）两种算法提取表格
     * - 比较两种算法得到的行/列数的比率，若接近且超出启发式阈值则认为是 tabular
     */
    public boolean isTabular(Page page) {

        // if there's no text at all on the page, it's not a table
        // (we won't be able to do anything with it though)
        if (page.getText().isEmpty()) {
            return false;
        }

        // get minimal region of page that contains every character (in effect,
        // removes white "margins")
        Page minimalRegion = page.getArea(Utils.bounds(page.getText()));

        List<? extends Table> tables = new SpreadsheetExtractionAlgorithm().extract(minimalRegion);
        if (tables.isEmpty()) {
            return false;
        }
        Table table = tables.get(0);
        int rowsDefinedByLines = table.getRowCount();
        int colsDefinedByLines = table.getColCount();

        tables = new BasicExtractionAlgorithm().extract(minimalRegion);
        if (tables.isEmpty()) {
            return false;
        }
        table = tables.get(0);
        int rowsDefinedWithoutLines = table.getRowCount();
        int colsDefinedWithoutLines = table.getColCount();

        float ratio = (((float) colsDefinedByLines / colsDefinedWithoutLines) +
                ((float) rowsDefinedByLines / rowsDefinedWithoutLines)) / 2.0f;

        return ratio > MAGIC_HEURISTIC_NUMBER && ratio < (1 / MAGIC_HEURISTIC_NUMBER);
    }

    /**
     * 基于水平与垂直线的交点计算单元格（Cell）。
     *
     * 算法概要：
     * - 计算所有水平线与垂直线的交点（Ruling.findIntersections）
     * - 遍历每个交点作为潜在左上角，查找向下的交点列表 xPoints 和向右的交点列表 yPoints
     * - 对每对 (xPoint, yPoint) 检查是否存在对应的右下角 btmRight，且四个角对应的横/纵边相匹配
     *
     * @param horizontalRulingLines 水平 rulings 列表
     * @param verticalRulingLines   垂直 rulings 列表
     * @return 识别出的单元格列表
     */
    public static List<Cell> findCells(List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        List<Cell> cellsFound = new ArrayList<>();
        Map<Point2D, Ruling[]> intersectionPoints = Ruling.findIntersections(horizontalRulingLines,
                verticalRulingLines);
        List<Point2D> intersectionPointsList = new ArrayList<>(intersectionPoints.keySet());
        intersectionPointsList.sort(Y_FIRST_POINT_COMPARATOR);

        for (int i = 0; i < intersectionPointsList.size(); i++) {
            Point2D topLeft = intersectionPointsList.get(i);
            Ruling[] hv = intersectionPoints.get(topLeft);

            List<Point2D> xPoints = new ArrayList<>();
            List<Point2D> yPoints = new ArrayList<>();

            // 从 topLeft 开始向下/向右收集位于同一列/同一行的交点
            for (Point2D p : intersectionPointsList.subList(i, intersectionPointsList.size())) {
                if (p.getX() == topLeft.getX() && p.getY() > topLeft.getY()) {
                    xPoints.add(p);
                }
                if (p.getY() == topLeft.getY() && p.getX() > topLeft.getX()) {
                    yPoints.add(p);
                }
            }
            outer: for (Point2D xPoint : xPoints) {

                // is there a vertical edge b/w topLeft and xPoint?
                if (!hv[1].equals(intersectionPoints.get(xPoint)[1])) {
                    continue;
                }
                for (Point2D yPoint : yPoints) {
                    // is there an horizontal edge b/w topLeft and yPoint ?
                    if (!hv[0].equals(intersectionPoints.get(yPoint)[0])) {
                        continue;
                    }
                    Point2D btmRight = new Point2D.Float((float) yPoint.getX(), (float) xPoint.getY());
                    // 检查右下角是否存在交点，且对应的水平/垂直边匹配
                    if (intersectionPoints.containsKey(btmRight)
                            && intersectionPoints.get(btmRight)[0].equals(intersectionPoints.get(xPoint)[0])
                            && intersectionPoints.get(btmRight)[1].equals(intersectionPoints.get(yPoint)[1])) {
                        cellsFound.add(new Cell(topLeft, btmRight));
                        break outer;
                    }
                }
            }
        }

        // TODO create cells for vertical ruling lines with aligned endpoints at the
        // top/bottom of a grid
        // that aren't connected with an horizontal ruler?
        // see:
        // https://github.com/jazzido/tabula-extractor/issues/78#issuecomment-41481207

        return cellsFound;
    }

    /**
     * 从识别出的单元格集合中找出表格区域（可能包含多个表格）。
     *
     * 策略：
     * - 把所有单元格的顶点进行“对消”：出现两次的顶点表示共享顶点，会被移除，剩余顶点构造边集合
     * - 根据 X/Y 排序配对水平/垂直边，构造边映射 edgesH / edgesV
     * - 从边映射中提取封闭多边形（每个多边形表示一个表格边界）
     * - 为每个多边形计算最小的 grid-aligned 矩形（axis-aligned bounding box）作为表格区域
     *
     * @param cells 单元格集合
     * @return 推断出的表格区域列表
     */
    public static List<Rectangle> findSpreadsheetsFromCells(List<? extends Rectangle> cells) {
        // via:
        // http://stackoverflow.com/questions/13746284/merging-multiple-adjacent-rectangles-into-one-polygon
        List<Rectangle> rectangles = new ArrayList<>();
        Set<Point2D> pointSet = new HashSet<>();
        Map<Point2D, Point2D> edgesH = new HashMap<>();
        Map<Point2D, Point2D> edgesV = new HashMap<>();
        int i = 0;

        // deduplicate cells (set) 保证后续处理的确定性
        cells = new ArrayList<>(new HashSet<>(cells));

        Utils.sort(cells, Rectangle.ILL_DEFINED_ORDER);

        // 对每个 cell 的四个顶点执行“对消”：重复出现的顶点视为内部/共享顶点并移除
        for (Rectangle cell : cells) {
            for (Point2D pt : cell.getPoints()) {
                if (pointSet.contains(pt)) { // shared vertex, remove it
                    pointSet.remove(pt);
                } else {
                    pointSet.add(pt);
                }
            }
        }

        // X first sort
        List<Point2D> pointsSortX = new ArrayList<>(pointSet);
        pointsSortX.sort(X_FIRST_POINT_COMPARATOR);
        // Y first sort
        List<Point2D> pointsSortY = new ArrayList<>(pointSet);
        pointsSortY.sort(Y_FIRST_POINT_COMPARATOR);

        // 根据 Y 分组，配对水平边（相邻两点构成一条水平边）
        while (i < pointSet.size()) {
            float currY = (float) pointsSortY.get(i).getY();
            while (i < pointSet.size() && Utils.feq(pointsSortY.get(i).getY(), currY)) {
                edgesH.put(pointsSortY.get(i), pointsSortY.get(i + 1));
                edgesH.put(pointsSortY.get(i + 1), pointsSortY.get(i));
                i += 2;
            }
        }

        i = 0;
        // 根据 X 分组，配对垂直边（相邻两点构成一条垂直边）
        while (i < pointSet.size()) {
            float currX = (float) pointsSortX.get(i).getX();
            while (i < pointSet.size() && Utils.feq(pointsSortX.get(i).getX(), currX)) {
                edgesV.put(pointsSortX.get(i), pointsSortX.get(i + 1));
                edgesV.put(pointsSortX.get(i + 1), pointsSortX.get(i));
                i += 2;
            }
        }

        // Get all the polygons: 从 edgesH/edgesV 中反复提取封闭环（多边形）
        List<List<PolygonVertex>> polygons = new ArrayList<>();
        Point2D nextVertex;
        while (!edgesH.isEmpty()) {
            ArrayList<PolygonVertex> polygon = new ArrayList<>();
            Point2D first = edgesH.keySet().iterator().next();
            polygon.add(new PolygonVertex(first, Direction.HORIZONTAL));
            edgesH.remove(first);

            while (true) {
                PolygonVertex curr = polygon.get(polygon.size() - 1);
                PolygonVertex lastAddedVertex;
                if (curr.direction == Direction.HORIZONTAL) {
                    nextVertex = edgesV.get(curr.point);
                    edgesV.remove(curr.point);
                    lastAddedVertex = new PolygonVertex(nextVertex, Direction.VERTICAL);
                } else {
                    nextVertex = edgesH.get(curr.point);
                    edgesH.remove(curr.point);
                    lastAddedVertex = new PolygonVertex(nextVertex, Direction.HORIZONTAL);
                }
                polygon.add(lastAddedVertex);

                if (lastAddedVertex.equals(polygon.get(0))) {
                    // closed polygon
                    polygon.remove(polygon.size() - 1);
                    break;
                }
            }

            for (PolygonVertex vertex : polygon) {
                edgesH.remove(vertex.point);
                edgesV.remove(vertex.point);
            }
            polygons.add(polygon);
        }

        // calculate grid-aligned minimum area rectangles for each found polygon
        for (List<PolygonVertex> poly : polygons) {
            float top = java.lang.Float.MAX_VALUE;
            float left = java.lang.Float.MAX_VALUE;
            float bottom = java.lang.Float.MIN_VALUE;
            float right = java.lang.Float.MIN_VALUE;
            for (PolygonVertex pt : poly) {
                top = (float) Math.min(top, pt.point.getY());
                left = (float) Math.min(left, pt.point.getX());
                bottom = (float) Math.max(bottom, pt.point.getY());
                right = (float) Math.max(right, pt.point.getX());
            }
            rectangles.add(new Rectangle(top, left, right - left, bottom - top));
        }

        return rectangles;
    }

    @Override
    public String toString() {
        return "lattice";
    }

    /**
     * 辅助枚举：表示边在多边形中的方向（水平或垂直）。
     */
    private enum Direction {
        HORIZONTAL,
        VERTICAL
    }

    /**
     * 用于表示多边形顶点及其沿边方向的简单结构体。
     * 在合并单元格顶点、构造多边形时使用。
     */
    static class PolygonVertex {
        Point2D point;
        Direction direction;

        public PolygonVertex(Point2D point, Direction direction) {
            this.direction = direction;
            this.point = point;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof PolygonVertex))
                return false;
            return this.point.equals(((PolygonVertex) other).point);
        }

        @Override
        public int hashCode() {
            return this.point.hashCode();
        }

        @Override
        public String toString() {
            return String.format("%s[point=%s,direction=%s]", this.getClass().getName(), this.point.toString(),
                    this.direction.toString());
        }
    }
}