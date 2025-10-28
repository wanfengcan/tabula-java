package technology.tabula;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.awt.geom.PathIterator.*;

/**
 * 从 PDFPage 的内容流中提取“对象”——主要是检测并收集页面上的直线 (rulings)。
 *
 * <p>
 * 继承自 PDFGraphicsStreamEngine，用于接收 PDF 绘制操作并把 Path 转换为 Ruling 列表。
 * 该类负责：
 * - 计算页面坐标变换（pageTransform），将 PDF 坐标系变为常用的页面坐标（原点在左上）
 * - 在绘图/描边/填充路径时过滤并解析只包含线段的 Path
 * - 将线段裁剪到当前裁剪路径并以 Ruling 对象保存（排除过短的线）
 * </p>
 */
class ObjectExtractorStreamEngine extends PDFGraphicsStreamEngine {

    /**
     * 收集到的 rulings（页面上的线段），由调用者读取。
     */
    protected List<Ruling> rulings;

    /**
     * 将 PDF 坐标系变换到页面坐标（翻转 y 轴、考虑裁剪框和旋转）。
     */
    private AffineTransform pageTransform;

    /**
     * 是否启用 rulings 提取（可以禁用以跳过路径处理）。
     */
    private boolean extractRulingLines = true;

    /**
     * 用于日志记录。
     */
    private Logger logger;

    /**
     * 临时保存 clip 时的 winding rule（直到 endPath 被调用）。
     */
    private int clipWindingRule = -1;

    /**
     * 当前正在构建的路径。PDFGraphicsStreamEngine 会以 moveTo/lineTo/curveTo 填充它。
     */
    private GeneralPath currentPath = new GeneralPath();

    /**
     * 判定为有效 ruling 的最小长度（短于该长度的线被忽略）。
     */
    private static final float RULING_MINIMUM_LENGTH = 0.01f;

    /**
     * 构造函数，初始化引擎并计算用于坐标转换的 AffineTransform。
     *
     * @param page 要处理的 PDPage
     */
    protected ObjectExtractorStreamEngine(PDPage page) {
        super(page);
        logger = LoggerFactory.getLogger(ObjectExtractorStreamEngine.class);
        rulings = new ArrayList<>();

        // 初始化 pageTransform：考虑页面 crop box 和旋转，然后翻转 y 轴以获得“自上而下”的坐标系
        pageTransform = new AffineTransform();
        PDRectangle pageCropBox = getPage().getCropBox();
        int rotationAngleInDegrees = getPage().getRotation();

        // 如果页面旋转了 90 或 270 度，需要先做旋转变换（围绕原点）
        if (Math.abs(rotationAngleInDegrees) == 90 || Math.abs(rotationAngleInDegrees) == 270) {
            double rotationAngleInRadians = rotationAngleInDegrees * (Math.PI / 180.0);
            pageTransform = AffineTransform.getRotateInstance(rotationAngleInRadians, 0, 0);
        } else {
            // 对于非旋转的页面，先把坐标系的原点移动到页面顶部
            double deltaX = 0;
            double deltaY = pageCropBox.getHeight();
            pageTransform.concatenate(AffineTransform.getTranslateInstance(deltaX, deltaY));
        }

        // 翻转 y 轴（PDF 的坐标原点在左下，翻转后原点在左上）
        pageTransform.concatenate(AffineTransform.getScaleInstance(1, -1));
        pageTransform.translate(-pageCropBox.getLowerLeftX(), -pageCropBox.getLowerLeftY());
    }

