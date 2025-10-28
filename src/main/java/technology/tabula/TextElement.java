package technology.tabula;

import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.font.PDFont;

@SuppressWarnings("serial")
/**
 * 表示页面上的一个文本片段（通常是一个字符或一个小的文本单元）。
 *
 * TextElement 继承自 Rectangle，包含文本内容、字体信息、字号、
 * 空格宽度估计以及方向信息。该类用于从 PDF 中抽取原始文本元素，
 * 并供后续的合并（mergeWords）和布局分析使用。
 */
public class TextElement extends Rectangle implements HasText {

    private final String text;
    private final PDFont font;
    private float fontSize;
    private float widthOfSpace, dir;
    private static final float AVERAGE_CHAR_TOLERANCE = 0.3f;

    /**
     * 使用默认方向（0f）构造 TextElement。
     *
     * @param y            文本顶部 Y 坐标
     * @param x            文本左侧 X 坐标
     * @param width        文本框宽度
     * @param height       文本框高度
     * @param font         PDFont 对象
     * @param fontSize     字号
     * @param c            文本内容（通常为单字符或短串）
     * @param widthOfSpace 估计的空格宽度（用于词边界判断）
     */
    public TextElement(float y, float x, float width, float height,
            PDFont font, float fontSize, String c, float widthOfSpace) {
        this(y, x, width, height, font, fontSize, c, widthOfSpace, 0f);
    }

    /**
     * 构造 TextElement 并设置方向信息。
     *
     * @param y            文本顶部 Y 坐标
     * @param x            文本左侧 X 坐标
     * @param width        文本框宽度
     * @param height       文本框高度
     * @param font         PDFont 对象
     * @param fontSize     字号
     * @param c            文本内容
     * @param widthOfSpace 估计的空格宽度
     * @param dir          文本方向（例如水平或旋转角度的指示）
     */
    public TextElement(float y, float x, float width, float height,
            PDFont font, float fontSize, String c, float widthOfSpace, float dir) {
        super();
        this.setRect(x, y, width, height);
        this.text = c;
        this.widthOfSpace = widthOfSpace;
        this.fontSize = fontSize;
        this.font = font;
        this.dir = dir;
    }

    /**
     * 返回文本内容（不包含换行处理）。
     *
     * @return 文本字符串
     */
    @Override
    public String getText() {
        return text;
    }

    /**
     * 返回文本内容，根据参数决定是否保留换行符。
     * 当前实现忽略参数，直接返回原始文本。
     *
     * @param useLineReturns 是否保留换行（未使用）
     * @return 文本字符串
     */
    @Override
    public String getText(boolean useLineReturns) {
        return text;
    }

    /**
     * 返回方向值（可能表示文字方向或旋转信息）。
     *
     * @return 方向值
     */
    public float getDirection() {
        return dir;
    }

    /**
     * 返回用于估算单词间距的空格宽度。
     *
     * @return 空格宽度估计
     */
    public float getWidthOfSpace() {
        return widthOfSpace;
    }

    /**
     * 返回使用的 PDF 字体对象。
     *
     * @return PDFont 实例
     */
    public PDFont getFont() {
        return font;
    }

    /**
     * 返回字体大小。
     *
     * @return 字号
     */
    public float getFontSize() {
        return fontSize;
    }

    /**
     * 返回带有文本信息的字符串表示（用于调试）。
     *
     * @return 描述性的字符串
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String s = super.toString();
        sb.append(s.substring(0, s.length() - 1));
        sb.append(String.format(",text=\"%s\"]", this.getText()));
        return sb.toString();
    }

    /**
     * 计算哈希码，包含父类哈希和本类字段。
     *
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + java.lang.Float.floatToIntBits(dir);
        result = prime * result + ((font == null) ? 0 : font.hashCode());
        result = prime * result + java.lang.Float.floatToIntBits(fontSize);
        result = prime * result + ((text == null) ? 0 : text.hashCode());
        result = prime * result + java.lang.Float.floatToIntBits(widthOfSpace);
        return result;
    }

    /**
     * 比较两个 TextElement 是否相等（包含位置、尺寸、字体、字号、方向、文本等）。
     *
     * @param obj 目标对象
     * @return 相等则返回 true
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        TextElement other = (TextElement) obj;
        if (java.lang.Float.floatToIntBits(dir) != java.lang.Float
                .floatToIntBits(other.dir))
            return false;
        if (font == null) {
            if (other.font != null)
                return false;
        } else if (!font.equals(other.font))
            return false;
        if (java.lang.Float.floatToIntBits(fontSize) != java.lang.Float
                .floatToIntBits(other.fontSize))
            return false;
        if (text == null) {
            if (other.text != null)
                return false;
        } else if (!text.equals(other.text))
            return false;
        return java.lang.Float.floatToIntBits(widthOfSpace) == java.lang.Float
                .floatToIntBits(other.widthOfSpace);
    }

    /**
     * 将一组 TextElement 合并为 TextChunk（文本块） 列表（不考虑竖向 rulings）。
     * 该方法是 mergeWords(textElements, new ArrayList<Ruling>()) 的便捷重载。
     *
     * @param textElements 原始文本元素列表（通常按页面扫描顺序）
     * @return 合并得到的 TextChunk 列表
     */
    public static List<TextChunk> mergeWords(List<TextElement> textElements) {
        return mergeWords(textElements, new ArrayList<Ruling>());
    }

