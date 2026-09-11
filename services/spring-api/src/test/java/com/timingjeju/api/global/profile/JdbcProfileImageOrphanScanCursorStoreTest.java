package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JdbcProfileImageOrphanScanCursorStoreTest {

  @Test
  void durable_cursor는_app_owned_table에서_offsets와_revision을_CAS한다() {
    String sql = JdbcProfileImageOrphanScanCursorStore.contractSql().toLowerCase();

    assertThat(sql)
        .contains(
            "from public.profile_image_orphan_scan_cursor",
            "set owner_offset = ?, object_offset = ?, revision = ?",
            "and owner_offset = ? and object_offset = ? and revision = ?")
        .doesNotContain("storage.objects", "storage.buckets");
  }
}
