// ...existing code...
package technology.tabula;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("serial")
public class Rectangle extends Rectangle2D.Float {

	/**
	 * Ill-defined comparator, from when Rectangle was Comparable.
	 * 
	 * @see <a href="https://github.com/tabulapdf/tabula-java/issues/116">PR 116</a>
	 * @deprecated with no replacement
	 */
	@Deprecated
	public static final Comparator<Rectangle> ILL_DEFINED_ORDER = new Comparator<Rectangle>() {
		@Override
		public int compare(Rectangle o1, Rectangle o2) {
			if (o1.equals(o2))
				return 0;
			if (o1.verticalOverlap(o2) > VERTICAL_COMPARISON_THRESHOLD) {
				return o1.isLtrDominant() == -1 && o2.isLtrDominant() == -1
						? -java.lang.Double.compare(o1.getX(), o2.getX())
						: java.lang.Double.compare(o1.getX(), o2.getX());
			} else {
				return java.lang.Float.compare(o1.getBottom(), o2.getBottom());
			}
		}
	};

	protected static final float VERTICAL_COMPARISON_THRESHOLD = 0.4f;

	/**
	 * 创建一个空的 Rectangle（所有数值为默认值）。
	 */
	public Rectangle() {
		super();
	}

	/**
	 * 使用顶部、左侧、宽度和高度构造 Rectangle。
	 *
	 * @param top    矩形顶部 Y 坐标
	 * @param left   矩形左侧 X 坐标
	 * @param width  矩形宽度
	 * @param height 矩形高度
	 */
	public Rectangle(float top, float left, float width, float height) {
		super();
		this.setRect(left, top, width, height);
	}

	/**
	 * 比较当前矩形与另一个矩形的顺序（使用已弃用的非严格定义规则）。
	 *
	 * @param other 要比较的矩形
	 * @return 负数、零或正数，表示顺序关系
	 * @deprecated 语义不明确，仅用于兼容
	 */
	public int compareTo(Rectangle other) {
		return ILL_DEFINED_ORDER.compare(this, other);
	}

	/**
	 * 返回从左到右是否占主导的指示器（保留以供排序使用，当前总是 0）。
	 *
	 * @return -1 表示左到右，1 表示右到左，0 表示未指定或默认
	 */
	public int isLtrDominant() {
		return 0;
	}

	/**
	 * 计算并返回矩形的面积（宽 * 高）。
	 *
	 * @return 矩形面积
	 */
	public float getArea() {
		return this.width * this.height;
	}

	/**
	 * 计算当前矩形与另一个矩形在垂直方向上的重叠长度（像素/坐标单位）。
	 * 如果没有重叠则返回 0。
	 *
	 * @param other 另一个矩形
	 * @return 垂直重叠长度
	 */
	public float verticalOverlap(Rectangle other) {
		return Math.max(0, Math.min(this.getBottom(), other.getBottom()) - Math.max(this.getTop(), other.getTop()));
	}

	/**
	 * 判断当前矩形与另一个矩形是否在垂直方向上有重叠。
	 *
	 * @param other 另一个矩形
	 * @return 有重叠返回 true，否则返回 false
	 */
	public boolean verticallyOverlaps(Rectangle other) {
		return verticalOverlap(other) > 0;
	}

	/**
	 * 计算当前矩形与另一个矩形在水平方向上的重叠长度（像素/坐标单位）。
	 * 如果没有重叠则返回 0。
	 *
	 * @param other 另一个矩形
	 * @return 水平重叠长度
	 */
	public float horizontalOverlap(Rectangle other) {
		return Math.max(0, Math.min(this.getRight(), other.getRight()) - Math.max(this.getLeft(), other.getLeft()));
	}

	/**
	 * 判断当前矩形与另一个矩形是否在水平方向上有重叠。
	 *
	 * @param other 另一个矩形
	 * @return 有重叠返回 true，否则返回 false
	 */
	public boolean horizontallyOverlaps(Rectangle other) {
		return horizontalOverlap(other) > 0;
	}

	/**
	 * 计算当前矩形与另一个矩形在垂直方向上的重叠比例（相对于较小高度的比值）。
	 *
	 * 返回的值在 0 到 1 之间，表示两矩形在垂直方向上重叠的比例（按较小矩形高度归一化）。
	 *
	 * @param other 另一个矩形
	 * @return 垂直重叠比例（0-1）
	 */
	public float verticalOverlapRatio(Rectangle other) {
		float rv = 0, delta = Math.min(this.getBottom() - this.getTop(), other.getBottom() - other.getTop());

		if (other.getTop() <= this.getTop() && this.getTop() <= other.getBottom()
				&& other.getBottom() <= this.getBottom()) {
			rv = (other.getBottom() - this.getTop()) / delta;
		} else if (this.getTop() <= other.getTop() && other.getTop() <= this.getBottom()
				&& this.getBottom() <= other.getBottom()) {
			rv = (this.getBottom() - other.getTop()) / delta;
		} else if (this.getTop() <= other.getTop() && other.getTop() <= other.getBottom()
				&& other.getBottom() <= this.getBottom()) {
			rv = (other.getBottom() - other.getTop()) / delta;
		} else if (other.getTop() <= this.getTop() && this.getTop() <= this.getBottom()
				&& this.getBottom() <= other.getBottom()) {
			rv = (this.getBottom() - this.getTop()) / delta;
		}

		return rv;

	}

