package com.eh.bms.util;

import com.eh.bms.service.ReportTable;
import com.eh.bms.service.ReportTable.Column;
import com.eh.bms.service.ReportTable.Kind;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes a {@link ReportTable} to an .xlsx file (16.1): business name, report title and filters on top, then the
 * headings, the rows and the totals row. Money, counts and dates are real Excel numbers and dates (so they can be
 * summed and sorted there), not text. The date format follows the app's date format setting.
 */
public final class ExcelExporter {

    private static final String MONEY_FORMAT = "#,##0.00";
    private static final String NUMBER_FORMAT = "#,##0.###";
    private static final String FALLBACK_DATE_FORMAT = "dd/mm/yyyy";
    /** Column width limits, in characters. */
    private static final int MIN_WIDTH = 10;
    private static final int MAX_WIDTH = 60;
    private static final double TEXT_WIDTH_FACTOR = 1.3;
    /** Excel allows 31 characters in a sheet name. */
    private static final int MAX_SHEET_NAME = 31;

    private ExcelExporter() {
    }

    /**
     * Saves {@code table} to {@code file}, replacing it if it exists: its own sheet first, then one sheet for
     * each of its {@link ReportTable#more()} tables.
     *
     * @param businessName shown on the first line of every sheet; blank = left out
     */
    public static void export(Path file, ReportTable table, String businessName) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Styles styles = new Styles(workbook);
            Set<String> used = new HashSet<>();
            writeSheet(workbook, styles, table, businessName, uniqueName(table, used));
            for (ReportTable more : table.more()) {
                writeSheet(workbook, styles, more, businessName, uniqueName(more, used));
            }
            workbook.write(out);
        }
    }

    /** The sheet's name: the table's own, no characters Excel refuses, at most 31 long, and not used before. */
    private static String uniqueName(ReportTable table, Set<String> used) {
        String base = WorkbookUtil.createSafeSheetName(table.sheetName() == null ? table.title() : table.sheetName());
        String name = base;
        for (int n = 2; !used.add(name.toLowerCase()); n++) {
            String suffix = " " + n;
            name = base.substring(0, Math.min(base.length(), MAX_SHEET_NAME - suffix.length())) + suffix;
        }
        return name;
    }

    private static void writeSheet(Workbook workbook, Styles styles, ReportTable table, String businessName,
                                   String sheetName) {
        Sheet sheet = workbook.createSheet(sheetName);
        List<Column> columns = table.columns();
        int[] widths = new int[columns.size()];

        int r = 0;
        if (businessName != null && !businessName.isBlank()) {
            text(sheet.createRow(r++), 0, businessName, styles.big);
        }
        text(sheet.createRow(r++), 0, table.title(), styles.bold);
        if (table.subtitle() != null && !table.subtitle().isBlank()) {
            text(sheet.createRow(r++), 0, table.subtitle(), styles.plain);
        }
        r++; // blank line

        Row head = sheet.createRow(r++);
        for (int c = 0; c < columns.size(); c++) {
            text(head, c, columns.get(c).heading(), styles.header);
            widths[c] = textWidth(columns.get(c).heading());
        }
        sheet.createFreezePane(0, r);

        for (List<Object> values : table.rows()) {
            writeRow(sheet.createRow(r++), columns, values, styles, false, widths);
        }
        if (table.totals() != null) {
            writeRow(sheet.createRow(r), columns, table.totals(), styles, true, widths);
        }

        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, widths[c] + 2)) * 256);
        }
    }

    /** Bangla letters with their signs run wider than a Latin character, and bold text wider again. */
    private static int textWidth(String text) {
        return (int) Math.ceil(text.length() * TEXT_WIDTH_FACTOR);
    }

    private static void writeRow(Row row, List<Column> columns, List<Object> values, Styles styles, boolean total,
                                 int[] widths) {
        for (int c = 0; c < columns.size() && c < values.size(); c++) {
            Object value = values.get(c);
            if (value == null) {
                if (total) {
                    row.createCell(c).setCellStyle(styles.of(Kind.TEXT, true));
                }
                continue;
            }
            Cell cell = row.createCell(c);
            Kind kind = columns.get(c).kind();
            cell.setCellStyle(styles.of(kind, total));
            switch (value) {
                case BigDecimal number -> {
                    cell.setCellValue(number.doubleValue());
                    widths[c] = Math.max(widths[c], number.setScale(2, RoundingMode.HALF_UP).toPlainString().length() + 3);
                }
                case LocalDate date -> {
                    cell.setCellValue(date);
                    widths[c] = Math.max(widths[c], 12);
                }
                case Number number -> {
                    cell.setCellValue(number.doubleValue());
                    widths[c] = Math.max(widths[c], String.valueOf(number).length());
                }
                default -> {
                    String s = value.toString();
                    cell.setCellValue(s);
                    widths[c] = Math.max(widths[c], textWidth(s));
                }
            }
        }
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** The cell styles of one workbook (a workbook allows only a limited number of them, so they are shared). */
    private static final class Styles {
        final CellStyle plain;
        final CellStyle bold;
        final CellStyle big;
        final CellStyle header;
        private final Workbook workbook;
        private final CellStyle[] rows = new CellStyle[Kind.values().length];
        private final CellStyle[] totals = new CellStyle[Kind.values().length];
        private final String dateFormat;

        Styles(Workbook workbook) {
            this.workbook = workbook;
            this.dateFormat = excelDateFormat();
            plain = workbook.createCellStyle();
            bold = workbook.createCellStyle();
            bold.setFont(font(true, 12));
            big = workbook.createCellStyle();
            big.setFont(font(true, 14));
            header = workbook.createCellStyle();
            header.setFont(font(true, 11));
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setAlignment(HorizontalAlignment.CENTER);
        }

        CellStyle of(Kind kind, boolean total) {
            CellStyle[] cache = total ? totals : rows;
            if (cache[kind.ordinal()] == null) {
                CellStyle style = workbook.createCellStyle();
                if (total) {
                    style.setFont(font(true, 11));
                    style.setBorderTop(BorderStyle.THIN);
                }
                String format = switch (kind) {
                    case MONEY -> MONEY_FORMAT;
                    case NUMBER -> NUMBER_FORMAT;
                    case DATE -> dateFormat;
                    case TEXT -> null;
                };
                if (format != null) {
                    style.setDataFormat(workbook.createDataFormat().getFormat(format));
                }
                if (kind == Kind.DATE) {
                    style.setAlignment(HorizontalAlignment.LEFT);
                }
                cache[kind.ordinal()] = style;
            }
            return cache[kind.ordinal()];
        }

        private Font font(boolean bold, int size) {
            Font font = workbook.createFont();
            font.setBold(bold);
            font.setFontHeightInPoints((short) size);
            return font;
        }

        /** The app's date pattern as an Excel one ("dd/MM/yyyy" becomes "dd/mm/yyyy"). */
        private static String excelDateFormat() {
            try {
                String pattern = DateUtil.pattern();
                return pattern == null || pattern.isBlank() ? FALLBACK_DATE_FORMAT : pattern.toLowerCase();
            } catch (RuntimeException e) {
                return FALLBACK_DATE_FORMAT;
            }
        }
    }
}
