package com.timingjeju.api.application.tourapi;

import java.util.Map;

public interface TourApiRequestFingerprinter {

  String fingerprint(Map<String, String> parameters);
}
