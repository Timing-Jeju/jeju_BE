package com.timingjeju.api.domain.places.repository;

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
