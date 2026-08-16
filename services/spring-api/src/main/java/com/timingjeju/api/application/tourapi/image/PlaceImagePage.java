package com.timingjeju.api.application.tourapi.image;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record PlaceImagePage(
    String contentId,
    int pageNo,
    int numOfRows,
    int totalCount,
    int rawItemCount,
    List<PlaceImage> images) {
  public PlaceImagePage {
    if (contentId == null || contentId.isBlank()) throw PlaceImageImportException.invalidResponse();
    contentId = contentId.strip();
    if (pageNo < 1
        || numOfRows < 1
        || totalCount < 0
        || rawItemCount < 0
        || images == null
        || rawItemCount != images.size()
        || rawItemCount > numOfRows) {
      throw PlaceImageImportException.invalidResponse();
    }
    images = List.copyOf(images);
    Set<String> sourceIds = new HashSet<>();
    Set<String> urls = new HashSet<>();
    for (PlaceImage image : images) {
      if (image.sourceImageId() != null && !sourceIds.add(image.sourceImageId())) {
        throw PlaceImageImportException.invalidResponse();
      }
      if (!urls.add(image.imageUrl())) throw PlaceImageImportException.invalidResponse();
    }
  }
}
