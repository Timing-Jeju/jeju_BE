package com.timingjeju.api.application.tourapi;

public interface TourApiProvenanceWriter {

  /**
   * 등록 operation과 snapshot/run 계보를 먼저 검증한 뒤 정규화 DB write와 provenance를 같은 transaction에서 기록합니다.
   * {@code normalizedWrite}는 현재 transaction에 참여하는 DB 작업만 수행해야 하며 외부 API, 메시지 발행, 파일 쓰기 등 rollback할
   * 수 없는 부수효과를 수행해서는 안 됩니다.
   */
  TourApiProvenance write(TourApiProvenanceCommand command, Runnable normalizedWrite);
}