	/**
	 * 计算两个矩形的交并比（intersection over union, IoU）。
	 *
	 * @param other 另一个矩形
	 * @return 交并比（0-1），表示两个矩形的重叠程度
	 */
	public float overlapRatio(Rectangle other) {
		double intersectionWidth = Math.max(0,
				Math.min(this.getRight(), other.getRight()) - Math.max(this.getLeft(), other.getLeft()));
		double intersectionHeight = Math.max(0,
				Math.min(this.getBottom(), other.getBottom()) - Math.max(this.getTop(), other.getTop()));
		double intersectionArea = Math.max(0, intersectionWidth * intersectionHeight);
		double unionArea = this.getArea() + other.getArea() - intersectionArea;

		return (float) (intersectionArea / unionArea);
	}

	/**
	 * 将当前矩形扩展为包含另一个矩形的并集（修改当前对象并返回自身）。
	 *
	 * @param other 要合并的矩形
	 * @return 修改后的当前矩形（包含两者的最小边界）
	 */
	public Rectangle merge(Rectangle other) {
		this.setRect(this.createUnion(other));
		return this;
	}

	/**
	 * 返回矩形顶部坐标（最小 Y）。
	 *
	 * @return 顶部 Y 坐标
	 */
	public float getTop() {
		return (float) this.getMinY();
	}

	/**
	 * 设置矩形的顶部坐标，同时调整高度以保留底边位置不变。
	 *
	 * @param top 新的顶部 Y 坐标
	 */
	public void setTop(float top) {
		float deltaHeight = top - this.y;
		this.setRect(this.x, top, this.width, this.height - deltaHeight);
	}

	/**
	 * 返回矩形右侧坐标（最大 X）。
	 *
	 * @return 右侧 X 坐标
	 */
	public float getRight() {
		return (float) this.getMaxX();
	}

	/**
	 * 设置矩形的右侧坐标，同时调整宽度以保留左边位置不变。
	 *
	 * @param right 新的右侧 X 坐标
	 */
	public void setRight(float right) {
		this.setRect(this.x, this.y, right - this.x, this.height);
	}

	/**
	 * 返回矩形左侧坐标（最小 X）。
	 *
	 * @return 左侧 X 坐标
	 */
	public float getLeft() {
		return (float) this.getMinX();
	}

	/**
	 * 设置矩形的左侧坐标，同时调整宽度以保留右边位置不变。
	 *
	 * @param left 新的左侧 X 坐标
	 */
	public void setLeft(float left) {
		float deltaWidth = left - this.x;
		this.setRect(left, this.y, this.width - deltaWidth, this.height);
	}

	/**
	 * 返回矩形底部坐标（最大 Y）。
	 *
	 * @return 底部 Y 坐标
	 */
	public float getBottom() {
		return (float) this.getMaxY();
	}

	/**
	 * 设置矩形的底部坐标，同时调整高度以保留顶部位置不变。
	 *
	 * @param bottom 新的底部 Y 坐标
	 */
	public void setBottom(float bottom) {
		this.setRect(this.x, this.y, this.width, bottom - this.y);
	}

	/**
	 * 返回矩形的四个角点，按顺时针顺序：左上、右上、右下、左下。
	 *
	 * @return 角点数组
	 */
	public Point2D[] getPoints() {
		return new Point2D[] { new Point2D.Float(this.getLeft(), this.getTop()),
				new Point2D.Float(this.getRight(), this.getTop()), new Point2D.Float(this.getRight(), this.getBottom()),
				new Point2D.Float(this.getLeft(), this.getBottom()) };
	}

	/**
	 * 返回矩形的字符串表示，包含底部和右侧信息。
	 *
	 * @return 字符串表示
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		String s = super.toString();
		sb.append(s.substring(0, s.length() - 1));
		sb.append(String.format(Locale.US, ",bottom=%f,right=%f]", this.getBottom(), this.getRight()));
		return sb.toString();
	}

	/**
	 * 计算并返回一组矩形的最小外接矩形（bounding box）。
	 *
	 * @param rectangles 要包围的矩形列表
	 * @return 包含所有矩形的最小矩形（top, left, width, height）
	 * @throws IllegalArgumentException 当列表为空时可能产生不合理值（调用方需保证非空）
	 */
	public static Rectangle boundingBoxOf(List<? extends Rectangle> rectangles) {
		float minx = java.lang.Float.MAX_VALUE;
		float miny = java.lang.Float.MAX_VALUE;
		float maxx = java.lang.Float.MIN_VALUE;
		float maxy = java.lang.Float.MIN_VALUE;

		for (Rectangle r : rectangles) {
			minx = (float) Math.min(r.getMinX(), minx);
			miny = (float) Math.min(r.getMinY(), miny);
			maxx = (float) Math.max(r.getMaxX(), maxx);
			maxy = (float) Math.max(r.getMaxY(), maxy);
		}
		return new Rectangle(miny, minx, maxx - minx, maxy - miny);
	}

}