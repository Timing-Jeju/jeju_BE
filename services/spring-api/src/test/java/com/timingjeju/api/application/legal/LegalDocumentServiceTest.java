package com.timingjeju.api.application.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.legal.service.LegalDocumentService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class LegalDocumentServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

  @Test
  void read는_locale우선_effective_semver_documentId순으로_type별_최신하나를_선택한다() {
    UUID winner = UUID.fromString("19000000-0000-0000-0000-000000000001");
    List<LegalDocument> candidates =
        List.of(
            document(
                "29000000-0000-0000-0000-000000000001",
                "terms",
                "ko-KR",
                "9.0.0",
                NOW.minusSeconds(2)),
            document(
                "39000000-0000-0000-0000-000000000001",
                "terms",
                "ko-KR",
                "1.9.0",
                NOW.minusSeconds(1)),
            document(winner.toString(), "terms", "ko-KR", "1.10.0", NOW.minusSeconds(1)),
            document("49000000-0000-0000-0000-000000000001", "terms", "en-US", "99.0.0", NOW),
            document(
                "59000000-0000-0000-0000-000000000001",
                "privacy",
                "ko-KR",
                "1.0.0",
                NOW.minusSeconds(1)));
    LegalDocumentService service =
        new LegalDocumentService((locale, at) -> candidates, fixedClock());

    LegalDocumentCatalog result = service.read("ko-KR");

    assertThat(result.evaluatedAt()).isEqualTo(NOW);
    assertThat(result.items())
        .extracting(LegalDocument::documentId)
        .containsExactly(UUID.fromString("59000000-0000-0000-0000-000000000001"), winner);
  }

  @Test
  void read는_locale생략을_koKR로_정규화하고_미지원_locale을_거부한다() {
    LegalDocumentService service =
        new LegalDocumentService((locale, at) -> List.of(), fixedClock());

    assertThat(service.read(null).locale()).isEqualTo("ko-KR");
    assertThatThrownBy(() -> service.read("ko-kr"))
        .isInstanceOf(LegalProfileException.class)
        .extracting("code")
        .isEqualTo("INVALID_PROFILE_LEGAL_REQUEST");
  }

  @Test
  void location_version은_73계약의_2026_08_11_v1을_semantic하게_비교한다() {
    assertThat(LegalDocumentSelection.compareVersion("2026-08-11.v10", "2026-08-11.v2"))
        .isPositive();
  }

  private static LegalDocument document(
      String id, String type, String locale, String version, Instant effectiveAt) {
    return new LegalDocument(
        UUID.fromString(id),
        type,
        locale,
        version,
        type,
        "https://example.invalid/" + type,
        true,
        effectiveAt);
  }

  private static Clock fixedClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }
}
