package com.timingjeju.api.global.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.timingjeju.api.domain.trip.exception.TripPlacePreferencesProblemDefinitions;
import com.timingjeju.api.domain.trip.exception.TripPreferencesProblemDefinitions;
import com.timingjeju.api.global.error.ProblemCodeRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Tests projection under explicitly ready fixtures; never changes release catalog readiness. */
public abstract class ReadyCanonicalOpenApiTest {
  @Autowired private JsonMapper mapper;
  @Autowired private ProblemCodeRegistry problems;
  @Autowired private TripPlacePreferencesProblemDefinitions placePreferences;
  @Autowired private TripPreferencesProblemDefinitions preferences;
  @MockitoBean private FrontendOpenApiCustomizer customizer;

  @BeforeEach
  void 테스트에서만_구현완료_계약의_투영을_검증한다() {
    var actual =
        new FrontendOpenApiCustomizer(
            mapper, problems, placePreferences, preferences, this::resource);
    doAnswer(
            invocation -> {
              actual.customise(invocation.getArgument(0));
              return null;
            })
        .when(customizer)
        .customise(any());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> resource(String path) {
    try (var stream = getClass().getResourceAsStream(path)) {
      Map<String, Object> resource = mapper.readValue(stream, Map.class);
      if (path.equals("/rest/catalog.json")) {
        for (var row : (List<Map<String, Object>>) resource.get("domainContracts")) {
          ((Map<String, Object>) row.get("readiness"))
              .put(
                  "implementation",
                  Map.of(
                      "status",
                      "ready",
                      "evidence",
                      Map.of("testFixture", "ready-projection-only")));
        }
      }
      return resource;
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
