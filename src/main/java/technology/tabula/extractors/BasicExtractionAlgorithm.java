package technology.tabula.extractors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Arrays;

import technology.tabula.Line;
import technology.tabula.Page;
import technology.tabula.Rectangle;
import technology.tabula.Ruling;
import technology.tabula.Table;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;

/**
 * 基于流（stream）的一种简单表格提取算法实现。
 *
 * <p>
 * 该算法并不依赖于页面上的直线（rulings）来构建表格网格，而是：
 * - 将页面文本合并为一系列 TextChunk（词/片段）
 * - 将 TextChunk 分组为行（Line）
 * - 基于第一行或提供的垂直线位置推断列边界（column positions）
 * - 把每个 chunk 按行列放入 Table 中
 * </p>
 *
 * 用途：在没有明显绘制表格边线的页面上，基于文本对齐推断表格结构（stream extraction）。
 */
public class BasicExtractionAlgorithm implements ExtractionAlgorithm {

    /**
     * 可选的垂直 rulings（当调用者已知列位置时可注入），
     * 如果提供则用这些 verticalRulings 来确定列边界，否则自动推断。
     */
    private List<Ruling> verticalRulings = null;

    public BasicExtractionAlgorithm() {
    }

    /**
     * 构造器：直接注入 vertical rulings。
     *
     * @param verticalRulings 垂直线列表，用以指定列位置
     */
    public BasicExtractionAlgorithm(List<Ruling> verticalRulings) {
        this.verticalRulings = verticalRulings;
    }

    /**
     * 使用一组垂直位置（x 坐标）来构建对应的垂直 Ruling 列表并执行提取。
     *
     * @param page                    页面对象
     * @param verticalRulingPositions 垂直线 x 坐标列表
     * @return 识别到的表格列表（通常只有一个 Table）
     */
    public List<Table> extract(Page page, List<Float> verticalRulingPositions) {
        List<Ruling> verticalRulings = new ArrayList<>(verticalRulingPositions.size());
        for (Float p : verticalRulingPositions) {
            verticalRulings.add(new Ruling(page.getTop(), p, 0.0f, (float) page.getHeight()));
        }
        this.verticalRulings = verticalRulings;
        return this.extract(page);
    }

    /**
     * 主提取方法：
     * 1. 从页面获得 TextElement 列表并合并为 TextChunk（词）
     * 2. 将 TextChunk 按行分组
     * 3. 使用提供的 verticalRulings 或自动推断列位置
     * 4. 构建 Table，并把每个文本块添加到相应的单元格（row, col）
     *
     * @param page 页面对象
     * @return 提取出的 Table 列表（stream 算法通常产出单个 Table）
     */
    @Override
    public List<Table> extract(Page page) {
        List<TextElement> textElements = page.getText();
        if (textElements.size() == 0) {
            // 页面无文本，返回空表格占位
            return Arrays.asList(new Table[] { Table.empty() });
        }

        // 合并字符为单词/片段。若指定了 verticalRulings 则在合并时考虑这些竖线以防止跨列合并
        List<TextChunk> textChunks = this.verticalRulings == null ? TextElement.mergeWords(page.getText())
                : TextElement.mergeWords(page.getText(), this.verticalRulings);
        // 按视觉行进行分组
        List<Line> lines = TextChunk.groupByLines(textChunks);
        List<Float> columns = null;
        if (this.verticalRulings != null) {
            // 若外部提供了竖线，则使用这些线的 x 坐标作为列边界
            Collections.sort(this.verticalRulings, new Comparator<Ruling>() {
                @Override
                public int compare(Ruling arg0, Ruling arg1) {
                    return Double.compare(arg0.getLeft(), arg1.getLeft());
                }
            });
            columns = new ArrayList<>(this.verticalRulings.size());
            for (Ruling vr : this.verticalRulings) {
                columns.add(vr.getLeft());
            }
        } else {
            // 否则根据文本排布自动推断列边界
            columns = columnPositions(lines);
        }

        // 创建 Table（覆盖整个页面区域）并记录页码
        Table table = new Table(this);
        table.setRect(page.getLeft(), page.getTop(), page.getWidth(), page.getHeight());
        table.setPageNumber(page.getPageNumber());

        // 遍历每一行，把行内的文本片段按列插入表格
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            List<TextChunk> elements = line.getTextElements();

            Collections.sort(elements, new Comparator<TextChunk>() {
                @Override
                public int compare(TextChunk o1, TextChunk o2) {
                    return Float.compare(o1.getLeft(), o2.getLeft());
                }
            });

            for (TextChunk tc : elements) {
                // 跳过仅为空白字符的 chunk
                if (tc.isSameChar(Line.WHITE_SPACE_CHARS)) {
                    continue;
                }

                int j = 0;
                boolean found = false;
                // 根据 chunk 的 left 坐标查找第一个大于等于该 left 的列边界
                for (; j < columns.size(); j++) {
                    if (tc.getLeft() <= columns.get(j)) {
                        found = true;
                        break;
                    }
                }
                // 将文本块添加到 (row=i, col=j)；若未找到则放在最后一列
                table.add(tc, i, found ? j : columns.size());
            }
        }

        return Arrays.asList(new Table[] { table });
    }

    @Override
    public String toString() {
        return "stream";
    }

    /**
     * 基于第一行的文本片段推断列位置（x 轴的边界）：
     *
     * 算法：
     * - 以第一行的非空文本块为初始“列区域”
     * - 对后续行，找出与现有列区域水平重叠的文本块并合并到对应区域
     * - 对没有重叠的文本块创建新的列区域
     * - 最后返回每个列区域的右边界（作为列边界位置）
     *
     * @param lines 已按 top 排序的行列表（每行包含若干 TextChunk）
     * @return 列边界的 x 坐标列表（升序）
     */
    public static List<java.lang.Float> columnPositions(List<Line> lines) {
        List<Rectangle> regions = new ArrayList<>();
        // 使用首行的非空 chunk 建立初始区域
        for (TextChunk tc : lines.get(0).getTextElements()) {
            if (tc.isSameChar(Line.WHITE_SPACE_CHARS)) {
                continue;
            }
            Rectangle r = new Rectangle();
            r.setRect(tc);
            regions.add(r);
        }

        // 遍历剩余每一行，尝试将该行的 chunk 合并到现有区域（水平重叠即视为同一列）
        for (Line l : lines.subList(1, lines.size())) {
            List<TextChunk> lineTextElements = new ArrayList<>();
            for (TextChunk tc : l.getTextElements()) {
                if (!tc.isSameChar(Line.WHITE_SPACE_CHARS)) {
                    lineTextElements.add(tc);
                }
            }

            for (Rectangle cr : regions) {

                List<TextChunk> overlaps = new ArrayList<>();
                for (TextChunk te : lineTextElements) {
                    if (cr.horizontallyOverlaps(te)) {
                        overlaps.add(te);
                    }
                }

                for (TextChunk te : overlaps) {
                    cr.merge(te);
                }

                lineTextElements.removeAll(overlaps);
            }

            // 剩余未匹配的文本块建立新的区域（新的列）
            for (TextChunk te : lineTextElements) {
                Rectangle r = new Rectangle();
                r.setRect(te);
                regions.add(r);
            }
        }

        // 返回每个区域的右侧边界作为列边界，并按 x 升序排序
        List<java.lang.Float> rv = new ArrayList<>();
        for (Rectangle r : regions) {
            rv.add(r.getRight());
        }

        Collections.sort(rv);
        return rv;
    }
}