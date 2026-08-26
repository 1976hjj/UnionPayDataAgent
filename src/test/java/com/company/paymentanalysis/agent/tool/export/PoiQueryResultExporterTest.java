package com.company.paymentanalysis.agent.tool.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.artifact.model.FileArtifactPayload.FileFormat;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Column;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.DataType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.QueryContract;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Role;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PoiQueryResultExporterTest {

    private final PoiQueryResultExporter exporter = new PoiQueryResultExporter();

    @Test
    void exportsUtf8CsvWithEscapingAndSpreadsheetFormulaProtection() {
        var exported = exporter.export(queryResult(), FileFormat.CSV);
        String csv = new String(exported.content(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF\"月份\",\"金额\",\"说明\"\r\n");
        assertThat(csv).contains("\"2026-01-01\",\"12.50\",\"含,逗号\"");
        assertThat(csv).contains("\"'=2+2\"");
        assertThat(exported.mediaType()).startsWith("text/csv");
    }

    @Test
    void exportsAReadableXlsxWithTypedNumbersAndDates() throws Exception {
        var exported = exporter.export(queryResult(), FileFormat.XLSX);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported.content()))) {
            var sheet = workbook.getSheet("查询结果");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("金额");
            assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(12.5d);
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("=2+2");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
        }
    }

    private QueryResultArtifactPayload queryResult() {
        return new QueryResultArtifactPayload(
                "", List.of(
                        new Column("month", "月份", Role.DIMENSION, DataType.DATE, null),
                        new Column("amount", "金额", Role.METRIC, DataType.NUMBER, "元"),
                        new Column("note", "说明", Role.DIMENSION, DataType.STRING, null)),
                List.of(
                        Map.of("month", "2026-01-01", "amount", new BigDecimal("12.50"), "note", "含,逗号"),
                        Map.of("month", "2026-02-01", "amount", 20, "note", "=2+2")),
                2, false,
                new QueryContract("dataset", List.of("amount"), List.of("month", "note"), List.of(), List.of()));
    }
}
