package com.timingjeju.api.documentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("slice")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes"
    })
@AutoConfigureMockMvc
class PlacesOpenApiIntegrationTest {

  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void places는_optional_bearer와_닫힌_DTO_size100_Problem_Details를_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/places'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.security.length()").value(2))
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.security[0]").isEmpty())
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.security[1].bearerAuth").isArray())
        .andExpect(
            jsonPath("$.paths['/api/v1/places'].get.parameters[?(@.name=='size')].schema.maximum")
                .value(100))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/places'].get.parameters[?(@.name=='query')].schema.minLength")
                .value(1))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/places'].get.parameters[?(@.name=='query')].schema.maxLength")
                .value(100))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/places'].get.parameters[?(@.name=='category')].schema.pattern")
                .value("^(?:[A-Z]{2}|content-type:[0-9]{1,10})$"))
        .andExpect(
            jsonPath("$.components.schemas.PlaceListItem.properties.category.pattern")
                .value("^(?:[A-Z]{2}|content-type:[0-9]{1,10})$"))
        .andExpect(
            jsonPath("$.paths['/api/v1/places'].get.parameters[?(@.name=='lat')].required")
                .value(false))
        .andExpect(
            jsonPath("$.paths['/api/v1/places'].get.parameters[?(@.name=='lat')].schema.minimum")
                .value(33.0))
        .andExpect(
            jsonPath("$.paths['/api/v1/places'].get.parameters[?(@.name=='lat')].schema.maximum")
                .value(34.0))
        .andExpect(
            jsonPath("$.paths['/api/v1/places'].get.parameters[?(@.name=='lng')].required")
                .value(false))
        .andExpect(
            jsonPath("$.paths['/api/v1/places'].get.parameters[?(@.name=='lng')].schema.minimum")
                .value(126.0))
        .andExpect(
            jsonPath("$.paths['/api/v1/places'].get.parameters[?(@.name=='lng')].schema.maximum")
                .value(127.0))
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.responses['400']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.responses['401']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.responses['403']").doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.PlacesListResponse.additionalProperties").value(false))
        .andExpect(jsonPath("$.components.schemas.PlaceListItem.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.PlaceDataFreshness.additionalProperties").value(false));
  }

  @Test
  void place_detail은_canonical_UUID_optional_bearer_닫힌_DTO와_오류를_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/places/{placeId}'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/places/{placeId}'].get.security.length()").value(2))
        .andExpect(jsonPath("$.paths['/api/v1/places/{placeId}'].get.security[0]").isEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/places/{placeId}'].get.security[1].bearerAuth").isArray())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/places/{placeId}'].get.parameters[?(@.name=='placeId')].required")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/places/{placeId}'].get.parameters[?(@.name=='placeId')].schema.pattern")
                .value("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        .andExpect(jsonPath("$.paths['/api/v1/places/{placeId}'].get.responses['400']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/places/{placeId}'].get.responses['401']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/places/{placeId}'].get.responses['404']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/places/{placeId}'].get.responses['503']").exists())
        .andExpect(
            jsonPath("$.components.schemas.PlaceDetailResponse.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.SavedPlaceState.additionalProperties").value(false))
        .andExpect(jsonPath("$.components.schemas.Contact.additionalProperties").value(false))
        .andExpect(jsonPath("$.components.schemas.Operations.additionalProperties").value(false))
        .andExpect(jsonPath("$.components.schemas.PlaceImage.additionalProperties").value(false))
        .andExpect(jsonPath("$.components.schemas.NearbyStop.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.NearbyStop.properties.provider.minLength").value(1))
        .andExpect(
            jsonPath("$.components.schemas.NearbyStop.properties.provider.maxLength").value(128))
        .andExpect(
            jsonPath("$.components.schemas.NearbyStop.properties.linkMethod.enum")
                .value(
                    org.hamcrest.Matchers.contains(
                        "spatial_radius", "fixture", "manual", "api_nearby")))
        .andExpect(
            jsonPath("$.components.schemas.PlaceDetailResponse.properties.nearbyStops.maxItems")
                .value(5))
        .andExpect(
            jsonPath("$.components.schemas.PlaceDetailResponse.properties.images.maxItems")
                .value(20))
        .andExpect(
            jsonPath(
                    "$.components.schemas.PlaceDetailResponse.properties.operationsSummary.maxLength")
                .value(1000))
        .andExpect(jsonPath("$.components.schemas.Contact.properties.phone.maxLength").value(1000))
        .andExpect(
            jsonPath("$.components.schemas.Operations.properties.operatingHoursText.maxLength")
                .value(1000))
        .andExpect(
            jsonPath("$.components.schemas.Operations.properties.closedDaysText.maxLength")
                .value(1000))
        .andExpect(
            jsonPath("$.components.schemas.Operations.properties.parkingText.maxLength")
                .value(1000))
        .andExpect(
            jsonPath("$.components.schemas.Operations.properties.admissionFeeText.maxLength")
                .value(1000))
        .andExpect(
            jsonPath("$.components.schemas.PlaceDetailResponse.required")
                .value(
                    org.hamcrest.Matchers.hasItems(
                        "saved", "contact", "operations", "images", "nearbyStops")));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
