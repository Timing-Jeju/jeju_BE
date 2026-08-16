package com.timingjeju.api.application.staypolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class StayPolicyImportServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");
  private static final UUID PLACE = UUID.fromString("65000000-0000-0000-0000-000000000001");

  @Test
  void 전체_payload를_검증한_뒤_행순서와_무관한_hash로_publish한다() {
    RecordingStore store = new RecordingStore(Set.of("VE"), Set.of(PLACE));
    StayPolicyImportService service = service(store);
    StayPolicyPayload first =
        payload(
            List.of(
                StayPolicyCandidate.categoryDefault("VE", 90),
                StayPolicyCandidate.placeOverride(PLACE, 120)));
    StayPolicyPayload reordered =
        payload(
            List.of(
                StayPolicyCandidate.placeOverride(PLACE, 120),
                StayPolicyCandidate.categoryDefault("VE", 90)));

    StayPolicyImportResult firstResult = service.importPolicy(first, false);
    StayPolicyImportResult reorderedResult = service.importPolicy(reordered, true);

    assertThat(firstResult.payloadHash()).isEqualTo(reorderedResult.payloadHash());
    assertThat(firstResult.importedPolicyCount()).isEqualTo(2);
    assertThat(store.publishCalls).isEqualTo(1);
  }

  @Test
  void future_effectiveAt과_범위밖_minutes와_duplicate_target은_publish전에_전체거부한다() {
    RecordingStore store = new RecordingStore(Set.of("VE"), Set.of(PLACE));
    StayPolicyImportService service = service(store);
    StayPolicyPayload invalid =
        new StayPolicyPayload(
            "stay-2026-08-23",
            NOW.plusSeconds(1),
            null,
            List.of(
                StayPolicyCandidate.categoryDefault("VE", 0),
                StayPolicyCandidate.categoryDefault("VE", 90)));

    assertThatThrownBy(() -> service.importPolicy(invalid, false))
        .isInstanceOf(StayPolicyValidationException.class)
        .hasMessageContaining("effectiveAt")
        .hasMessageContaining("minutes")
        .hasMessageContaining("duplicate");
    assertThat(store.publishCalls).isZero();
  }

  @Test
  void effectiveAt_now는_허용하고_dryRun은_DB_target을_검증하되_write하지_않는다() {
    RecordingStore store = new RecordingStore(Set.of("VE"), Set.of(PLACE));
    StayPolicyImportService service = service(store);

    StayPolicyImportResult result =
        service.importPolicy(payload(List.of(StayPolicyCandidate.categoryDefault("VE", 90))), true);

    assertThat(result.dryRun()).isTrue();
    assertThat(store.validatedCategories).containsExactly("VE");
    assertThat(store.publishCalls).isZero();
  }

  @Test
  void canonical_category가_없거나_place가_missing_stale_tombstoned이면_전체거부한다() {
    RecordingStore store = new RecordingStore(Set.of(), Set.of());
    StayPolicyImportService service = service(store);

    assertThatThrownBy(
            () ->
                service.importPolicy(
                    payload(
                        List.of(
                            StayPolicyCandidate.categoryDefault("UNKNOWN", 90),
                            StayPolicyCandidate.placeOverride(PLACE, 120))),
                    false))
        .isInstanceOf(StayPolicyValidationException.class)
        .hasMessageContaining("UNKNOWN")
        .hasMessageContaining(PLACE.toString());
    assertThat(store.publishCalls).isZero();
  }

  @Test
  void unicode_NFC로_정규화한_target_duplicate를_거부한다() {
    RecordingStore store = new RecordingStore(Set.of("Å"), Set.of());
    StayPolicyImportService service = service(store);

    assertThatThrownBy(
            () ->
                service.importPolicy(
                    payload(
                        List.of(
                            StayPolicyCandidate.categoryDefault("A\u030A", 90),
                            StayPolicyCandidate.categoryDefault("Å", 120))),
                    false))
        .isInstanceOf(StayPolicyValidationException.class)
        .hasMessageContaining("duplicate policy target: category:Å");
    assertThat(store.publishCalls).isZero();
  }

  @Test
  void NFKC_only_호환_category는_ASCII_target으로_축약하지_않고_canonical_syntax로_거부한다() {
    RecordingStore store = new RecordingStore(Set.of("VE"), Set.of());
    StayPolicyImportService service = service(store);

    assertThatThrownBy(
            () ->
                service.importPolicy(
                    payload(
                        List.of(
                            StayPolicyCandidate.categoryDefault("ＶＥ", 90),
                            StayPolicyCandidate.categoryDefault("VE", 120))),
                    false))
        .isInstanceOf(StayPolicyValidationException.class)
        .hasMessageContaining("category must be a canonical code")
        .hasMessageNotContaining("duplicate policy target: category:VE");
    assertThat(store.publishCalls).isZero();
  }

  @Test
  void hash는_NFC_canonical_equivalence만_동일하게_취급한다() {
    StayPolicyPayloadHasher hasher = new StayPolicyPayloadHasher();

    String composed = hasher.hash(payload(List.of(StayPolicyCandidate.categoryDefault("Å", 90))));
    String decomposed =
        hasher.hash(payload(List.of(StayPolicyCandidate.categoryDefault("A\u030A", 90))));
    String compatibility =
        hasher.hash(payload(List.of(StayPolicyCandidate.categoryDefault("Ａ", 90))));
    String ascii = hasher.hash(payload(List.of(StayPolicyCandidate.categoryDefault("A", 90))));

    assertThat(decomposed).isEqualTo(composed);
    assertThat(compatibility).isNotEqualTo(ascii);
  }

  private static StayPolicyImportService service(RecordingStore store) {
    return new StayPolicyImportService(store, store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static StayPolicyPayload payload(List<StayPolicyCandidate> policies) {
    return new StayPolicyPayload("stay-2026-08-23", NOW, null, policies);
  }

  private static final class RecordingStore
      implements StayPolicyTargetCatalog, StayPolicyPublicationStore {
    private final Set<String> liveCategories;
    private final Set<UUID> livePlaces;
    private Set<String> validatedCategories = Set.of();
    private int publishCalls;

    private RecordingStore(Set<String> liveCategories, Set<UUID> livePlaces) {
      this.liveCategories = liveCategories;
      this.livePlaces = livePlaces;
    }

    @Override
    public StayPolicyTargetValidation validateTargets(Set<String> categories, Set<UUID> placeIds) {
      validatedCategories = new HashSet<>(categories);
      return new StayPolicyTargetValidation(
          intersection(categories, liveCategories), intersection(placeIds, livePlaces));
    }

    @Override
    public void publish(ValidatedStayPolicyPayload payload, Instant importedAt) {
      publishCalls++;
    }

    private static <T> Set<T> intersection(Set<T> requested, Set<T> available) {
      Set<T> result = new HashSet<>(requested);
      result.retainAll(available);
      return result;
    }
  }
}