    // --------------------------------------------------------------------------------
    // PDFGraphicsStreamEngine 回调实现：这些方法收集构造路径的数据到 currentPath
    // --------------------------------------------------------------------------------

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        // 将矩形的四条边加入 currentPath
        currentPath.moveTo((float) p0.getX(), (float) p0.getY());
        currentPath.lineTo((float) p1.getX(), (float) p1.getY());
        currentPath.lineTo((float) p2.getX(), (float) p2.getY());
        currentPath.lineTo((float) p3.getX(), (float) p3.getY());
        currentPath.closePath();
    }

    @Override
    public void clip(int windingRule) {
        // 保存 clipping winding rule，直到 endPath 时应用到图形状态
        clipWindingRule = windingRule;
    }

    @Override
    public void closePath() {
        // 关闭当前子路径
        currentPath.closePath();
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        // 将贝塞尔曲线片段加入路径（后续会被 filterPathBySegmentType 过滤掉非线段路径）
        currentPath.curveTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public void drawImage(PDImage arg0) {
        // 图像不参与 rulings 提取，留空实现
    }

    @Override
    public void endPath() {
        // 在路径结束时如果有 pending 的 clipping，应用到图形状态
        if (clipWindingRule != -1) {
            currentPath.setWindingRule(clipWindingRule);
            getGraphicsState().intersectClippingPath(currentPath);
            clipWindingRule = -1;
        }
        // 重置路径以便下一次开始新路径
        currentPath.reset();
    }

    @Override
    public void fillAndStrokePath(int arg0) {
        // 填充并描边与填充路径的处理一致：交由 strokeOrFillPath 处理
        strokeOrFillPath(true);
    }

    @Override
    public void fillPath(int arg0) {
        // 填充路径也按同样方式处理（我们只关心线段）
        strokeOrFillPath(true);
    }

    @Override
    public Point2D getCurrentPoint() {
        return currentPath.getCurrentPoint();
    }

    @Override
    public void lineTo(float x, float y) {
        currentPath.lineTo(x, y);
    }

    @Override
    public void moveTo(float x, float y) {
        currentPath.moveTo(x, y);
    }

    @Override
    public void shadingFill(COSName arg0) {
        // 着色填充不影响 rulings 提取
    }

    // --------------------------------------------------------------------------------
    // 路径解析核心：将 currentPath 中的线段提取为 Ruling，并考虑裁剪与最小长度过滤
    // --------------------------------------------------------------------------------

    @Override
    public void strokePath() {
        // 描边路径的处理与填充一致
        strokeOrFillPath(false);
    }

    /**
     * 解析并处理 currentPath 中的路径片段。
     *
     * 步骤概要：
     * - 若未启用提取则直接丢弃路径
     * - 仅接受由 moveTo/lineTo/close 组成的路径（filterPathBySegmentType）
     * - 使用 PathIterator（带 pageTransform）遍历分段，构造直线段并裁剪到当前 clipping，符合长度阈值的加入 rulings
     *
     * @param isFill 表示这是 fill 操作还是 stroke，当前逻辑对两者一致
     */
    private void strokeOrFillPath(boolean isFill) {
        if (!extractRulingLines) {
            currentPath.reset();
            return;
        }

        // 只接受由线段构成的路径，否则放弃（例如贝塞尔曲线）
        boolean didNotPassedTheFilter = filterPathBySegmentType();
        if (didNotPassedTheFilter)
            return;

        // TODO: how to implement color filter?

        // 使用 pageTransform 将 PathIterator 中的坐标变换到页面坐标系
        PathIterator pathIterator = currentPath.getPathIterator(getPageTransform());

        float[] coordinates = new float[6];
        int currentSegment;

        // 起始点（moveTo 指定的开头）
        Point2D.Float startPoint = getStartPoint(pathIterator);
        Point2D.Float last_move = startPoint;
        Point2D.Float endPoint = null;
        Line2D.Float line;
        PointComparator pointComparator = new PointComparator();

        // 遍历路径分段，处理线段 (SEG_LINETO)、子路径起点 (SEG_MOVETO) 和闭合 (SEG_CLOSE)
        while (!pathIterator.isDone()) {
            pathIterator.next();
            // pathIterator.currentSegment 可能在迭代结束时抛出 IndexOutOfBounds — 捕获并跳过
            try {
                currentSegment = pathIterator.currentSegment(coordinates);
            } catch (IndexOutOfBoundsException ex) {
                continue;
            }
            switch (currentSegment) {
                case SEG_LINETO:
                    // 解析一条直线段，从 startPoint 到 endPoint
                    endPoint = new Point2D.Float(coordinates[0], coordinates[1]);
                    if (startPoint == null || endPoint == null) {
                        break;
                    }
                    line = getLineBetween(startPoint, endPoint, pointComparator);
                    verifyLineIntersectsClipping(line);
                    break;
                case SEG_MOVETO:
                    // 新子路径的起点
                    last_move = new Point2D.Float(coordinates[0], coordinates[1]);
                    endPoint = last_move;
                    break;
                case SEG_CLOSE:
                    // close 意味着从当前点回到最近的 moveto
                    if (startPoint == null || endPoint == null) {
                        break;
                    }
                    line = getLineBetween(endPoint, last_move, pointComparator);
                    verifyLineIntersectsClipping(line);
                    break;
            }
            startPoint = endPoint;
        }
        // 处理完路径后重置
        currentPath.reset();
    }

    /**
     * 快速检查 currentPath 是否由允许的段类型组成（只接受 moveTo/lineTo/close）。
     *
     * 如果遇到其他类型（如 curve），则会清空 currentPath 并返回 true 表示“不通过过滤”。
     *
     * @return 如果未通过过滤返回 true；通过则返回 false
     */
    private boolean filterPathBySegmentType() {
        PathIterator pathIterator = currentPath.getPathIterator(pageTransform);
        float[] coordinates = new float[6];
        int currentSegmentType = pathIterator.currentSegment(coordinates);

        // 路径必须以 SEG_MOVETO 开始，否则舍弃
        if (currentSegmentType != SEG_MOVETO) {
            currentPath.reset();
            return true;
        }
        pathIterator.next();
        while (!pathIterator.isDone()) {
            currentSegmentType = pathIterator.currentSegment(coordinates);
            // 只允许线段/关闭/再次移动（move）指令
            if (currentSegmentType != SEG_LINETO && currentSegmentType != SEG_CLOSE
                    && currentSegmentType != SEG_MOVETO) {
                currentPath.reset();
                return true;
            }
            pathIterator.next();
        }
        return false;
    }

    /**
     * 从 PathIterator 的当前状态读取第一个（moveTo）点，并对坐标做四舍五入处理以减小浮点噪声。
     *
     * @param pathIterator 已经定位到序列开头的 PathIterator（尚未 next）
     * @return 经过舍入的起始点
     */
    private Point2D.Float getStartPoint(PathIterator pathIterator) {
        float[] startPointCoordinates = new float[6];
        pathIterator.currentSegment(startPointCoordinates);
        float x = Utils.round(startPointCoordinates[0], 2);
        float y = Utils.round(startPointCoordinates[1], 2);
        return new Point2D.Float(x, y);
    }

    /**
     * 根据点的比较器返回按一致顺序构造的 Line2D.Float（确保 p1 <= p2 的顺序性，方便后续比较/集合操作）。
     *
     * @param pointA          点 A
     * @param pointB          点 B
     * @param pointComparator 用于按 y 再 x 排序的比较器
     * @return 按顺序构造的直线段
     */
    private Line2D.Float getLineBetween(Point2D.Float pointA, Point2D.Float pointB, PointComparator pointComparator) {
        if (pointComparator.compare(pointA, pointB) == -1) {
            return new Line2D.Float(pointA, pointB);
        }
        return new Line2D.Float(pointB, pointA);
    }

    /**
     * 将一条线与当前的裁剪路径做交集判断并存入 rulings（先裁剪线段到 clipping bounds，再检查最小长度）。
     *
     * @param line 要验证并可能加入 rulings 的线段（未变换）
     */
    private void verifyLineIntersectsClipping(Line2D.Float line) {
        Rectangle2D currentClippingPath = currentClippingPath();
        if (line.intersects(currentClippingPath)) {
            // 先构造 Ruling，再裁剪到裁剪区域（intersect 会返回裁剪后的 Ruling）
            Ruling ruling = new Ruling(line.getP1(), line.getP2()).intersect(currentClippingPath);
            // 丢弃过短的线段
            if (ruling.length() > RULING_MINIMUM_LENGTH) {
                rulings.add(ruling);
            }
        }
    }

    // --------------------------------------------------------------------------------
    // 辅助公开方法：提供 pageTransform 与 当前裁剪区域信息
    // --------------------------------------------------------------------------------

    /**
     * 返回用于将 PathIterator 坐标变换为页面坐标的 AffineTransform。
     */
    public AffineTransform getPageTransform() {
        return pageTransform;
    }

    /**
     * 返回当前图形状态下的裁剪路径边界（已经应用了 pageTransform）。
     *
     * @return 当前裁剪区域的 bounds（Rectangle2D）
     */
    public Rectangle2D currentClippingPath() {
        Shape currentClippingPath = getGraphicsState().getCurrentClippingPath();
        Shape transformedClippingPath = getPageTransform().createTransformedShape(currentClippingPath);
        return transformedClippingPath.getBounds2D();
    }

    // 比较器：按 y 再按 x 排序（用于确保线段端点顺序一致）
    // TODO: repeated in SpreadsheetExtractionAlgorithm.
    class PointComparator implements Comparator<Point2D> {
        @Override
        public int compare(Point2D p1, Point2D p2) {
            float p1X = Utils.round(p1.getX(), 2);
            float p1Y = Utils.round(p1.getY(), 2);
            float p2X = Utils.round(p2.getX(), 2);
            float p2Y = Utils.round(p2.getY(), 2);

            if (p1Y > p2Y)
                return 1;
            if (p1Y < p2Y)
                return -1;
            if (p1X > p2X)
                return 1;
            if (p1X < p2X)
                return -1;
            return 0;
        }
    }

}