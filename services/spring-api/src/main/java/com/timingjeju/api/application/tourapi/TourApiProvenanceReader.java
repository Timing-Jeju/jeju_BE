package com.timingjeju.api.application.tourapi;

import java.util.List;
import java.util.UUID;

public interface TourApiProvenanceReader {

  List<TourApiProvenance> findByNormalizedRow(String normalizedEntityType, UUID normalizedRowId);
}
