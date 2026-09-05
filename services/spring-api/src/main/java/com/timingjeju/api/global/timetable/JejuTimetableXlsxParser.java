package com.timingjeju.api.global.timetable;

import com.timingjeju.api.application.timetable.OperatorTimetableMapping;
import com.timingjeju.api.application.timetable.ParsedTimetable;
import com.timingjeju.api.application.timetable.TimetableEntryCandidate;
import com.timingjeju.api.application.timetable.TimetableParseException;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class JejuTimetableXlsxParser {
  public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
  private static final int MAX_ZIP_ENTRIES = 512;
  private static final long MAX_EXPANDED_BYTES = 32L * 1024 * 1024;
  public static final int MAX_ROWS_PER_SHEET = 10_000;
  private static final int MAX_COLUMNS = 256;
  private static final int HEADER_ROW = 6;
  private static final int TRIP_COLUMN = 1;
  private static final int FIRST_STOP_COLUMN = 2;
  private static final String PROVIDER = "JEJU_PROVINCE";
  private static final String SERVICE = "jeju-bus-schedule-xlsx";
  private static final Pattern TIME =
      Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*\\(([^)]{1,80})\\))?$");
  private static final Pattern EFFECTIVE_DATE =
      Pattern.compile("^\\(시행일 : (\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})\\.\\)$");
  private static final Set<String> MARKERS = Set.of("", "X", "○");

  public ParsedTimetable parse(
      byte[] bytes, OperatorTimetableMapping mapping, LocalDate effectiveDate) {
    return parse(bytes, mapping, effectiveDate, false);
  }

  public ParsedTimetable parse(
      byte[] bytes,
      OperatorTimetableMapping mapping,
      LocalDate effectiveDate,
      boolean collectRejectedRows) {
    if (!mapping.effectiveDate().equals(effectiveDate)) {
      throw error("EFFECTIVE_DATE_MISMATCH", null, -1, -1);
    }
    if (bytes == null || bytes.length == 0) throw error("EMPTY_FILE", null, -1, -1);
    if (bytes.length > MAX_FILE_BYTES) throw error("FILE_TOO_LARGE", null, -1, -1);
    inspectZip(bytes);
    ZipSecureFile.setMinInflateRatio(0.01d);
    ZipSecureFile.setMaxEntrySize(MAX_EXPANDED_BYTES);
    ZipSecureFile.setMaxTextSize(MAX_EXPANDED_BYTES);
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      requireSheets(workbook, mapping);
      rejectAllFormulas(workbook);
      List<TimetableEntryCandidate> entries = new ArrayList<>();
      List<String> omissions = new ArrayList<>();
      List<String> rejectedRows = new ArrayList<>();
      Set<String> tripRows = new HashSet<>();
      Set<String> sourceKeys = new HashSet<>();
      for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
        Sheet sheet = workbook.getSheetAt(index);
        parseSheet(
            sheet,
            mapping,
            effectiveDate,
            entries,
            omissions,
            rejectedRows,
            collectRejectedRows,
            tripRows,
            sourceKeys);
      }
      entries.sort(Comparator.comparing(TimetableEntryCandidate::sourceRecordKey));
      return new ParsedTimetable(
          sha256(bytes),
          manifest(mapping, effectiveDate, entries, omissions),
          entries,
          rejectedRows,
          omissions);
    } catch (TimetableParseException exception) {
      throw exception;
    } catch (Exception exception) {
      throw error("UNSAFE_XLSX", null, -1, -1);
    }
  }

  private static void rejectAllFormulas(XSSFWorkbook workbook) {
    for (Sheet sheet : workbook) {
      for (Row row : sheet) {
        for (Cell cell : row) {
          if (cell.getCellType() == CellType.FORMULA) {
            throw error(
                "FORMULA_FORBIDDEN",
                sheet.getSheetName(),
                cell.getRowIndex() + 1,
                cell.getColumnIndex() + 1);
          }
        }
      }
    }
  }

  private static void requireSheets(XSSFWorkbook workbook, OperatorTimetableMapping mapping) {
    Set<String> actual = new HashSet<>();
    for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
      actual.add(workbook.getSheetName(index));
    }
    if (workbook.getNumberOfSheets() != mapping.directions().size()
        || !actual.equals(mapping.directions().keySet())) {
      throw error("SHEET_ALLOWLIST_MISMATCH", actual.toString(), -1, -1);
    }
  }

  private static void parseSheet(
      Sheet sheet,
      OperatorTimetableMapping mapping,
      LocalDate effectiveDate,
      List<TimetableEntryCandidate> entries,
      List<String> omissions,
      List<String> rejectedRows,
      boolean collectRejectedRows,
      Set<String> tripRows,
      Set<String> sourceKeys) {
    String sheetName = sheet.getSheetName();
    var direction = mapping.directions().get(sheetName);
    if (sheet.getLastRowNum() + 1 > MAX_ROWS_PER_SHEET) {
      throw error("ROW_LIMIT", sheetName, sheet.getLastRowNum() + 1, -1);
    }
    if (!(mapping.routeNo() + "번").equals(normalizedString(sheet, 1, 1))) {
      throw error("HEADER_DRIFT", sheetName, 2, 2);
    }
    if (!direction.metadataDirection().equals(normalizedString(sheet, 2, 1))) {
      throw error("DIRECTION_METADATA_MISMATCH", sheetName, 3, 2);
    }
    String operations = normalizedString(sheet, 4, 1);
    if (!direction
        .operationsSha256()
        .equals(sha256(operations.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
      throw error("OPERATIONS_METADATA_MISMATCH", sheetName, 5, 2);
    }
    int effectiveColumn = mapping.routeNo().equals("101") ? 9 : 15;
    Matcher effective = EFFECTIVE_DATE.matcher(normalizedString(sheet, 4, effectiveColumn));
    if (!effective.matches()
        || !LocalDate.of(
                Integer.parseInt(effective.group(1)),
                Integer.parseInt(effective.group(2)),
                Integer.parseInt(effective.group(3)))
            .equals(effectiveDate)) {
      throw error("EFFECTIVE_DATE_MISMATCH", sheetName, 5, effectiveColumn + 1);
    }
    Row header = sheet.getRow(HEADER_ROW);
    if (header == null || header.getLastCellNum() > MAX_COLUMNS) {
      throw error("HEADER_DRIFT", sheetName, HEADER_ROW + 1, -1);
    }
    if (header.getLastCellNum() != FIRST_STOP_COLUMN + direction.headers().size()) {
      throw error("HEADER_DRIFT", sheetName, HEADER_ROW + 1, header.getLastCellNum());
    }
    if (!"구분".equals(normalizedString(sheet, HEADER_ROW, TRIP_COLUMN))) {
      throw error("HEADER_DRIFT", sheetName, HEADER_ROW + 1, TRIP_COLUMN + 1);
    }
    List<Header> headers = new ArrayList<>();
    for (int offset = 0; offset < direction.headers().size(); offset++) {
      int column = FIRST_STOP_COLUMN + offset;
      OperatorTimetableMapping.Header expected = direction.headers().get(offset);
      String directHeader = normalizeText(stringValue(sheet, HEADER_ROW, column, false));
      String stopName = normalizedMergedString(sheet, HEADER_ROW, column);
      if ((expected.unresolved()
              && (!directHeader.isEmpty() || mergedRange(sheet, HEADER_ROW, column) == null))
          || (!expected.unresolved() && !expected.exactLabel().equals(stopName))) {
        throw error("HEADER_DRIFT", sheetName, HEADER_ROW + 1, column + 1);
      }
      headers.add(new Header(column, stopName, expected));
    }

    for (int rowIndex = HEADER_ROW + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;
      int entryCheckpoint = entries.size();
      int omissionCheckpoint = omissions.size();
      Set<String> tripCheckpoint = new HashSet<>(tripRows);
      Set<String> keyCheckpoint = new HashSet<>(sourceKeys);
      try {
        String trip = tripValue(sheet, rowIndex, TRIP_COLUMN);
        if (trip.isBlank()) continue;
        String tripIdentity = direction.directionKey() + '/' + trip;
        if (!tripRows.add(tripIdentity)) {
          throw error("DUPLICATE_SOURCE_ROW", sheetName, rowIndex + 1, 1);
        }
        LocalTime previousTime = null;
        for (Header stop : headers) {
          CellRangeAddress merged = mergedRange(sheet, rowIndex, stop.column());
          if (merged != null && stop.column() != merged.getFirstColumn()) {
            continue;
          }
          if (merged != null) validateMergedEvent(headers, merged, sheetName, rowIndex);
          Cell cell = row.getCell(stop.column(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
          String value = normalizeText(stringValue(sheet, rowIndex, stop.column(), false));
          if (MARKERS.contains(value)) continue;
          if (cell != null && cell.getCellType() != CellType.STRING) {
            throw error("TIME_TYPE_MISMATCH", sheetName, rowIndex + 1, stop.column() + 1);
          }
          Matcher matcher = TIME.matcher(value);
          if (!matcher.matches()) {
            throw error("INVALID_TIME", sheetName, rowIndex + 1, stop.column() + 1);
          }
          LocalTime time =
              LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
          String annotation = matcher.group(3);
          if (merged != null
              && !stop.mapping().unresolved()
              && !headers.get(merged.getLastColumn() - FIRST_STOP_COLUMN).mapping().unresolved()
              && annotation == null) {
            throw error(
                "MERGED_EVENT_ANNOTATION_REQUIRED", sheetName, rowIndex + 1, stop.column() + 1);
          }
          UUID targetStopId = stop.mapping().stopId();
          if (stop.mapping().unresolved() && annotation == null) {
            omissions.add(
                sheetName
                    + "/row="
                    + (rowIndex + 1)
                    + "/column="
                    + (stop.column() + 1)
                    + "/UNRESOLVED_OFFICIAL_COLUMN_OMITTED");
            continue;
          }
          if (annotation != null) {
            targetStopId = stop.mapping().annotationOverrides().get(annotation);
            if (targetStopId == null) {
              throw error(
                  "ANNOTATION_OVERRIDE_MISMATCH", sheetName, rowIndex + 1, stop.column() + 1);
            }
          }
          if (previousTime != null && time.isBefore(previousTime)) {
            throw error("TIME_ORDER_MISMATCH", sheetName, rowIndex + 1, stop.column() + 1);
          }
          previousTime = time;
          String sourceKey =
              String.join(
                  "/",
                  mapping.datasetId(),
                  mapping.scheduleId(),
                  canonical(sheetName),
                  canonical(direction.directionKey()),
                  Integer.toString(rowIndex + 1),
                  targetStopId.toString(),
                  "daily",
                  time.format(DateTimeFormatter.ofPattern("HH:mm")));
          if (!sourceKeys.add(sourceKey)) {
            throw error("DUPLICATE_SOURCE_ROW", sheetName, rowIndex + 1, stop.column() + 1);
          }
          entries.add(
              new TimetableEntryCandidate(
                  mapping.routeId(),
                  targetStopId,
                  direction.directionKey(),
                  "daily",
                  time,
                  sourceKey,
                  PROVIDER,
                  SERVICE,
                  mapping.routeSourceProvider(),
                  mapping.routeCityCode()));
        }
      } catch (TimetableParseException rowFailure) {
        entries.subList(entryCheckpoint, entries.size()).clear();
        omissions.subList(omissionCheckpoint, omissions.size()).clear();
        tripRows.clear();
        tripRows.addAll(tripCheckpoint);
        sourceKeys.clear();
        sourceKeys.addAll(keyCheckpoint);
        if (!collectRejectedRows) throw rowFailure;
        rejectedRows.add(rowFailure.getMessage());
      }
    }
  }

  private static void validateMergedEvent(
      List<Header> headers, CellRangeAddress range, String sheetName, int rowIndex) {
    boolean edgeRows = rowIndex >= 7 && rowIndex <= 12 || rowIndex >= 64 && rowIndex <= 69;
    boolean officialPrimary =
        edgeRows
            && (sheetName.equals("201 서귀포터미널-남원-성산-세화-조천-제주터미널")
                    && range.getFirstColumn() == 8
                    && range.getLastColumn() == 9
                || sheetName.equals("201 제주터미널-조천-세화-성산-남원-서귀포터미널")
                    && range.getFirstColumn() == 9
                    && range.getLastColumn() == 10);
    boolean officialAnnotatedEnd =
        rowIndex >= 64
            && rowIndex <= 69
            && sheetName.equals("201 서귀포터미널-남원-성산-세화-조천-제주터미널")
            && range.getFirstColumn() == 10
            && range.getLastColumn() == 11;
    if (!officialPrimary && !officialAnnotatedEnd) {
      throw error(
          "MERGED_EVENT_MAPPING_MISMATCH", sheetName, rowIndex + 1, range.getFirstColumn() + 1);
    }
    Header anchor = headers.get(range.getFirstColumn() - FIRST_STOP_COLUMN);
    if (anchor.mapping().unresolved()) {
      throw error(
          "MERGED_EVENT_MAPPING_MISMATCH", sheetName, rowIndex + 1, range.getFirstColumn() + 1);
    }
    for (int column = range.getFirstColumn(); column <= range.getLastColumn(); column++) {
      int headerIndex = column - FIRST_STOP_COLUMN;
      if (headerIndex < 0
          || headerIndex >= headers.size()
          || (column != range.getFirstColumn()
              && !headers.get(headerIndex).mapping().unresolved()
              && !officialAnnotatedEnd)) {
        throw error("MERGED_EVENT_MAPPING_MISMATCH", sheetName, rowIndex + 1, column + 1);
      }
    }
  }

  private static String tripValue(Sheet sheet, int row, int column) {
    Cell cell = sheet.getRow(row).getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    if (cell == null || cell.getCellType() == CellType.BLANK) return "";
    if (cell.getCellType() == CellType.STRING) return normalizeText(cell.getStringCellValue());
    if (cell.getCellType() == CellType.NUMERIC
        && cell.getNumericCellValue() == Math.rint(cell.getNumericCellValue())) {
      return Long.toString((long) cell.getNumericCellValue());
    }
    throw error("TRIP_TYPE_MISMATCH", sheet.getSheetName(), row + 1, column + 1);
  }

  private static String mergedStringValue(Sheet sheet, int row, int column) {
    String direct = stringValue(sheet, row, column, false);
    if (!direct.isBlank()) return direct;
    for (CellRangeAddress range : sheet.getMergedRegions()) {
      if (range.isInRange(row, column)) {
        return stringValue(sheet, range.getFirstRow(), range.getFirstColumn(), false);
      }
    }
    return "";
  }

  private static CellRangeAddress mergedRange(Sheet sheet, int row, int column) {
    for (CellRangeAddress range : sheet.getMergedRegions()) {
      if (range.isInRange(row, column)) return range;
    }
    return null;
  }

  private static String normalizedMergedString(Sheet sheet, int row, int column) {
    return normalizeText(mergedStringValue(sheet, row, column));
  }

  private static String normalizedString(Sheet sheet, int row, int column) {
    return normalizeText(stringValue(sheet, row, column, true));
  }

  private static String normalizeText(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
  }

  private static String stringValue(Sheet sheet, int row, int column, boolean requireString) {
    Row foundRow = sheet.getRow(row);
    if (foundRow == null) return "";
    Cell cell = foundRow.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    if (cell == null) return "";
    if (cell.getCellType() == CellType.FORMULA) {
      throw error("FORMULA_FORBIDDEN", sheet.getSheetName(), row + 1, column + 1);
    }
    if (cell.getCellType() == CellType.BLANK) return "";
    if (requireString && cell.getCellType() != CellType.STRING) {
      throw error("CELL_TYPE_MISMATCH", sheet.getSheetName(), row + 1, column + 1);
    }
    if (cell.getCellType() != CellType.STRING) return "#NON_STRING#";
    return cell.getStringCellValue().strip();
  }

  private static void inspectZip(byte[] bytes) {
    try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      int entries = 0;
      long expanded = 0;
      byte[] buffer = new byte[8192];
      for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
        String name = entry.getName().replace('\\', '/');
        String lower = name.toLowerCase(Locale.ROOT);
        if (++entries > MAX_ZIP_ENTRIES
            || entry.isDirectory() && (name.startsWith("/") || name.contains("../"))
            || name.startsWith("/")
            || name.equals("..")
            || name.startsWith("../")
            || name.contains("/../")
            || lower.endsWith("vbaproject.bin")
            || lower.startsWith("xl/externallinks/")) {
          throw error("UNSAFE_XLSX", name, -1, -1);
        }
        for (int read; (read = input.read(buffer)) >= 0; ) {
          expanded += read;
          if (expanded > MAX_EXPANDED_BYTES) throw error("UNSAFE_XLSX", name, -1, -1);
        }
      }
    } catch (TimetableParseException exception) {
      throw exception;
    } catch (Exception exception) {
      throw error("UNSAFE_XLSX", null, -1, -1);
    }
  }

  private static String manifest(
      OperatorTimetableMapping mapping,
      LocalDate effectiveDate,
      List<TimetableEntryCandidate> entries,
      List<String> omissions) {
    String keys =
        entries.stream()
            .map(e -> "\"" + e.sourceRecordKey() + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    return "{\"datasetId\":\""
        + mapping.datasetId()
        + "\",\"effectiveDate\":\""
        + effectiveDate
        + "\",\"mappingVersion\":\""
        + mapping.version()
        + "\",\"omissionCount\":"
        + omissions.size()
        + ",\"omissionCode\":\"UNRESOLVED_OFFICIAL_COLUMN_OMITTED\",\"parserVersion\":\"jeju-timetable-xlsx-v1\",\"recordKeys\":["
        + keys
        + "]}";
  }

  private static String canonical(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static TimetableParseException error(String code, String sheet, int row, int column) {
    return new TimetableParseException(
        code,
        "sheet=" + (sheet == null ? "<workbook>" : sheet) + " row=" + row + " column=" + column);
  }

  private record Header(int column, String stopName, OperatorTimetableMapping.Header mapping) {}
}
