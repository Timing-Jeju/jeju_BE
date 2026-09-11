package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.trip.TripException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Tag("architecture")
class JdbcTripPreferencesCoordinatorArchitectureTest {

  @Test
  void coordinator후_root는있지만_required_preference_projection이없으면_internal_corruption이다() {
    assertThatThrownBy(() -> JdbcTripStore.requirePreferenceProjectionForTest(null))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("INTERNAL_SERVER_ERROR");
  }

  @Test
  void replace는_owner_lock부터_rows와_root_revision까지_한_transaction에서_처리한다() throws Exception {
    assertThat(
            JdbcTripStore.class
                .getMethod(
                    "replacePreferences",
                    com.timingjeju.api.application.trip.ReplaceTripPreferencesRecord.class)
                .getAnnotation(Transactional.class))
        .isNotNull();
  }

  @Test
  void callback_DB_constraint는_exact_identity로_preference와_place오류를구분한다() {
    assertMapped(
        failure("23514", "trip_transport_modes_aggregate_check"),
        "PREFERENCE_CONSTRAINT_VIOLATION");
    assertMapped(
        failure("23505", "uq_trip_transport_modes_primary"), "PREFERENCE_CONSTRAINT_VIOLATION");
    assertMapped(failure("23503", "trip_preferences_start_place_id_fkey"), "PLACE_NOT_FOUND");
    assertMapped(failure("23503", "trip_preferences_end_place_id_fkey"), "PLACE_NOT_FOUND");
    DataIntegrityViolationException unknown = failure("23514", "unrelated_constraint");
    assertThat(JdbcTripStore.translatePreferenceWriteFailureForTest(unknown)).isSameAs(unknown);
  }

  private static void assertMapped(DataIntegrityViolationException failure, String code) {
    assertThatThrownBy(
            () -> {
              throw JdbcTripStore.translatePreferenceWriteFailureForTest(failure);
            })
        .isInstanceOf(com.timingjeju.api.application.trip.TripException.class)
        .extracting(error -> ((com.timingjeju.api.application.trip.TripException) error).code())
        .isEqualTo(code);
  }

  private static DataIntegrityViolationException failure(String sqlState, String constraint) {
    String fields = "SERROR\0C" + sqlState + "\0Mredacted\0n" + constraint + "\0\0";
    return new DataIntegrityViolationException(
        "redacted", new PSQLException(new ServerErrorMessage(fields)));
  }
}
