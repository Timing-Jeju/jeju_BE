package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GenerationAdmissionScopeTest {
  private static final UUID OWNER = UUID.fromString("53000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("53000000-0000-0000-0000-000000000002");
  private static final UUID DAY = UUID.fromString("53000000-0000-0000-0000-000000000003");
  private static final UUID OTHER = UUID.fromString("53000000-0000-0000-0000-000000000004");

  @Test
  void 최초_생성은_null_활성버전을_비교한다() {
    assertThatCode(() -> scope(null).validate(OWNER, TRIP, 7, command(null)))
        .doesNotThrowAnyException();
  }

  @Test
  void 소유권_불일치는_버전_정보보다_먼저_거부한다() {
    assertThatThrownBy(() -> scope(OTHER).validate(OTHER, TRIP, 1, command(null)))
        .hasMessage("TRIP_NOT_FOUND");
  }

  @Test
  void 다른_여행은_공개하지_않는다() {
    assertThatThrownBy(() -> scope(null).validate(OWNER, OTHER, 7, command(null)))
        .hasMessage("TRIP_NOT_FOUND");
  }

  @Test
  void 본인_여행_밖의_Day는_생성조건_오류다() {
    assertThatThrownBy(
            () -> scope(null).validate(OWNER, TRIP, 7, new CreateGenerationCommand(OTHER, null, 3)))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  @Test
  void 오래된_여행_revision을_거부한다() {
    assertThatThrownBy(() -> scope(null).validate(OWNER, TRIP, 6, command(null)))
        .hasMessage("TRIP_VERSION_CONFLICT");
  }

  @Test
  void null과_실제_활성버전의_양방향_경쟁을_거부한다() {
    assertThatThrownBy(() -> scope(OTHER).validate(OWNER, TRIP, 7, command(null)))
        .hasMessage("ACTIVE_SCHEDULE_VERSION_CONFLICT");
    assertThatThrownBy(() -> scope(null).validate(OWNER, TRIP, 7, command(OTHER)))
        .hasMessage("ACTIVE_SCHEDULE_VERSION_CONFLICT");
    assertThatCode(() -> scope(OTHER).validate(OWNER, TRIP, 7, command(OTHER)))
        .doesNotThrowAnyException();
  }

  @Test
  void DB에서_읽은_Day목록은_외부_수정과_분리한다() {
    var days = new java.util.ArrayList<>(List.of(DAY));
    var scope = new GenerationAdmissionScope(OWNER, TRIP, 7, null, days);
    days.clear();
    assertThatCode(() -> scope.validate(OWNER, TRIP, 7, command(null))).doesNotThrowAnyException();
  }

  private static GenerationAdmissionScope scope(UUID active) {
    return new GenerationAdmissionScope(OWNER, TRIP, 7, active, List.of(DAY));
  }

  private static CreateGenerationCommand command(UUID active) {
    return new CreateGenerationCommand(DAY, active, 3);
  }
}
