package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripPlannerConditionsTest {
  private static final UUID DAY = UUID.fromString("53000000-0000-0000-0000-000000000001");
  private static final UUID PLACE = UUID.fromString("53000000-0000-0000-0000-000000000002");

  @Test
  void 장소_ID와_코드만_보존하고_입력_컬렉션_변경과_격리한다() {
    var styles = new ArrayList<>(List.of("restaurant", "relaxed", "trendy"));
    var conditions =
        new TripPlannerConditions(List.of(new TripPlannerConditions.DayAnchor(DAY, PLACE)), styles);
    styles.clear();
    assertThat(conditions.styleCodes()).containsExactly("relaxed", "restaurant", "trendy");
    assertThat(conditions.dayAnchors().getFirst().lodgingPlaceId()).isEqualTo(PLACE);
  }

  @Test
  void 같은_Day의_중복과_알수없는_스타일을_거부한다() {
    var anchor = new TripPlannerConditions.DayAnchor(DAY, PLACE);
    assertThatThrownBy(() -> new TripPlannerConditions(List.of(anchor, anchor), List.of()))
        .isInstanceOf(TripException.class);
    assertThatThrownBy(() -> new TripPlannerConditions(List.of(anchor), List.of("사용자 원문")))
        .isInstanceOf(TripException.class);
    assertThatThrownBy(() -> new TripPlannerConditions(List.of(anchor), List.of("cafe", "cafe")))
        .isInstanceOf(TripException.class);
  }

  @Test
  void 일반_여행은_최대30일을_저장하고_5일_AI제한과_분리한다() {
    var anchors = new ArrayList<TripPlannerConditions.DayAnchor>();
    for (int i = 1; i <= 30; i++) {
      anchors.add(new TripPlannerConditions.DayAnchor(new UUID(53, i), PLACE));
    }
    assertThat(new TripPlannerConditions(anchors, List.of()).dayAnchors()).hasSize(30);
    anchors.add(new TripPlannerConditions.DayAnchor(new UUID(53, 31), PLACE));
    assertThatThrownBy(() -> new TripPlannerConditions(anchors, List.of()))
        .isInstanceOf(TripException.class);
  }

  @Test
  void 미입력은_빈목록이고_null_ID는_허용하지_않는다() {
    assertThat(TripPlannerConditions.empty().dayAnchors()).isEmpty();
    assertThatThrownBy(() -> new TripPlannerConditions.DayAnchor(DAY, null))
        .isInstanceOf(TripException.class);
  }
}
