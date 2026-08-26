package com.company.paymentanalysis.agent.tool.export;

import com.company.paymentanalysis.artifact.model.FileArtifactPayload.FileFormat;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Column;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.DataType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** Produces typed, formula-free exports from the reusable query artifact. */
@Service
public class PoiQueryResultExporter implements QueryResultExporter {

    private static final int MAX_ROWS = 100_000;
    private static final int MAX_COLUMNS = 256;

    @Override
    public ExportedContent export(QueryResultArtifactPayload query, FileFormat format) {
        validate(query);
        return switch (format) {
            case CSV -> new ExportedContent(csv(query), "text/csv; charset=UTF-8", "csv");
            case XLSX -> new ExportedContent(xlsx(query),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        };
    }

    private byte[] csv(QueryResultArtifactPayload query) {
        StringBuilder output = new StringBuilder("\uFEFF");
        appendCsvRow(output, query.columns().stream().map(Column::displayName).toList(), false);
        for (Map<String, Object> row : query.rows()) {
            appendCsvRow(output, query.columns().stream().map(column -> row.get(column.id())).toList(), true);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCsvRow(StringBuilder output, List<?> values, boolean protectFormula) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) output.append(',');
            String value = values.get(index) == null ? "" : values.get(index).toString();
            if (protectFormula && !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
                value = "'" + value;
            }
            output.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        output.append("\r\n");
    }

    private byte[] xlsx(QueryResultArtifactPayload query) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("查询结果");
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));

            Row header = sheet.createRow(0);
            for (int columnIndex = 0; columnIndex < query.columns().size(); columnIndex++) {
                Cell cell = header.createCell(columnIndex);
                cell.setCellValue(query.columns().get(columnIndex).displayName());
                cell.setCellStyle(headerStyle);
            }
            for (int rowIndex = 0; rowIndex < query.rows().size(); rowIndex++) {
                Row sheetRow = sheet.createRow(rowIndex + 1);
                Map<String, Object> sourceRow = query.rows().get(rowIndex);
                for (int columnIndex = 0; columnIndex < query.columns().size(); columnIndex++) {
                    Column column = query.columns().get(columnIndex);
                    writeCell(sheetRow.createCell(columnIndex), sourceRow.get(column.id()), column, dateStyle, dateTimeStyle);
                }
            }
            sheet.createFreezePane(0, 1);
            if (!query.columns().isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, query.rows().size(), 0, query.columns().size() - 1));
            }
            sizeColumns(sheet, query);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("XLSX 导出失败", exception);
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void writeCell(
            Cell cell, Object value, Column column, CellStyle dateStyle, CellStyle dateTimeStyle) {
        if (value == null) return;
        if (column.dataType() == DataType.NUMBER) {
            cell.setCellValue(decimal(value).doubleValue());
            return;
        }
        if (column.dataType() == DataType.BOOLEAN) {
            cell.setCellValue(value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString()));
            return;
        }
        if (column.dataType() == DataType.DATE) {
            LocalDate date = localDate(value);
            if (date != null) {
                cell.setCellValue(date);
                cell.setCellStyle(dateStyle);
                return;
            }
        }
        if (column.dataType() == DataType.DATETIME) {
            LocalDateTime dateTime = localDateTime(value);
            if (dateTime != null) {
                cell.setCellValue(dateTime);
                cell.setCellStyle(dateTimeStyle);
                return;
            }
        }
        cell.setCellValue(value.toString());
    }

    private void sizeColumns(Sheet sheet, QueryResultArtifactPayload query) {
        for (int columnIndex = 0; columnIndex < query.columns().size(); columnIndex++) {
            int characters = Math.max(8, query.columns().get(columnIndex).displayName().length() + 2);
            for (Map<String, Object> row : query.rows()) {
                Object value = row.get(query.columns().get(columnIndex).id());
                if (value != null) characters = Math.max(characters, Math.min(40, value.toString().length() + 2));
            }
            sheet.setColumnWidth(columnIndex, Math.min(40, characters) * 256);
        }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("数值列包含非数值内容");
        }
    }

    private LocalDate localDate(Object value) {
        if (value instanceof LocalDate date) return date;
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Instant instant) return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        try {
            return LocalDateTime.parse(value.toString());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(value.toString()), ZoneId.systemDefault());
            } catch (DateTimeParseException alsoIgnored) {
                return null;
            }
        }
    }

    private void validate(QueryResultArtifactPayload query) {
        if (query == null) throw new IllegalArgumentException("查询结果不能为空");
        if (query.columns().isEmpty()) throw new IllegalArgumentException("查询结果没有可导出的列");
        if (query.columns().size() > MAX_COLUMNS) throw new IllegalArgumentException("导出列数超过限制");
        if (query.rows().size() > MAX_ROWS) throw new IllegalArgumentException("导出行数超过限制");
    }
}
