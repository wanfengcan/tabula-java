package technology.tabula;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import technology.tabula.extractors.ExtractionAlgorithm;

@SuppressWarnings("serial")
/**
 * 表示从页面中提取出来的表格（矩形网格，行列交叉的单元格容器）。
 *
 * Table 继承自 Rectangle，以便携带页面区域的几何信息（top/left/width/height）。
 * 该类负责：
 * - 记录表格的行列数以及所属页码
 * - 存储每个单元格的文本容器（RectangularTextContainer），用 CellPosition 索引
 * - 提供按行返回单元格矩阵的便捷方法
 *
 * extractionMethod 字段记录用来构造该 Table 的提取算法/方法名（便于调试/输出）。
 */
public class Table extends Rectangle {

	/**
	 * 返回一个空表格实例（用于占位或测试）。
	 */
	public static final Table empty() {
		return new Table("");
	}

	/**
	 * 私有构造，允许直接设置 extractionMethod 字符串。
	 */
	private Table(String extractionMethod) {
		this.extractionMethod = extractionMethod;
	}

	/**
	 * 使用具体的 ExtractionAlgorithm 构造 Table，记录提取方法名。
	 *
	 * @param extractionAlgorithm 用于提取该表格的算法（仅记录其 toString()）
	 */
	public Table(ExtractionAlgorithm extractionAlgorithm) {
		this(extractionAlgorithm.toString());
	}

	/**
	 * 标识该表格是由哪个提取方法得出的（只读）。
	 */
	private final String extractionMethod;

	/* 表格元数据 */
	private int rowCount = 0;
	private int colCount = 0;
	private int pageNumber = 0;

	/*
	 * 用于存储单元格内容的有序映射（Key: CellPosition, Value: RectangularTextContainer）
	 * 使用 TreeMap 保证按行列顺序可预测地遍历。对测试可见。
	 */
	final TreeMap<CellPosition, RectangularTextContainer> cells = new TreeMap<>();

	/* 访问器和元数据操作方法 */
	public int getRowCount() {
		return rowCount;
	}

	public int getColCount() {
		return colCount;
	}

	public int getPageNumber() {
		return pageNumber;
	}

	public void setPageNumber(int pageNumber) {
		this.pageNumber = pageNumber;
	}

	public String getExtractionMethod() {
		return extractionMethod;
	}

	/**
	 * 将给定文本容器添加到表格的指定单元格（row, col）。
	 *
	 * 行列计数会根据需要自动扩展；如果目标位置已有内容，则会把新内容与旧内容合并（chunk.merge(old)）。
	 * 并且会使缓存的行矩阵（memoizedRows）失效。
	 *
	 * @param chunk 要放入的单元格文本容器（RectangularTextContainer）
	 * @param row   目标行索引（从 0 开始）
	 * @param col   目标列索引（从 0 开始）
	 */
	public void add(RectangularTextContainer chunk, int row, int col) {
		this.merge(chunk);

		rowCount = Math.max(rowCount, row + 1);
		colCount = Math.max(colCount, col + 1);

		CellPosition cp = new CellPosition(row, col);

		RectangularTextContainer old = cells.get(cp);
		if (old != null)
			chunk.merge(old);
		cells.put(cp, chunk);

		this.memoizedRows = null;
	}

	/* memoization of computed row matrix，避免重复构造二维列表 */
	private List<List<RectangularTextContainer>> memoizedRows = null;

	/**
	 * 返回表格的行矩阵（List 每项为一行，行内为按列顺序的 RectangularTextContainer）。
	 *
	 * 如果尚未计算过，会调用 computeRows() 并缓存结果。
	 *
	 * @return 行矩阵，缺失单元格以 TextChunk.EMPTY 填充
	 */
	public List<List<RectangularTextContainer>> getRows() {
		if (this.memoizedRows == null)
			this.memoizedRows = computeRows();
		return this.memoizedRows;
	}

	/**
	 * 根据当前 rowCount/colCount 和 cells 映射创建完整的行矩阵。
	 *
	 * 任何未设置的单元格都会以 TextChunk.EMPTY 填充，保证矩阵为完整的 rowCount x colCount。
	 */
	private List<List<RectangularTextContainer>> computeRows() {
		List<List<RectangularTextContainer>> rows = new ArrayList<>();
		for (int i = 0; i < rowCount; i++) {
			List<RectangularTextContainer> lastRow = new ArrayList<>();
			rows.add(lastRow);
			for (int j = 0; j < colCount; j++) {
				RectangularTextContainer cell = cells.get(new CellPosition(i, j)); // JAVA_8 use getOrDefault()
				lastRow.add(cell != null ? cell : TextChunk.EMPTY);
			}
		}
		return rows;
	}

	/**
	 * 返回指定位置的单元格内容，如果该位置为空则返回 TextChunk.EMPTY（不会返回 null）。
	 *
	 * @param i 行索引（从 0 开始）
	 * @param j 列索引（从 0 开始）
	 * @return 对应的 RectangularTextContainer 或 TextChunk.EMPTY
	 */
	public RectangularTextContainer getCell(int i, int j) {
		RectangularTextContainer cell = cells.get(new CellPosition(i, j)); // JAVA_8 use getOrDefault()
		return cell != null ? cell : TextChunk.EMPTY;
	}

}

/**
 * 表示单元格的位置（行, 列），并实现 Comparable 以便在 TreeMap 中排序。
 *
 * CellPosition 是不可变的（row/col 字段为 final），并重写 equals/hashCode/compareTo。
 */
class CellPosition implements Comparable<CellPosition> {

	CellPosition(int row, int col) {
		this.row = row;
		this.col = col;
	}

	final int row, col;

	@Override
	public int hashCode() {
		return row + 101 * col;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CellPosition other = (CellPosition) obj;
		return row == other.row && col == other.col;
	}

	/**
	 * 比较先按行再按列，确保按自然表格顺序排序（即先行优先）。
	 */
	@Override
	public int compareTo(CellPosition other) {
		int rowdiff = row - other.row;
		return rowdiff != 0 ? rowdiff : col - other.col;
	}

}