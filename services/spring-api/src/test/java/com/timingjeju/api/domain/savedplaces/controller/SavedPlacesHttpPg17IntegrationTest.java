package com.timingjeju.api.domain.savedplaces.controller;

@org.springframework.test.context.TestPropertySource(
    properties = "timing-jeju.test.postgis-image=postgis/postgis:17-3.5")
class SavedPlacesHttpPg17IntegrationTest extends SavedPlacesHttpPostgreSqlIntegrationTest {
  @Override
  protected int expectedPostgresMajor() {
    return 17;
  }
}
