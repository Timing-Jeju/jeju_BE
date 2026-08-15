package com.timingjeju.api.application.tourapi.reference;

public interface ReferenceCodeRepository {
  ReferenceCodeUpsertResult upsert(ReferenceCodeUpsertCommand command);
}
