package com.timingjeju.api.domain.trip.adapter;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "timing-jeju.test.postgis-image=postgis/postgis:17-3.5")
class JdbcTripDetailProjectionPg17IntegrationTest extends JdbcTripDetailProjectionIntegrationTest {
  @Override
  protected int expectedPostgresMajor() {
    return 17;
  }
}
