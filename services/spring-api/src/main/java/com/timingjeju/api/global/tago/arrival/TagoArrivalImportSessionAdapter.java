package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportSourceKind;
import com.timingjeju.api.application.importing.ImportSyncMode;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalImportSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class TagoArrivalImportSessionAdapter implements TagoArrivalImportSession {
  static final String OPERATION = "getSttnAcctoArvlPrearngeInfoList";
  static final String PARSER_VERSION = "tago-arrival-v1";
  private static final long CACHE_SECONDS = 25;
  private final ImportRunLifecycleService runs;

  public TagoArrivalImportSessionAdapter(ImportRunLifecycleService runs) {
    this.runs = Objects.requireNonNull(runs, "runs는 필수입니다.");
  }

  @Override
  public ImportRunLease start(TagoArrivalCacheKey key, Instant observedAt) {
    Objects.requireNonNull(key, "key는 필수입니다.");
    Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    ImportRunScope scope = scope(key);
    String bucket = "arrival:" + Math.floorDiv(observedAt.getEpochSecond(), CACHE_SECONDS);
    return runs.start(
            new ImportRunStartCommand(
                ImportSourceKind.TAGO,
                "TAGO 정류장 도착정보",
                scope,
                "live",
                PARSER_VERSION,
                "tago-arrival-v1",
                ImportSyncMode.SNAPSHOT,
                sha256(OPERATION + ':' + key.cityCode() + ':' + key.nodeId()),
                bucket,
                null))
        .lease();
  }

  @Override
  public void fail(ImportRunLease lease, TagoArrivalException.Code code) {
    ImportRunFailure failure =
        switch (code) {
          case RATE_LIMITED, TIMEOUT, PROVIDER_UNAVAILABLE -> ImportRunFailure.PROVIDER_UNAVAILABLE;
          case EMPTY_RESULT, INVALID_PROVIDER_RESPONSE, INVALID_REQUEST ->
              ImportRunFailure.INVALID_PROVIDER_RESPONSE;
        };
    runs.fail(lease, failure);
  }

  static ImportRunScope scope(TagoArrivalCacheKey key) {
    return new ImportRunScope(key.provider(), key.service(), OPERATION, scopeKey(key));
  }

  static String scopeKey(TagoArrivalCacheKey key) {
    String readable = "stop:" + key.cityCode() + ':' + key.nodeId();
    return readable.getBytes(StandardCharsets.UTF_8).length <= 512
        ? readable
        : "stop:" + key.cityCode() + ":sha256:" + sha256(key.nodeId());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }
}
