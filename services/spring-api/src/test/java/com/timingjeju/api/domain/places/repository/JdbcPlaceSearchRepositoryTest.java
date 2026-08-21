package com.timingjeju.api.domain.places.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.places.dto.request.PlacesListQuery;
import com.timingjeju.api.domain.places.exception.PlaceSearchUnavailableException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcPlaceSearchRepositoryTest {

  @Test
  void DB_read_failure만_message와_cause없는_typed_failure로_변환한다() {
    NamedParameterJdbcTemplate jdbc = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenThrow(new DataAccessResourceFailureException("select password from secret_table"));
    JdbcPlaceSearchRepository repository = new JdbcPlaceSearchRepository(jdbc);

    assertThatThrownBy(
            () ->
                repository.search(
                    PlacesListQuery.of(null, "VE", null, null, null, null, null, 20, false),
                    null,
                    Optional.empty()))
        .isExactlyInstanceOf(PlaceSearchUnavailableException.class)
        .hasNoCause()
        .hasMessage(null);
  }
}
