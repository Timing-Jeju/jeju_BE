package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageOrphanScanCursorStore;
import com.timingjeju.api.application.profile.ProfileImageScanCursor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProfileImageOrphanScanCursorStore implements ProfileImageOrphanScanCursorStore {

  static final String SCANNER = "profile-images-orphan-scan";
  private static final String LOAD_SQL =
      """
      select owner_offset, object_offset, revision
      from public.profile_image_orphan_scan_cursor
      where scanner_name = ?
      """;
  private static final String ADVANCE_SQL =
      """
      update public.profile_image_orphan_scan_cursor
      set owner_offset = ?, object_offset = ?, revision = ?, updated_at = now()
      where scanner_name = ?
        and owner_offset = ? and object_offset = ? and revision = ?
      """;
  private final JdbcTemplate jdbc;

  public JdbcProfileImageOrphanScanCursorStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc);
  }

  @Override
  public ProfileImageScanCursor load() {
    try {
      var rows =
          jdbc.query(
              LOAD_SQL,
              (resultSet, rowNumber) ->
                  new ProfileImageScanCursor(
                      resultSet.getInt("owner_offset"),
                      resultSet.getInt("object_offset"),
                      resultSet.getLong("revision")),
              SCANNER);
      if (rows.size() != 1) {
        throw ProfileImageException.storageUnavailable();
      }
      return rows.getFirst();
    } catch (DataAccessException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  @Override
  public boolean advance(ProfileImageScanCursor expected, ProfileImageScanCursor next) {
    try {
      return jdbc.update(
              ADVANCE_SQL,
              next.ownerOffset(),
              next.objectOffset(),
              next.revision(),
              SCANNER,
              expected.ownerOffset(),
              expected.objectOffset(),
              expected.revision())
          == 1;
    } catch (DataAccessException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  static String contractSql() {
    return LOAD_SQL + "\n" + ADVANCE_SQL;
  }
}
