package technology.tabula;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("serial")
/**
 * 矩形文本容器。
 *
 * <p>
 * 泛型 T 必须同时是 Rectangle 与 HasText 的实现（例如 TextChunk/TextElement）。
 * 本类用于表示页面上某个矩形区域内的文本集合（按视觉顺序保存在 textElements 中），
 * 并提供合并（merge）等便捷操作以构建表格单元格的内容容器。
 * </p>
 *
 * @param <T> 存放的文本元素类型，必须继承 Rectangle 并实现 HasText
 */
public class RectangularTextContainer<T extends Rectangle & HasText> extends Rectangle implements HasText {
	/**
	 * 保存属于该矩形区域的文本元素（按顺序）。
	 */
	protected List<T> textElements = new ArrayList<>();

	/**
	 * 受保护构造函数，使用区域的 top/left/width/height 初始化基类 Rectangle。
	 *
	 * @param top    区域顶部 Y 坐标
	 * @param left   区域左侧 X 坐标
	 * @param width  区域宽度
	 * @param height 区域高度
	 */
	protected RectangularTextContainer(float top, float left, float width, float height) {
		super(top, left, width, height);
	}

	/**
	 * 将另一个 RectangularTextContainer 的文本元素合并到当前容器中，并合并几何边界。
	 *
	 * 合并策略：
	 * - 根据 compareTo 的顺序决定新元素追加到末尾还是插入到开头，保持视觉顺序一致性。
	 * - 使用父类的 merge 方法扩展当前矩形以包含 other 的区域。
	 *
	 * @param other 要合并的容器
	 * @return 当前对象以便链式调用
	 */
	public RectangularTextContainer<T> merge(RectangularTextContainer<T> other) {
		if (compareTo(other) < 0) {
			this.getTextElements().addAll(other.getTextElements());
		} else {
			this.getTextElements().addAll(0, other.getTextElements());
		}
		super.merge(other);
		return this;
	}

	/**
	 * 返回包含的文本元素列表（可修改以添加/重排元素）。
	 *
	 * @return 文本元素的列表
	 */
	public List<T> getTextElements() {
		return textElements;
	}

	/**
	 * 替换当前的文本元素列表。
	 *
	 * @param textElements 新的文本元素列表
	 */
	public void setTextElements(List<T> textElements) {
		this.textElements = textElements;
	}

	/**
	 * 返回该容器的文本表示（合并的字符串）。
	 *
	 * <p>
	 * 默认实现未定义 —— 子类应覆盖以提供基于 textElements 的文本拼接逻辑。
	 * 调用此基类方法会抛出 UnsupportedOperationException。
	 * </p>
	 *
	 * @throws UnsupportedOperationException 未由基类实现
	 */
	@Override
	public String getText() {
		throw new UnsupportedOperationException();
	}

	/**
	 * 返回该容器的文本表示，是否使用换行符由实现决定。
	 *
	 * <p>
	 * 基类未实现，子类应覆盖。
	 * </p>
	 *
	 * @param useLineReturns 是否保留行分隔
	 * @throws UnsupportedOperationException 未由基类实现
	 */
	@Override
	public String getText(boolean useLineReturns) {
		throw new UnsupportedOperationException();
	}

	/**
	 * 返回带有几何信息和文本内容（如果可用）的调试字符串。
	 *
	 * @return 描述性字符串
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		String s = super.toString();
		sb.append(s.substring(0, s.length() - 1));
		sb.append(String.format(",text=%s]", this.getText() == null ? "null" : "\"" + this.getText() + "\""));
		return sb.toString();
	}

}