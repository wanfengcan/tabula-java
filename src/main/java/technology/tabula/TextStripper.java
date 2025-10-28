package technology.tabula;

import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 PDF 页面中提取文本元素（TextElement）的工具类，基于 PDFBox 的 PDFTextStripper。
 *
 * <p>
 * TextStripper 将 PDFTextStripper 的字符级回调转换为 TextElement 实例，并把它们收集到列表中。
 * 同时维护一个基于 RectangleSpatialIndex 的空间索引以便后续按区域查询文本。
 * 还包含对字体高度的计算和对异常/不可打印字符的过滤逻辑，以及对过高空白字符的过滤策略。
 * </p>
 */
public class TextStripper extends PDFTextStripper {
  // 非断行空格字符，用于替换为普通空格
  private static final String NBSP = "\u00A0";
  // 用于丢弃过高空白字符的高度阈值倍数 (avgHeight * AVG_HEIGHT_MULT_THRESHOLD)
  private static final float AVG_HEIGHT_MULT_THRESHOLD = 6.0f;
  // 允许的最大空白字符字号（超过则丢弃）
  private static final float MAX_BLANK_FONT_SIZE = 40.0f;
  // 允许的最小空白字符字号（小于则丢弃）
  private static final float MIN_BLANK_FONT_SIZE = 2.0f;

  // 原始 PDDocument 引用（用于在构造时指定要处理的页面）
  private final PDDocument document;
  // 收集到的 TextElement 列表（以原始扫描顺序保存）
  private final ArrayList<TextElement> textElements;
  // 空间索引，便于基于矩形区域查找文本元素
  private final RectangleSpatialIndex<TextElement> spatialIndex;

  // 页面上观测到的最小字符宽度/高度（用于启发式判断）
  private float minCharWidth = Float.MAX_VALUE;
  private float minCharHeight = Float.MAX_VALUE;

  // 用于计算字符高度的平均值（用于判断异常空白）
  private float totalHeight = 0.0f;
  private int countHeight = 0;

  /**
   * 构造函数：初始化 TextStripper，用于提取指定页码的文本元素。
   *
   * @param document   要处理的 PDF 文档
   * @param pageNumber 要提取的页码（1-based，传入 PDFTextStripper 的 start/end）
   * @throws IOException 由 PDFTextStripper 抛出
   */
  public TextStripper(PDDocument document, int pageNumber) throws IOException {
    super();
    this.document = document;
    this.setStartPage(pageNumber);
    this.setEndPage(pageNumber);
    this.textElements = new ArrayList<>();
    this.spatialIndex = new RectangleSpatialIndex<>();
  }

  /**
   * 触发对指定页的解析并填充内部的 textElements 与 spatialIndex。
   *
   * @throws IOException 若读取 PDF 失败
   */
  public void process() throws IOException {
    this.getText(this.document);
  }

