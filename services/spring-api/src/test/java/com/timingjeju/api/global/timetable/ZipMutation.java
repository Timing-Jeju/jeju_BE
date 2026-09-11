package com.timingjeju.api.global.timetable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class ZipMutation {
  private ZipMutation() {}

  static byte[] addEntry(byte[] source, String name, byte[] content) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var zipOutput = new ZipOutputStream(output);
        var zipInput = new ZipInputStream(new ByteArrayInputStream(source))) {
      for (ZipEntry entry; (entry = zipInput.getNextEntry()) != null; ) {
        zipOutput.putNextEntry(new ZipEntry(entry.getName()));
        zipInput.transferTo(zipOutput);
        zipOutput.closeEntry();
      }
      zipOutput.putNextEntry(new ZipEntry(name));
      zipOutput.write(content);
      zipOutput.closeEntry();
    }
    return output.toByteArray();
  }

  static byte[] withBogusWorksheetDimension(byte[] source) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var zipOutput = new ZipOutputStream(output);
        var zipInput = new ZipInputStream(new ByteArrayInputStream(source))) {
      for (ZipEntry entry; (entry = zipInput.getNextEntry()) != null; ) {
        zipOutput.putNextEntry(new ZipEntry(entry.getName()));
        byte[] content = zipInput.readAllBytes();
        if (entry.getName().startsWith("xl/worksheets/sheet")) {
          String xml = new String(content, StandardCharsets.UTF_8);
          content =
              xml.replaceFirst("<dimension ref=\"[^\"]+\"", "<dimension ref=\"A1\"")
                  .getBytes(StandardCharsets.UTF_8);
        }
        zipOutput.write(content);
        zipOutput.closeEntry();
      }
    }
    return output.toByteArray();
  }

  static byte[] replaceEntry(byte[] source, String targetName, byte[] replacement)
      throws Exception {
    var output = new ByteArrayOutputStream();
    try (var zipOutput = new ZipOutputStream(output);
        var zipInput = new ZipInputStream(new ByteArrayInputStream(source))) {
      for (ZipEntry entry; (entry = zipInput.getNextEntry()) != null; ) {
        zipOutput.putNextEntry(new ZipEntry(entry.getName()));
        if (entry.getName().equals(targetName)) zipOutput.write(replacement);
        else zipInput.transferTo(zipOutput);
        zipOutput.closeEntry();
      }
    }
    return output.toByteArray();
  }
}
