package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripPreferencePolicyTest {
  private static final UUID PLACE = UUID.fromString("46000000-0000-0000-0000-000000000001");

  @Test
  void one_two_three_modes와_nullable_places를_허용하고_region을_trim_NFC_정규화한다() {
    for (int count = 1; count <= 3; count++) {
      ReplaceTripPreferencesCommand canonical =
          TripPreferencePolicy.canonicalizeAndValidate(command(modes().subList(0, count)));

      assertThat(canonical.transportModes()).hasSize(count);
      assertThat(canonical.arrivalRegionCode()).isEqualTo("제주시");
      assertThat(canonical.departureRegionCode()).isEqualTo("jeju-si");
      assertThat(canonical.preferredRegionCodes()).containsExactly("성산", "aewol");
      assertThat(canonical.startPlaceId()).isEqualTo(PLACE);
      assertThat(canonical.endPlaceId()).isNull();
    }
  }

  @Test
  void region_trim은_ASCII_여섯공백만제거하고_그밖의_C0제어문자는보존한다() {
    ReplaceTripPreferencesCommand base = command(modes().subList(0, 1));
    String trim = " \t\n\r\f\u000B";
    String preserved = "\u0001jeju-si\b";

    ReplaceTripPreferencesCommand canonical =
        TripPreferencePolicy.canonicalizeAndValidate(
            new ReplaceTripPreferencesCommand(
                base.preferredCategories(),
                trim + "\u110C\u1166\u110C\u116E\u1109\u1175" + trim,
                preserved,
                List.of(trim + "aewol" + trim, preserved),
                null,
                null,
                base.transportModes()));

    assertThat(canonical.arrivalRegionCode()).isEqualTo("제주시");
    assertThat(canonical.departureRegionCode()).isEqualTo(preserved);
    assertThat(canonical.preferredRegionCodes()).containsExactly("aewol", preserved);

    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            "\0jeju-si",
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            null,
            null,
            base.transportModes()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            List.of("aewol\0"),
            null,
            null,
            base.transportModes()));
  }

  @Test
  void category와_region_중복은_PREFERENCE_CONSTRAINT_VIOLATION이다() {
    ReplaceTripPreferencesCommand base = command(modes().subList(0, 1));
    assertConstraint(
        new ReplaceTripPreferencesCommand(
            List.of("cafe", "cafe"),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            base.startPlaceId(),
            base.endPlaceId(),
            base.transportModes()));
    assertConstraint(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            List.of("seongsan", "seongsan"),
            base.startPlaceId(),
            base.endPlaceId(),
            base.transportModes()));
  }

  @Test
  void mode_중복_priority_비연속_primary_불일치와_walk를_거부한다() {
    assertConstraint(
        command(
            List.of(
                new TripTransportMode("public_transit", 1, true),
                new TripTransportMode("public_transit", 2, false))));
    assertConstraint(
        command(
            List.of(
                new TripTransportMode("public_transit", 1, true),
                new TripTransportMode("taxi", 3, false))));
    assertConstraint(
        command(
            List.of(
                new TripTransportMode("public_transit", 1, false),
                new TripTransportMode("taxi", 2, true))));
    assertConstraint(command(List.of(new TripTransportMode("walk", 1, true))));
  }

  @Test
  void 빈_mode_또는_계약_상한을_넘는_배열과_공백_region은_INVALID_REQUEST다() {
    ReplaceTripPreferencesCommand base = command(modes().subList(0, 1));
    assertInvalid(command(List.of()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            "   ",
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            base.startPlaceId(),
            base.endPlaceId(),
            base.transportModes()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            List.of(
                "tourist_attraction",
                "cultural_facility",
                "festival",
                "travel_course",
                "leisure",
                "restaurant",
                "cafe",
                "shopping",
                "extra"),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            base.startPlaceId(),
            base.endPlaceId(),
            base.transportModes()));
  }

  @Test
  void unknown_category는_constraint이고_null_element와_50_codepoint초과_region은_INVALID_REQUEST다() {
    ReplaceTripPreferencesCommand base = command(modes().subList(0, 1));
    assertConstraint(
        new ReplaceTripPreferencesCommand(
            List.of("unknown"),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            null,
            null,
            base.transportModes()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            "가".repeat(51),
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            null,
            null,
            base.transportModes()));
    List<String> withNull = new ArrayList<>();
    withNull.add(null);
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            withNull,
            null,
            null,
            base.transportModes()));
  }

  @Test
  void null_lists_null_mode_regions20초과_modes3초과는_INVALID_REQUEST다() {
    ReplaceTripPreferencesCommand base = command(modes().subList(0, 1));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            null,
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            null,
            null,
            base.transportModes()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            null,
            null,
            null,
            base.transportModes()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            null,
            null,
            null));
    List<TripTransportMode> nullMode = new ArrayList<>();
    nullMode.add(null);
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            null,
            null,
            nullMode));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            java.util.stream.IntStream.range(0, 21).mapToObj(index -> "r" + index).toList(),
            null,
            null,
            base.transportModes()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            base.preferredRegionCodes(),
            null,
            null,
            List.of(
                new TripTransportMode("public_transit", 1, true),
                new TripTransportMode("rental_car", 2, false),
                new TripTransportMode("taxi", 3, false),
                new TripTransportMode("public_transit", 4, false))));
  }

  @Test
  void trim_NFC후_같아지는_region은_PREFERENCE_CONSTRAINT_VIOLATION이다() {
    ReplaceTripPreferencesCommand base = command(modes().subList(0, 1));
    assertConstraint(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            base.arrivalRegionCode(),
            base.departureRegionCode(),
            List.of("제주시", "  \u110C\u1166\u110C\u116E\u1109\u1175  "),
            null,
            null,
            base.transportModes()));
  }

  @Test
  void 정확히_50_codepoint와_빈_category_region배열은_허용한다() {
    ReplaceTripPreferencesCommand canonical =
        TripPreferencePolicy.canonicalizeAndValidate(
            new ReplaceTripPreferencesCommand(
                List.of(),
                "가".repeat(50),
                "나".repeat(50),
                List.of(),
                null,
                null,
                modes().subList(0, 1)));

    assertThat(canonical.arrivalRegionCode()).hasSize(50);
    assertThat(canonical.preferredCategories()).isEmpty();
    assertThat(canonical.preferredRegionCodes()).isEmpty();
  }

  @Test
  void supplementary_unicode는_arrival_departure와_preferredRegion각각_50_codepoint허용_51거부한다() {
    String fifty = "😀".repeat(50);
    String fiftyOne = "😀".repeat(51);
    ReplaceTripPreferencesCommand base = command(modes().subList(0, 1));
    ReplaceTripPreferencesCommand canonical =
        TripPreferencePolicy.canonicalizeAndValidate(
            new ReplaceTripPreferencesCommand(
                base.preferredCategories(),
                fifty,
                fifty,
                List.of(fifty),
                null,
                null,
                base.transportModes()));

    assertThat(
            canonical.arrivalRegionCode().codePointCount(0, canonical.arrivalRegionCode().length()))
        .isEqualTo(50);
    assertThat(
            canonical
                .departureRegionCode()
                .codePointCount(0, canonical.departureRegionCode().length()))
        .isEqualTo(50);
    assertThat(
            canonical
                .preferredRegionCodes()
                .getFirst()
                .codePointCount(0, canonical.preferredRegionCodes().getFirst().length()))
        .isEqualTo(50);

    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            fiftyOne,
            fifty,
            List.of(fifty),
            null,
            null,
            base.transportModes()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            fifty,
            fiftyOne,
            List.of(fifty),
            null,
            null,
            base.transportModes()));
    assertInvalid(
        new ReplaceTripPreferencesCommand(
            base.preferredCategories(),
            fifty,
            fifty,
            List.of(fiftyOne),
            null,
            null,
            base.transportModes()));
  }

  @Test
  void priority는_list순서대로_1부터_연속이고_primary는_오직_priority1이어야한다() {
    assertConstraint(
        command(
            List.of(
                new TripTransportMode("taxi", 2, false),
                new TripTransportMode("public_transit", 1, true))));
    assertConstraint(
        command(
            List.of(
                new TripTransportMode("taxi", 1, true),
                new TripTransportMode("rental_car", 2, true))));
    assertConstraint(
        command(
            List.of(
                new TripTransportMode("taxi", 1, false),
                new TripTransportMode("rental_car", 2, false))));
  }

  @Test
  void command와_canonical_result는_입력_list의_후속변경에_영향받지않는다() {
    List<String> categories = new ArrayList<>(List.of("cafe"));
    List<String> regions = new ArrayList<>(List.of("aewol"));
    List<TripTransportMode> transport =
        new ArrayList<>(List.of(new TripTransportMode("taxi", 1, true)));
    ReplaceTripPreferencesCommand command =
        new ReplaceTripPreferencesCommand(
            categories, "jeju-si", "seogwipo-si", regions, null, null, transport);
    ReplaceTripPreferencesCommand canonical = TripPreferencePolicy.canonicalizeAndValidate(command);

    categories.clear();
    regions.clear();
    transport.clear();

    assertThat(command.preferredCategories()).containsExactly("cafe");
    assertThat(canonical.preferredRegionCodes()).containsExactly("aewol");
    assertThat(canonical.transportModes()).hasSize(1);
    assertThatThrownBy(() -> canonical.preferredCategories().add("shopping"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static ReplaceTripPreferencesCommand command(List<TripTransportMode> transportModes) {
    return new ReplaceTripPreferencesCommand(
        List.of("tourist_attraction", "cafe"),
        "  \u110C\u1166\u110C\u116E\u1109\u1175  ",
        "jeju-si",
        List.of(" \u1109\u1165\u11BC\u1109\u1161\u11AB ", " aewol "),
        PLACE,
        null,
        transportModes);
  }

  private static List<TripTransportMode> modes() {
    return List.of(
        new TripTransportMode("public_transit", 1, true),
        new TripTransportMode("rental_car", 2, false),
        new TripTransportMode("taxi", 3, false));
  }

  private static void assertConstraint(ReplaceTripPreferencesCommand command) {
    assertCode(command, "PREFERENCE_CONSTRAINT_VIOLATION");
  }

  private static void assertInvalid(ReplaceTripPreferencesCommand command) {
    assertCode(command, "INVALID_REQUEST");
  }

  private static void assertCode(ReplaceTripPreferencesCommand command, String code) {
    assertThatThrownBy(() -> TripPreferencePolicy.canonicalizeAndValidate(command))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo(code);
  }
}
