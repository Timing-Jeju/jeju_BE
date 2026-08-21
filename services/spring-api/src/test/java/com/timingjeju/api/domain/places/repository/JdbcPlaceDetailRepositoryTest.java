package com.timingjeju.api.domain.places.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.places.exception.PlaceDetailUnavailableException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@Tag("unit")
class JdbcPlaceDetailRepositoryTest {

  @Test
  void 공개상세_SQL은_effective_active_place와_ordered_active_image_20개를_DB에서_제한한다() {
    assertThat(JdbcPublicPlaceDetailRepository.SELECT)
        .contains("p.source_deleted_at is null")
        .contains("p.tombstoned_at is null")
        .contains("p.stale = false")
        .contains("p.stale_at is null or p.stale_at > now()")
        .contains("left join lateral")
        .contains("candidate.tombstoned_at is null")
        .contains("order by candidate.display_order asc nulls last, candidate.id asc")
        .contains("limit 20");
  }

  @Test
  void DB_read_failure만_raw_SQL과_cause없는_typed_failure로_변환한다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenThrow(new DataAccessResourceFailureException("select password from secret_table"));
    JdbcPublicPlaceDetailRepository repository = new JdbcPublicPlaceDetailRepository(jdbc);

    assertThatThrownBy(
            () ->
                repository.find(
                    UUID.fromString("20000000-0000-0000-0000-000000000002"), Optional.empty()))
        .isExactlyInstanceOf(PlaceDetailUnavailableException.class)
        .hasNoCause()
        .hasMessage(null);
  }
}
