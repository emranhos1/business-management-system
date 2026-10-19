package com.eh.bms.service;

import java.util.List;

/**
 * One report as a plain table (Stage 16): the Reports page shows it and {@code ExcelExporter} writes it to .xlsx,
 * so both always hold the same rows. Headings and text are already in the language the report was built in.
 * <p>
 * A cell holds a {@code String}, a {@code BigDecimal} (money or a count), a {@code LocalDate} or null (blank),
 * matching its column's {@link Kind}. {@code totals} is an optional last row (null = none); a blank cell is null.
 * <p>
 * {@code more}: tables the Excel file gets as further sheets after this one (e.g. one ledger per party); the page
 * does not show them. {@code sheetName}: the name of this table's Excel sheet (null = its title).
 *
 * @param subtitle the filters the report was built with, e.g. the dates; may be empty
 */
public record ReportTable(String title, String subtitle, List<Column> columns, List<List<Object>> rows,
                          List<Object> totals, String sheetName, List<ReportTable> more) {

    /** What a column holds, so the screen and Excel format it the same way. */
    public enum Kind {
        TEXT,
        /** A money amount, shown with the currency symbol and 2 decimals; in Excel a number with 2 decimals. */
        MONEY,
        /** A whole number or quantity, no currency. */
        NUMBER,
        DATE
    }

    public record Column(String heading, Kind kind) {
    }

    /** A table with one sheet. */
    public ReportTable(String title, String subtitle, List<Column> columns, List<List<Object>> rows,
                       List<Object> totals) {
        this(title, subtitle, columns, rows, totals, null, List.of());
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** The same table with its Excel sheet named {@code name}. */
    public ReportTable named(String name) {
        return new ReportTable(title, subtitle, columns, rows, totals, name, more);
    }

    /** The same table whose Excel file also gets {@code sheets}, one after the other. */
    public ReportTable withSheets(List<ReportTable> sheets) {
        return new ReportTable(title, subtitle, columns, rows, totals, sheetName, sheets);
    }
}
