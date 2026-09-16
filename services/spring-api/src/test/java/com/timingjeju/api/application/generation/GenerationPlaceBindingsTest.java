package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GenerationPlaceBindingsTest {
  private static final UUID PLACE = new UUID(79, 1);

  @Test
  void 공항_출처는_TourAPI로_위조하지_않고_승인된_CJU만_연결한다() {
    var binding =
        new GenerationPlaceBindings(
            List.of(new GenerationPlaceBindings.Place(PLACE, "CJU", "제주국제공항", "kac.airport")));
    assertThat(binding.factId(PLACE)).isEqualTo("kac.airport:CJU");
    assertThat(binding.canonicalId("kac.airport:CJU")).isEqualTo(PLACE);
    assertThatThrownBy(() -> new GenerationPlaceBindings.Place(PLACE, "GMP", "김포", "kac.airport"))
        .isInstanceOf(GenerationException.class);
    assertThatThrownBy(() -> new GenerationPlaceBindings.Place(PLACE, "CJU", "제주", "unknown"))
        .isInstanceOf(GenerationException.class);
  }

  @Test
  void 승인된_TourAPI_contentId로_양방향_식별자를_연결하고_UUID를_AI에_노출하지_않는다() {
    var bindings =
        new GenerationPlaceBindings(
            List.of(new GenerationPlaceBindings.Place(PLACE, "126471", "제주국제공항")));
    assertThat(bindings.factId(PLACE)).isEqualTo("tourapi.place:126471");
    assertThat(bindings.canonicalId("tourapi.place:126471")).isEqualTo(PLACE);
    assertThat(bindings.reference(PLACE)).containsOnlyKeys("place_id");
    assertThat(bindings.accommodation(PLACE))
        .containsEntry("place_id", "tourapi.place:126471")
        .containsEntry("name", "제주국제공항")
        .hasSize(2);
  }

  @Test
  void 미지_ID는_이름이나_UUID로_추정하지_않는다() {
    var bindings = new GenerationPlaceBindings(List.of());
    assertThatThrownBy(() -> bindings.factId(PLACE)).hasMessage("GENERATION_INPUT_UNAVAILABLE");
    assertThatThrownBy(() -> bindings.canonicalId("tourapi.place:126471"))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
  }

  @Test
  void 중복_canonical과_중복_source_ID는_조용히_덮어쓰지_않는다() {
    var place = new GenerationPlaceBindings.Place(PLACE, "126471", "제주국제공항");
    assertThatThrownBy(() -> new GenerationPlaceBindings(List.of(place, place)))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                new GenerationPlaceBindings(
                    List.of(
                        place,
                        new GenerationPlaceBindings.Place(new UUID(79, 2), "126471", "다른 이름"))))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
  }

  @Test
  void 비정상_source_ID와_빈_공식_이름은_거부한다() {
    for (var id : List.of("", "126471/geometry", PLACE.toString(), " 126471")) {
      assertThatThrownBy(() -> new GenerationPlaceBindings.Place(PLACE, id, "공식 장소"))
          .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    }
    assertThatThrownBy(() -> new GenerationPlaceBindings.Place(PLACE, "126471", " "))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
  }
}
