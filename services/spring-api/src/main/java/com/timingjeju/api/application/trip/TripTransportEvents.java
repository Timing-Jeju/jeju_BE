package com.timingjeju.api.application.trip;

import com.timingjeju.api.application.transportevent.TransportEvent;

public record TripTransportEvents(TransportEvent arrival, TransportEvent departure) {
  public static TripTransportEvents empty() {
    return new TripTransportEvents(null, null);
  }
}
