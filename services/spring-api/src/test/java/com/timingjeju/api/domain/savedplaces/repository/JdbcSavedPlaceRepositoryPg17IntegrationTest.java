package com.timingjeju.api.domain.savedplaces.repository;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "timing-jeju.test.postgis-image=postgis/postgis:17-3.5")
class JdbcSavedPlaceRepositoryPg17IntegrationTest extends JdbcSavedPlaceRepositoryIntegrationTest {
  @Override
  protected int expectedPostgresMajor() {
    return 17;
  }
}