    /**
     * 根据启发式规则把一系列 TextElement 合并成单词/片段（TextChunk）。
     *
     * 主要逻辑：
     * - 使用字符宽度、空格宽度估计、字体/字号变化和垂直 rulings 信息判断词边界
     * - 将相邻字符按行组合成 chunk，必要时插入表示空格的 TextElement
     * - 最后按方向性对 chunk 进行分组（LTR/RTL）
     *
     * 注意：该方法会对传入列表做一次复制（避免修改原列表的副作用），并保留算法中大量 PDFBox 移植的启发式逻辑。
     *
     * @param textElements    原始 TextElement 列表（可以为空）
     * @param verticalRulings 垂直方向的 rulings，用于阻断跨列合并
     * @return 合并后的 TextChunk 列表（每个 chunk 代表一个连续的单词或文本片段）
     */
    public static List<TextChunk> mergeWords(List<TextElement> textElements, List<Ruling> verticalRulings) {
        List<TextChunk> textChunks = new ArrayList<>();
        if (textElements.isEmpty()) {
            return textChunks;
        }

        // it's a problem that this `remove` is side-effecty
        // other things depend on `textElements` and it can sometimes lead to the first
        // textElement in textElement
        // not appearing in the final output because it's been removed here.
        // https://github.com/tabulapdf/tabula-java/issues/78
        List<TextElement> copyOfTextElements = new ArrayList<>(textElements);
        textChunks.add(new TextChunk(copyOfTextElements.remove(0)));
        TextChunk firstTC = textChunks.get(0);

        float previousAveCharWidth = (float) firstTC.getWidth();
        float endOfLastTextX = firstTC.getRight();
        float maxYForLine = firstTC.getBottom();
        float maxHeightForLine = (float) firstTC.getHeight();
        float minYTopForLine = firstTC.getTop();
        float lastWordSpacing = -1;
        float wordSpacing, deltaSpace, averageCharWidth, deltaCharWidth;
        float expectedStartOfNextWordX, dist;
        TextElement sp, prevChar;
        TextChunk currentChunk;
        boolean sameLine, acrossVerticalRuling;

        for (TextElement chr : copyOfTextElements) {
            currentChunk = textChunks.get(textChunks.size() - 1);
            prevChar = currentChunk.textElements.get(currentChunk.textElements.size() - 1);

            // if same char AND overlapped, skip
            if ((chr.getText().equals(prevChar.getText())) && (prevChar.overlapRatio(chr) > 0.5)) {
                continue;
            }

            // if chr is a space that overlaps with prevChar, skip
            if (chr.getText().equals(" ") && Utils.feq(prevChar.getLeft(), chr.getLeft())
                    && Utils.feq(prevChar.getTop(), chr.getTop())) {
                continue;
            }

            // Resets the average character width when we see a change in font
            // or a change in the font size
            if ((chr.getFont() != prevChar.getFont()) || !Utils.feq(chr.getFontSize(), prevChar.getFontSize())) {
                previousAveCharWidth = -1;
            }

            // is there any vertical ruling that goes across chr and prevChar?
            acrossVerticalRuling = false;
            for (Ruling r : verticalRulings) {
                if ((verticallyOverlapsRuling(prevChar, r) && verticallyOverlapsRuling(chr, r)) &&
                        (prevChar.x < r.getPosition() && chr.x > r.getPosition())
                        || (prevChar.x > r.getPosition() && chr.x < r.getPosition())) {
                    acrossVerticalRuling = true;
                    break;
                }
            }

            // Estimate the expected width of the space based on the
            // space character with some margin.
            wordSpacing = chr.getWidthOfSpace();
            deltaSpace = 0;
            if (java.lang.Float.isNaN(wordSpacing) || wordSpacing == 0) {
                deltaSpace = java.lang.Float.MAX_VALUE;
            } else if (lastWordSpacing < 0) {
                deltaSpace = wordSpacing * 0.5f; // 0.5 == spacing tolerance
            } else {
                deltaSpace = ((wordSpacing + lastWordSpacing) / 2.0f) * 0.5f;
            }

            // Estimate the expected width of the space based on the
            // average character width with some margin. This calculation does not
            // make a true average (average of averages) but we found that it gave the
            // best results after numerous experiments. Based on experiments we also found
            // that
            // .3 worked well.
            if (previousAveCharWidth < 0) {
                averageCharWidth = (float) (chr.getWidth() / chr.getText().length());
            } else {
                averageCharWidth = (float) ((previousAveCharWidth + (chr.getWidth() / chr.getText().length())) / 2.0f);
            }
            deltaCharWidth = averageCharWidth * AVERAGE_CHAR_TOLERANCE;

            // Compares the values obtained by the average method and the wordSpacing method
            // and picks
            // the smaller number.
            expectedStartOfNextWordX = -java.lang.Float.MAX_VALUE;

            if (endOfLastTextX != -1) {
                expectedStartOfNextWordX = endOfLastTextX + Math.min(deltaCharWidth, deltaSpace);
            }

            // new line?
            sameLine = true;
            if (!Utils.overlap(chr.getBottom(), chr.height, maxYForLine, maxHeightForLine)) {
                endOfLastTextX = -1;
                expectedStartOfNextWordX = -java.lang.Float.MAX_VALUE;
                maxYForLine = -java.lang.Float.MAX_VALUE;
                maxHeightForLine = -1;
                minYTopForLine = java.lang.Float.MAX_VALUE;
                sameLine = false;
            }

            endOfLastTextX = chr.getRight();

            // should we add a space?
            if (!acrossVerticalRuling &&
                    sameLine &&
                    expectedStartOfNextWordX < chr.getLeft() &&
                    !prevChar.getText().endsWith(" ")) {

                sp = new TextElement(prevChar.getTop(),
                        prevChar.getLeft(),
                        expectedStartOfNextWordX - prevChar.getLeft(),
                        (float) prevChar.getHeight(),
                        prevChar.getFont(),
                        prevChar.getFontSize(),
                        " ",
                        prevChar.getWidthOfSpace());

                currentChunk.add(sp);
            } else {
                sp = null;
            }

            maxYForLine = Math.max(chr.getBottom(), maxYForLine);
            maxHeightForLine = (float) Math.max(maxHeightForLine, chr.getHeight());
            minYTopForLine = Math.min(minYTopForLine, chr.getTop());

            dist = chr.getLeft() - (sp != null ? sp.getRight() : prevChar.getRight());

            if (!acrossVerticalRuling &&
                    sameLine &&
                    (dist < 0 ? currentChunk.verticallyOverlaps(chr) : dist < wordSpacing)) {
                currentChunk.add(chr);
            } else { // create a new chunk
                textChunks.add(new TextChunk(chr));
            }

            lastWordSpacing = wordSpacing;
            previousAveCharWidth = (float) (sp != null ? (averageCharWidth + sp.getWidth()) / 2.0f : averageCharWidth);
        }

        List<TextChunk> textChunksSeparatedByDirectionality = new ArrayList<>();
        // count up characters by directionality
        for (TextChunk chunk : textChunks) {
            // choose the dominant direction
            boolean isLtrDominant = chunk.isLtrDominant() != -1; // treat neutral as LTR
            TextChunk dirChunk = chunk.groupByDirectionality(isLtrDominant);
            textChunksSeparatedByDirectionality.add(dirChunk);
        }

        return textChunksSeparatedByDirectionality;
    }

    /**
     * 判断一个 TextElement 在垂直方向上是否与一个 Ruling 重叠（用于检测跨列情况）。
     *
     * @param te 文本元素
     * @param r  Ruling（线）
     * @return 若存在垂直重叠则返回 true
     */
    private static boolean verticallyOverlapsRuling(TextElement te, Ruling r) {
        return Math.max(0, Math.min(te.getBottom(), r.getY2()) - Math.max(te.getTop(), r.getY1())) > 0;
    }

}