package com.timingjeju.api.global.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.timetable.OperatorTimetableMapping;
import com.timingjeju.api.application.timetable.TimetableParseException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JejuTimetableXlsxParserTest {
  private static final String S101_OUT = "101 남원-성산-김녕-조천-공항";
  private static final String S101_IN = "101 공항-조천-김녕-성산-남원";
  private static final String S201_OUT = "201 서귀포터미널-남원-성산-세화-조천-제주터미널";
  private static final String S201_IN = "201 제주터미널-조천-세화-성산-남원-서귀포터미널";
  private static final String B5_101_OUT =
      "첫차(성산일출봉 출발) 6:00, 막차 21:44, 배차간격 28분~43분, 금남여객(064-753-4423)";
  private static final String B5_101_IN =
      "첫차(성산일출봉 출발) 6:05, 막차 21:38, 배차간격 30분~120분, 금남여객(064-753-4423)";
  private static final String B5_201_OUT =
      "첫차(성산일출봉 출발) 5:43, 막차 21:35, 배차간격 15~22분, 금남여객(064-753-4423)";
  private static final String B5_201_IN =
      "첫차(성산일출봉 출발) 5:55, 막차 21:35, 배차간격 15~20분, 금남여객(064-753-4423)";
  private final JejuTimetableXlsxParser parser = new JejuTimetableXlsxParser();

  @Test
  void 공식_101은_long_sheet_B2_B3_B5_J5_row7_CK를_exact하게_파싱한다() throws Exception {
    byte[] xlsx = officialWorkbook("101", false);
    var parsed = parser.parse(xlsx, mapping101(), LocalDate.of(2024, 8, 15));
    assertThat(parsed.entries()).hasSize(396);
    assertThat(parsed.entries())
        .allSatisfy(
            entry -> {
              assertThat(entry.sourceRecordKey()).startsWith("3043887/405001/");
              assertThat(entry.sourceProvider()).isEqualTo("JEJU_PROVINCE");
              assertThat(entry.routeSourceProvider()).isEqualTo("TAGO");
              assertThat(entry.routeCityCode()).isEqualTo("39");
            });
    assertThat(parsed.entries().getFirst().sourceRecordKey()).contains("/101_");
  }

  @Test
  void H_mm_newline_annotation과_X_circle_blank_marker를_지원한다() throws Exception {
    assertThat(
            parser
                .parse(
                    officialWorkbook("101", false, Mutation.NEWLINE_ANNOTATION),
                    mapping101(),
                    LocalDate.of(2024, 8, 15))
                .entries())
        .hasSize(396);
    assertThat(
            parser
                .parse(
                    officialWorkbook("101", false, Mutation.MARKERS),
                    mapping101(),
                    LocalDate.of(2024, 8, 15))
                .entries())
        .hasSize(264);
  }

  @Test
  void 공식_201은_P5_IJ_JK_merged_header_data를_한_logical_event로_파싱한다() throws Exception {
    byte[] xlsx = ZipMutation.withBogusWorksheetDimension(officialWorkbook("201", true));
    var parsed = parser.parse(xlsx, mapping201(), LocalDate.of(2024, 8, 1));
    // 공식 시작 8~13행과 종료 65~70행의 병합 + 대표 normal 행. covered cell은 복제하지 않는다.
    assertThat(parsed.entries()).hasSize(1758);
    assertThat(parsed.omissions())
        .hasSize(102)
        .allSatisfy(value -> assertThat(value).contains("UNRESOLVED_OFFICIAL_COLUMN_OMITTED"));
    assertThat(parsed.entries())
        .allSatisfy(entry -> assertThat(entry.sourceRecordKey()).startsWith("3043887/405009/"));
    assertThat(parsed.canonicalManifest()).contains("\"effectiveDate\":\"2024-08-01\"");
  }

  @Test
  void 시간24_numeric_formula_header_direction_operations_effective_drift를_fail_closed한다()
      throws Exception {
    for (Mutation mutation :
        List.of(
            Mutation.TIME_24,
            Mutation.NUMERIC,
            Mutation.FORMULA,
            Mutation.HEADER,
            Mutation.DIRECTION,
            Mutation.OPERATIONS,
            Mutation.EFFECTIVE,
            Mutation.ROUTE)) {
      byte[] invalid = officialWorkbook("101", false, mutation);
      assertThatThrownBy(() -> parser.parse(invalid, mapping101(), LocalDate.of(2024, 8, 15)))
          .as(mutation.name())
          .isInstanceOf(TimetableParseException.class)
          .hasMessageContaining("sheet=");
    }
  }

  @Test
  void duplicate_trip과_stop순서_time역행을_거부하지만_서로다른stop의_동일시각은_허용한다() throws Exception {
    byte[] sameTime = officialWorkbook("101", false, Mutation.SAME_TIME_DIFFERENT_STOP);
    assertThat(parser.parse(sameTime, mapping101(), LocalDate.of(2024, 8, 15)).entries())
        .hasSize(396);
    for (Mutation mutation : List.of(Mutation.DUPLICATE_TRIP, Mutation.REVERSED_TIME)) {
      assertThatThrownBy(
              () ->
                  parser.parse(
                      officialWorkbook("101", false, mutation),
                      mapping101(),
                      LocalDate.of(2024, 8, 15)))
          .isInstanceOf(TimetableParseException.class);
    }
  }

  @Test
  void dry_run_parser는_모든_잘못된_행을_결정적_sheet_row순서로_수집한다() throws Exception {
    var report =
        parser.parse(
            officialWorkbook("101", false, Mutation.TIME_24),
            mapping101(),
            LocalDate.of(2024, 8, 15),
            true);
    assertThat(report.entries()).isEmpty();
    assertThat(report.rejectedRows())
        .hasSize(44)
        .allSatisfy(rejected -> assertThat(rejected).contains("INVALID_TIME"));
  }

  @Test
  void unknown_annotation과_공식규칙밖_merge를_fail_closed한다() throws Exception {
    assertThatThrownBy(
            () ->
                parser.parse(
                    officialWorkbook("101", false, Mutation.UNKNOWN_ANNOTATION),
                    mapping101(),
                    LocalDate.of(2024, 8, 15)))
        .hasMessageContaining("ANNOTATION_OVERRIDE_MISMATCH");
    assertThatThrownBy(
            () ->
                parser.parse(
                    officialWorkbook("201", true, Mutation.BAD_MERGE),
                    mapping201(),
                    LocalDate.of(2024, 8, 1)))
        .hasMessageContaining("MERGE_TOPOLOGY_MISMATCH");
  }

  @Test
  void 공식_merge_range는_하나라도_빠지거나_추가되면_전체거부한다() throws Exception {
    for (Mutation mutation :
        List.of(
            Mutation.MERGE_REMOVED,
            Mutation.BAD_MERGE,
            Mutation.MERGE_MOVED,
            Mutation.MERGE_RESIZED)) {
      assertThatThrownBy(
              () ->
                  parser.parse(
                      officialWorkbook("201", true, mutation),
                      mapping201(),
                      LocalDate.of(2024, 8, 1)))
          .as(mutation.name())
          .hasMessageContaining("MERGE_TOPOLOGY_MISMATCH");
    }
    byte[] valid = officialWorkbook("201", true);
    try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(valid))) {
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        int mergeCount = workbook.getSheetAt(sheetIndex).getNumMergedRegions();
        for (int mergeIndex = 0; mergeIndex < mergeCount; mergeIndex++) {
          byte[] missing = workbookWithoutMerge(valid, sheetIndex, mergeIndex);
          assertThatThrownBy(() -> parser.parse(missing, mapping201(), LocalDate.of(2024, 8, 1)))
              .as("sheet=%s merge=%s", sheetIndex, mergeIndex)
              .hasMessageContaining("MERGE_TOPOLOGY_MISMATCH");
        }
      }
    }
  }

  @Test
  void valid_workbook의_dry_run과_production_parse는_동일한_bounded_normalized_result다()
      throws Exception {
    byte[] xlsx = officialWorkbook("201", true);
    var production = parser.parse(xlsx, mapping201(), LocalDate.of(2024, 8, 1), false);
    var dryRun = parser.parse(xlsx, mapping201(), LocalDate.of(2024, 8, 1), true);
    assertThat(dryRun.entries()).isEqualTo(production.entries());
    assertThat(dryRun.omissions()).isEqualTo(production.omissions());
    assertThat(dryRun.canonicalManifest()).isEqualTo(production.canonicalManifest());
    assertThat(dryRun.entries())
        .hasSizeLessThanOrEqualTo(JejuTimetableXlsxParser.MAX_TOTAL_ENTRIES);
    assertThat(dryRun.omissions())
        .hasSizeLessThanOrEqualTo(JejuTimetableXlsxParser.MAX_TOTAL_OMISSIONS);
    assertThat(dryRun.canonicalManifest().getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(JejuTimetableXlsxParser.MAX_MANIFEST_BYTES);
  }

  @Test
  void zip_path_macro_external_link_expansion_file_row_sheet_bounds를_거부한다() throws Exception {
    byte[] valid = officialWorkbook("101", false);
    for (byte[] invalid :
        List.of(
            ZipMutation.addEntry(valid, "../escape", new byte[] {1}),
            ZipMutation.addEntry(
                valid, "[content_types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8)),
            ZipMutation.addEntry(valid, "xl/vbaProject.bin", new byte[] {1}),
            ZipMutation.addEntry(valid, "xl/externalLinks/externalLink1.xml", new byte[] {1}),
            ZipMutation.addEntry(valid, "xl/embeddings/oleObject1.bin", new byte[] {1}),
            ZipMutation.addEntry(
                valid, "xl/connections.xml", "<connections/>".getBytes(StandardCharsets.UTF_8)),
            ZipMutation.addEntry(
                valid,
                "xl/worksheets/_rels/sheet1.xml.rels",
                """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                    Target="https://example.invalid/" TargetMode="External"/>
                </Relationships>
                """
                    .getBytes(StandardCharsets.UTF_8)),
            ZipMutation.replaceEntry(
                valid,
                "[Content_Types].xml",
                """
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Override PartName="/xl/workbook.xml"
                    ContentType="application/vnd.ms-excel.sheet.macroEnabled.main+xml"/>
                </Types>
                """
                    .getBytes(StandardCharsets.UTF_8)),
            ZipMutation.addEntry(valid, "xl/huge.bin", new byte[(32 * 1024 * 1024) + 1]))) {
      assertThatThrownBy(() -> parser.parse(invalid, mapping101(), LocalDate.of(2024, 8, 15)))
          .isInstanceOf(TimetableParseException.class);
    }
    assertThatThrownBy(
            () ->
                parser.parse(
                    new byte[JejuTimetableXlsxParser.MAX_FILE_BYTES + 1],
                    mapping101(),
                    LocalDate.of(2024, 8, 15)))
        .hasMessageContaining("FILE_TOO_LARGE");
    assertThatThrownBy(
            () ->
                parser.parse(
                    officialWorkbook("101", false, Mutation.EXTRA_SHEET),
                    mapping101(),
                    LocalDate.of(2024, 8, 15)))
        .hasMessageContaining("SHEET_ALLOWLIST_MISMATCH");
    assertThatThrownBy(
            () ->
                parser.parse(
                    officialWorkbook("101", false, Mutation.ROW_LIMIT),
                    mapping101(),
                    LocalDate.of(2024, 8, 15)))
        .hasMessageContaining("ROW_LIMIT");
  }

  @Test
  void POI_process_global_zip_limit은_단일_owner가_한번만_설정하고_parse마다_변경하지_않는다() throws Exception {
    PoiZipSecurityPolicy.ensureInitialized();
    double ratio = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
    long entrySize = org.apache.poi.openxml4j.util.ZipSecureFile.getMaxEntrySize();
    parser.parse(officialWorkbook("101", false), mapping101(), LocalDate.of(2024, 8, 15));
    parser.parse(officialWorkbook("101", false), mapping101(), LocalDate.of(2024, 8, 15));
    assertThat(org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio()).isEqualTo(ratio);
    assertThat(org.apache.poi.openxml4j.util.ZipSecureFile.getMaxEntrySize()).isEqualTo(entrySize);
  }

  private static OperatorTimetableMapping mapping101() {
    return mapping(
        "405001",
        "101",
        LocalDate.of(2024, 8, 15),
        Map.of(
            S101_OUT,
            direction(
                "OUT",
                "남원 →일주동로 → 공항",
                B5_101_OUT,
                "남원",
                "표선",
                "고성",
                "성산",
                "세화",
                "김녕",
                "함덕",
                "제주 버스터미널",
                "공항"),
            S101_IN,
            direction(
                "IN",
                "공항 → 일주동로→ 남원",
                B5_101_IN,
                "공항",
                "제주 버스터미널",
                "함덕",
                "김녕",
                "세화",
                "성산",
                "고성",
                "표선",
                "남원")));
  }

  private static OperatorTimetableMapping mapping201() {
    return mapping(
        "405009",
        "201",
        LocalDate.of(2024, 8, 1),
        Map.of(
            S201_OUT,
            direction(
                "OUT",
                "서귀포터미널→일주동로 →제주터미널",
                B5_201_OUT,
                "서귀포버스터미널",
                "서귀포중앙R",
                "효돈초교(경유)",
                "남원",
                "표선",
                "신산",
                "고성",
                "고성",
                "성산",
                "오조(경유)",
                "세화고(경유)",
                "세화",
                "김녕",
                "함덕",
                "제주버스터미널"),
            S201_IN,
            direction(
                "IN",
                "제주터미널→일주동로 →서귀포터미널",
                B5_201_IN,
                "제주버스터미널",
                "함덕",
                "김녕",
                "세화",
                "세화고(경유)",
                "오조(경유)",
                "성산",
                "고성",
                "고성",
                "신산",
                "표선",
                "남원",
                "효돈초교(경유)",
                "서귀포중앙R",
                "서귀포버스터미널")));
  }

  private static OperatorTimetableMapping mapping(
      String schedule,
      String route,
      LocalDate effective,
      Map<String, OperatorTimetableMapping.Direction> directions) {
    return new OperatorTimetableMapping(
        "operator-mapping-v1",
        "3043887",
        schedule,
        route,
        effective,
        id("route-" + route),
        "TAGO",
        "39",
        directions);
  }

  private static OperatorTimetableMapping.Direction direction(
      String key, String metadata, String operations, String... labels) {
    List<OperatorTimetableMapping.Header> headers = new ArrayList<>();
    for (int index = 0; index < labels.length; index++) {
      String label = labels[index];
      if (index > 0 && label.equals(labels[index - 1])) {
        headers.add(
            new OperatorTimetableMapping.Header(
                "",
                null,
                true,
                Map.of(
                    "성산일출봉 출발", id("성산"),
                    "성산일출봉 종료", id("성산"),
                    "고성 경유", id("고성"))));
      } else {
        headers.add(
            new OperatorTimetableMapping.Header(
                label,
                id(label),
                false,
                Map.of(
                    "성산일출봉 출발", id("성산"),
                    "성산일출봉 종료", id("성산"),
                    "고성 경유", id("고성"))));
      }
    }
    return new OperatorTimetableMapping.Direction(key, metadata, hash(operations), headers);
  }

  private static byte[] officialWorkbook(String route, boolean merged) throws Exception {
    return officialWorkbook(route, merged, null);
  }

  private static byte[] officialWorkbook(String route, boolean merged, Mutation mutation)
      throws Exception {
    OperatorTimetableMapping mapping = route.equals("201") ? mapping201() : mapping101();
    try (var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      for (var item : mapping.directions().entrySet()) {
        var sheet = workbook.createSheet(item.getKey());
        sheet.createRow(1).createCell(1).setCellValue(route + "번");
        sheet.createRow(2).createCell(1).setCellValue(item.getValue().metadataDirection());
        sheet.createRow(3).createCell(1).setCellValue("노선 안내 metadata");
        var metadata = sheet.createRow(4);
        String operations =
            route.equals("101")
                ? (item.getValue().directionKey().equals("OUT") ? B5_101_OUT : B5_101_IN)
                : (item.getValue().directionKey().equals("OUT") ? B5_201_OUT : B5_201_IN);
        metadata.createCell(1).setCellValue(operations);
        metadata
            .createCell(route.equals("101") ? 9 : 15)
            .setCellValue(route.equals("101") ? "(시행일 : 2024.8.15.)" : "(시행일 : 2024.8.1.)");
        sheet.createRow(5).createCell(1).setCellValue("metadata");
        var header = sheet.createRow(6);
        header.createCell(1).setCellValue("구분");
        for (int i = 0; i < item.getValue().headers().size(); i++) {
          if (i > 0
              && item.getValue()
                  .headers()
                  .get(i)
                  .exactLabel()
                  .equals(item.getValue().headers().get(i - 1).exactLabel())) continue;
          header.createCell(2 + i).setCellValue(item.getValue().headers().get(i).exactLabel());
        }
        int mergeStart =
            route.equals("201") ? (item.getValue().directionKey().equals("OUT") ? 8 : 9) : -1;
        if (mergeStart >= 0)
          sheet.addMergedRegion(new CellRangeAddress(6, 6, mergeStart, mergeStart + 1));
        if (route.equals("201")) {
          for (int rowIndex = 7; rowIndex <= 12; rowIndex++)
            addDataRow(
                sheet, rowIndex, rowIndex - 6, item.getValue(), mergeStart, merged, mutation);
          for (int rowIndex = 13; rowIndex <= 63; rowIndex++)
            addDataRow(sheet, rowIndex, rowIndex - 6, item.getValue(), mergeStart, false, mutation);
          for (int rowIndex = 64; rowIndex <= 69; rowIndex++) {
            addDataRow(
                sheet, rowIndex, rowIndex - 6, item.getValue(), mergeStart, merged, mutation);
            if (item.getValue().directionKey().equals("OUT")) {
              var anchor = sheet.getRow(rowIndex).getCell(10);
              anchor.setCellValue(anchor.getStringCellValue() + " (성산일출봉 종료)");
              sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 10, 11));
            }
          }
        } else {
          for (int rowIndex = 7; rowIndex <= 28; rowIndex++)
            addDataRow(
                sheet, rowIndex, rowIndex - 6, item.getValue(), mergeStart, merged, mutation);
        }
        if (mutation == Mutation.DUPLICATE_TRIP)
          addDataRow(sheet, 8, 1, item.getValue(), -1, false, null);
      }
      if (mutation == Mutation.HEADER)
        workbook.getSheetAt(0).getRow(6).getCell(2).setCellValue("drift");
      if (mutation == Mutation.BAD_MERGE)
        workbook.getSheetAt(0).addMergedRegion(new CellRangeAddress(7, 7, 2, 3));
      if (mutation == Mutation.MERGE_REMOVED) workbook.getSheetAt(0).removeMergedRegion(0);
      if (mutation == Mutation.MERGE_MOVED) {
        workbook.getSheetAt(0).removeMergedRegion(0);
        workbook.getSheetAt(0).addMergedRegion(new CellRangeAddress(5, 5, 8, 9));
      }
      if (mutation == Mutation.MERGE_RESIZED) {
        workbook.getSheetAt(0).removeMergedRegion(0);
        workbook.getSheetAt(0).addMergedRegion(new CellRangeAddress(6, 6, 8, 10));
      }
      if (mutation == Mutation.DIRECTION)
        workbook.getSheetAt(0).getRow(2).getCell(1).setCellValue("drift");
      if (mutation == Mutation.OPERATIONS)
        workbook.getSheetAt(0).getRow(4).getCell(1).setCellValue("drift");
      if (mutation == Mutation.EFFECTIVE)
        workbook.getSheetAt(0).getRow(4).getCell(9).setCellValue("(시행일 : 2024.8.16.)");
      if (mutation == Mutation.ROUTE)
        workbook.getSheetAt(0).getRow(1).getCell(1).setCellValue("201번");
      if (mutation == Mutation.EXTRA_SHEET) workbook.createSheet("unexpected");
      if (mutation == Mutation.ROW_LIMIT)
        workbook.getSheetAt(0).createRow(JejuTimetableXlsxParser.MAX_ROWS_PER_SHEET);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static void addDataRow(
      org.apache.poi.ss.usermodel.Sheet sheet,
      int rowIndex,
      int trip,
      OperatorTimetableMapping.Direction direction,
      int mergeStart,
      boolean mergeData,
      Mutation mutation) {
    var row = sheet.createRow(rowIndex);
    row.createCell(1).setCellValue(trip);
    for (int i = 0; i < direction.headers().size(); i++) {
      int column = 2 + i;
      if (mergeData && column == mergeStart + 1) continue;
      String time = String.format("%d:%02d", 5 + ((i * 5) / 60), (i * 5) % 60);
      if (mutation == Mutation.TIME_24 && i == 0) time = "24:00";
      if (mutation == Mutation.REVERSED_TIME && i == 2) time = "4:59";
      if (mutation == Mutation.SAME_TIME_DIFFERENT_STOP && i == 1) time = "5:00";
      var cell = row.createCell(column);
      if (mutation == Mutation.NUMERIC && i == 0) cell.setCellValue(0.25d);
      else if (mutation == Mutation.FORMULA && i == 0) cell.setCellFormula("1+1");
      else if (mutation == Mutation.MARKERS && i >= 1 && i <= 3)
        cell.setCellValue(i == 1 ? "X" : i == 2 ? "○" : "");
      else {
        int annotationIndex = mergeData ? mergeStart - 2 : 0;
        String annotation = mutation == Mutation.UNKNOWN_ANNOTATION ? "추정" : "성산일출봉 출발";
        String separator = mutation == Mutation.NEWLINE_ANNOTATION ? "\n" : " ";
        cell.setCellValue(i == annotationIndex ? time + separator + "(" + annotation + ")" : time);
      }
    }
    if (mergeData)
      sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, mergeStart, mergeStart + 1));
  }

  private static byte[] workbookWithoutMerge(byte[] source, int sheetIndex, int mergeIndex)
      throws Exception {
    try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(source));
        var output = new ByteArrayOutputStream()) {
      workbook.getSheetAt(sheetIndex).removeMergedRegion(mergeIndex);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private enum Mutation {
    TIME_24,
    NUMERIC,
    FORMULA,
    HEADER,
    DIRECTION,
    OPERATIONS,
    EFFECTIVE,
    ROUTE,
    DUPLICATE_TRIP,
    REVERSED_TIME,
    SAME_TIME_DIFFERENT_STOP,
    EXTRA_SHEET,
    ROW_LIMIT,
    UNKNOWN_ANNOTATION,
    BAD_MERGE,
    MERGE_REMOVED,
    MERGE_MOVED,
    MERGE_RESIZED,
    NEWLINE_ANNOTATION,
    MARKERS
  }
}
