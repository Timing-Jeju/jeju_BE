package com.timingjeju.api.application.transportevent;

public interface TransportEventStore {
  void requireOwned(java.util.UUID ownerId, java.util.UUID tripId);

  TransportEventMutation upsert(TransportEventUpsertRecord record);

  TransportEventMutation delete(TransportEventDeleteRecord record);
}
