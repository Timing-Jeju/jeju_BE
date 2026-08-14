package com.timingjeju.api.application.tourapi;

public interface TourApiProvenanceWriter {

  TourApiProvenance write(TourApiProvenanceCommand command, Runnable normalizedWrite);
}