  /**
   * PDFTextStripper 回调：对每个字符（或 PDFBox 分割的字符单元）构建 TextElement 并收集。
   *
   * 处理要点：
   * - 过滤不可打印字符
   * - 将非断行空格替换为普通空格
   * - 使用 TextPosition 提供的坐标/宽高/字体信息构建 TextElement
   * - 更新最小字符宽/高统计
   * - 基于平均字符高度与字体大小对异常空白字符进行过滤（避免被当作行/单元格）
   * - 把通过过滤的 TextElement 加入 spatialIndex 与 textElements 列表
   *
   * @param string        本次回调的字符串（可能为多字符）
   * @param textPositions 对应的 TextPosition 列表（字符级）
   * @throws IOException 继承签名
   */
  @Override
  protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
    for (TextPosition textPosition : textPositions) {
      if (textPosition == null) {
        continue;
      }

      String c = textPosition.getUnicode();

      // 如果字符不可打印则跳过
      if (!isPrintable(c)) {
        continue;
      }

      Float h = textPosition.getHeightDir();

      if (c.equals(NBSP)) { // 将不换行空格替换为普通空格，便于后续合并
        c = " ";
      }

      float wos = textPosition.getWidthOfSpace();

      // 构造 TextElement（注意 Y 的调整与四舍五入保持一致性）
      TextElement te = new TextElement(Utils.round(textPosition.getYDirAdj() - h, 2),
          Utils.round(textPosition.getXDirAdj(), 2), Utils.round(textPosition.getWidthDirAdj(), 2),
          Utils.round(textPosition.getHeightDir(), 2), textPosition.getFont(), textPosition.getFontSizeInPt(), c,
          // workaround a possible bug in PDFBox:
          // https://issues.apache.org/jira/browse/PDFBOX-1755
          wos, textPosition.getDir());

      // 更新最小字符宽/高统计
      this.minCharWidth = (float) Math.min(this.minCharWidth, te.getWidth());
      this.minCharHeight = (float) Math.min(this.minCharHeight, te.getHeight());

      // 更新高度平均值统计，用于检测异常高的空白
      countHeight++;
      totalHeight += te.getHeight();
      float avgHeight = totalHeight / countHeight;

      // 对空白字符进行额外的过滤判断，避免非常高或非常大的空白影响行高估算
      if ((te.getText() == null || te.getText().trim().equals(""))) {
        // 若该空白高度远大于平均高度（乘以阈值），则丢弃
        if (avgHeight > 0
            && te.getHeight() >= (avgHeight * AVG_HEIGHT_MULT_THRESHOLD)) {
          continue;
        }

        // 若字体大小异常（太大或太小），也丢弃
        if (textPosition.getFontSizeInPt() > MAX_BLANK_FONT_SIZE
            || textPosition.getFontSizeInPt() < MIN_BLANK_FONT_SIZE) {
          continue;
        }
      }

      // 通过过滤后把元素加入空间索引与列表
      this.spatialIndex.add(te);
      this.textElements.add(te);
    }
  }

  /**
   * 计算字体高度的辅助方法（覆盖 PDFTextStripper 的实现以处理某些字体数据异常）。
   *
   * 说明：
   * - 使用字形边界框或字体描述符（capHeight/ascent/descent）来估算 glyph height
   * - 对 PDType3Font 与其它字体做不同变换
   *
   * @param font PDFBox 的 PDFont
   * @return 估算的字体高度（文本空间单位）
   * @throws IOException 若读取字体元数据失败
   */
  @Override
  protected float computeFontHeight(PDFont font) throws IOException {
    BoundingBox bbox = font.getBoundingBox();
    if (bbox.getLowerLeftY() < Short.MIN_VALUE) {
      // 处理某些异常字体的 lowerLeftY 表示方式（PDFBOX-2158 / PDFBOX-3130）
      bbox.setLowerLeftY(-(bbox.getLowerLeftY() + 65536));
    }
    // 使用 bbox 的一半作为 glyph height（保留原实现的经验值）
    float glyphHeight = bbox.getHeight() / 2;

    // 若 fontDescriptor 提供 capHeight 或 ascent/descent，则使用更可靠的值
    PDFontDescriptor fontDescriptor = font.getFontDescriptor();
    if (fontDescriptor != null) {
      float capHeight = fontDescriptor.getCapHeight();
      if (Float.compare(capHeight, 0) != 0 &&
          (capHeight < glyphHeight || Float.compare(glyphHeight, 0) == 0)) {
        glyphHeight = capHeight;
      }
      // 有时 capHeight 也异常，尝试使用 ascent/descent 的一半作为备选
      float ascent = fontDescriptor.getAscent();
      float descent = fontDescriptor.getDescent();
      if (ascent > 0 && descent < 0 &&
          ((ascent - descent) / 2 < glyphHeight || Float.compare(glyphHeight, 0) == 0)) {
        glyphHeight = (ascent - descent) / 2;
      }
    }

    // 把 glyph space 的高度变换到文本空间
    float height;
    if (font instanceof PDType3Font) {
      height = font.getFontMatrix().transformPoint(0, glyphHeight).y;
    } else {
      height = glyphHeight / 1000;
    }

    return height;
  }

  /**
   * 判断字符串是否为“可打印”字符（非控制字符、属于有效 Unicode 区块且不是 SPECIALS）。
   *
   * @param s 待检测字符串（可能为多字符）
   * @return 若包含至少一个可打印字符则返回 true
   */
  private boolean isPrintable(String s) {
    Character c;
    Character.UnicodeBlock block;
    boolean printable = false;
    for (int i = 0; i < s.length(); i++) {
      c = s.charAt(i);
      block = Character.UnicodeBlock.of(c);
      printable |= !Character.isISOControl(c) && block != null && block != Character.UnicodeBlock.SPECIALS;
    }
    return printable;
  }

  /**
   * 返回解析得到的 TextElement 列表（按页面扫描顺序）。
   *
   * @return TextElement 列表
   */
  public List<TextElement> getTextElements() {
    return this.textElements;
  }

  /**
   * 返回构建的空间索引，便于按矩形区域快速查询文本元素。
   *
   * @return RectangleSpatialIndex 实例
   */
  public RectangleSpatialIndex<TextElement> getSpatialIndex() {
    return spatialIndex;
  }

  /**
   * 返回页面上观测到的最小字符宽度（用于启发式判断）。
   */
  public float getMinCharWidth() {
    return minCharWidth;
  }

  /**
   * 返回页面上观测到的最小字符高度（用于启发式判断）。
   */
  public float getMinCharHeight() {
    return minCharHeight;
  }
}