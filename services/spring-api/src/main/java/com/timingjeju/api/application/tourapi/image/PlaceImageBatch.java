package com.timingjeju.api.application.tourapi.image;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record PlaceImageBatch(
    String contentId, String contentTypeId, List<PlaceImageWrite> writes) {
  public PlaceImageBatch {
    if (contentId == null
        || contentId.isBlank()
        || contentTypeId == null
        || contentTypeId.isBlank()
        || writes == null) {
      throw new IllegalArgumentException("image batch identity가 올바르지 않습니다.");
    }
    contentId = contentId.strip();
    contentTypeId = contentTypeId.strip();
    writes = List.copyOf(writes);
    Set<String> ids = new HashSet<>();
    Set<String> urls = new HashSet<>();
    for (PlaceImageWrite write : writes) {
      PlaceImage image = write.image();
      if (image.sourceImageId() != null && !ids.add(image.sourceImageId())) {
        throw PlaceImageImportException.invalidResponse();
      }
      if (!urls.add(image.imageUrl())) throw PlaceImageImportException.invalidResponse();
    }
  }

  public List<PlaceImage> images() {
    return writes.stream().map(PlaceImageWrite::image).toList();
  }
}
