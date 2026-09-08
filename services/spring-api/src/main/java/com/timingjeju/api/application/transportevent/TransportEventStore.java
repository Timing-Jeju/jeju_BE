package com.timingjeju.api.application.transportevent;

public interface TransportEventStore {
  TransportEventMutation upsert(TransportEventUpsertRecord record);

  TransportEventMutation delete(TransportEventDeleteRecord record);
}
