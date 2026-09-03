package com.timingjeju.api.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PersistedSnapshotProviderCatalogTest {

  @Test
  void 영속_snapshot_공급자는_TourAPI_TAGO_KMA로_고정하고_TMAP은_제외한다() {
    assertThat(PersistedSnapshotProviderCatalog.providers())
        .containsExactly("TAGO", "kma", "tour-api");
    assertThat(PersistedSnapshotProviderCatalog.allows("TAGO")).isTrue();
    assertThat(PersistedSnapshotProviderCatalog.allows("kma")).isTrue();
    assertThat(PersistedSnapshotProviderCatalog.allows("tour-api")).isTrue();
    assertThat(PersistedSnapshotProviderCatalog.allows("TMAP")).isFalse();
    assertThat(PersistedSnapshotProviderCatalog.allows("tmap")).isFalse();
  }

  @Test
  void 영속_snapshot_공급자_목록은_호출자가_변경할_수_없다() {
    assertThatThrownBy(() -> PersistedSnapshotProviderCatalog.providers().add("TMAP"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
