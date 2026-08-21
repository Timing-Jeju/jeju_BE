package com.timingjeju.api.application.tourapi.image;

import java.net.URI;
import java.nio.charset.StandardCharsets;

public record PlaceImage(
    String sourceImageId,
    String imageUrl,
    String thumbnailUrl,
    String imageName,
    String copyrightCode,
    String copyrightOwner,
    String licenseText,
    int displayOrder) {
  public PlaceImage {
    sourceImageId = optional(sourceImageId);
    imageUrl = required(imageUrl);
    thumbnailUrl = optional(thumbnailUrl);
    imageName = optional(imageName);
    copyrightCode = optional(copyrightCode);
    copyrightOwner = optional(copyrightOwner);
    licenseText = optional(licenseText);
    validateUrl(imageUrl);
    if (thumbnailUrl != null) validateUrl(thumbnailUrl);
    if (sourceImageId != null && bytes(sourceImageId) > 512) {
      throw PlaceImageImportException.invalidResponse();
    }
    for (String metadata : new String[] {imageName, copyrightCode, copyrightOwner, licenseText}) {
      if (metadata != null && bytes(metadata) > 8192) {
        throw PlaceImageImportException.invalidResponse();
      }
    }
    if (displayOrder < 1) throw PlaceImageImportException.invalidResponse();
  }

  private static String required(String value) {
    String result = optional(value);
    if (result == null) throw PlaceImageImportException.invalidResponse();
    return result;
  }

  private static String optional(String value) {
    if (value == null) return null;
    String result = value.strip();
    return result.isEmpty() ? null : result;
  }

  private static void validateUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (bytes(value) > 8192
          || !uri.isAbsolute()
          || (!"http".equalsIgnoreCase(uri.getScheme())
              && !"https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null) {
        throw PlaceImageImportException.invalidResponse();
      }
    } catch (IllegalArgumentException failure) {
      throw PlaceImageImportException.invalidResponse();
    }
  }

  private static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
