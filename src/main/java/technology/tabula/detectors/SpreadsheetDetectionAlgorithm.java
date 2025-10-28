package technology.tabula.detectors;

import technology.tabula.Cell;
import technology.tabula.Page;
import technology.tabula.Rectangle;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.util.Collections;
import java.util.List;

/**
 * Created by matt on 2015-12-14.
 *
 * This is the basic spreadsheet table detection algorithm currently implemented
 * in tabula (web).
 * 这是目前在 tabula（web）中实现的基本电子表格表格检测算法。
 *
 * It uses intersecting ruling lines to find tables.
 * 它使用相交的标尺线来查找表格。
 */
public class SpreadsheetDetectionAlgorithm implements DetectionAlgorithm {
    @Override
    public List<Rectangle> detect(Page page) {
        List<Cell> cells = SpreadsheetExtractionAlgorithm.findCells(page.getHorizontalRulings(),
                page.getVerticalRulings());

        List<Rectangle> tables = SpreadsheetExtractionAlgorithm.findSpreadsheetsFromCells(cells);

        // we want tables to be returned from top to bottom on the page
        Collections.sort(tables, Rectangle.ILL_DEFINED_ORDER);

        return tables;
    }
}
