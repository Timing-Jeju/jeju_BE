package com.timingjeju.api.domain.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.demo.DemoImportResult;
import com.timingjeju.api.application.demo.DemoPlaceDetailItemRow;
import com.timingjeju.api.application.demo.DemoPlaceDetailRow;
import com.timingjeju.api.application.demo.DemoPlaceImageRow;
import com.timingjeju.api.application.demo.DemoPlaceRow;
import com.timingjeju.api.application.demo.DemoProvenanceRow;
import com.timingjeju.api.application.demo.DemoRunRow;
import com.timingjeju.api.application.demo.DemoSnapshotRow;
import com.timingjeju.api.application.demo.DemoStorageView;
import com.timingjeju.api.domain.demo.service.DemoImportService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class DemoImportControllerTest {

  @Test
  void tour_api_수집_엔드포인트는_import_결과를_반환한다() throws Exception {
    DemoImportService service = mock(DemoImportService.class);
    UUID runId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(service.importTourPlaces()).thenReturn(new DemoImportResult(runId, 2, 5, 3, 1, 0, false));

    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DemoImportController(service)).build();

    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/demo/imports/tour-api")
                    .contentType(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains(runId.toString());
    assertThat(body).contains("\"pageCount\":2");
    assertThat(body).contains("\"inserted\":5");
    assertThat(body).doesNotContain("secret", "token");
    verify(service).importTourPlaces();
  }

  @Test
  void storage_요약_엔드포인트는_최근저장_요약을_반환한다() throws Exception {
    DemoImportService service = mock(DemoImportService.class);
    DemoStorageView view =
        new DemoStorageView(
            List.of(
                new DemoRunRow(
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "tour_api",
                    "areaBasedList2",
                    "succeeded",
                    4,
                    4,
                    Instant.EPOCH)),
            List.of(
                new DemoSnapshotRow(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "areaBasedList2",
                    "parsed",
                    1024L)),
            List.of(
                new DemoPlaceRow(
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "10001",
                    "12",
                    "성산일출봉",
                    "관광지",
                    "제주",
                    null,
                    null,
                    null,
                    126.0,
                    33.0)),
            List.of(
                new DemoPlaceDetailRow(
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "02-1234",
                    "09:00-18:00",
                    null,
                    "주차가능",
                    "{\"source\":\"seed\"}",
                    UUID.randomUUID())),
            List.of(
                new DemoPlaceDetailItemRow(
                    UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    "12",
                    "info",
                    "item-01",
                    1,
                    "소개",
                    UUID.fromString("22222222-2222-2222-2222-222222222222"))),
            List.of(
                new DemoPlaceImageRow(
                    UUID.fromString("66666666-6666-6666-6666-666666666666"),
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    "https://example.com/a.jpg",
                    "https://example.com/a-thumb.jpg",
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "img-01")),
            List.of(
                new DemoProvenanceRow(
                    UUID.randomUUID(),
                    "tour_places",
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    "areaBasedList2",
                    "12",
                    "a".repeat(64),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"))));
    when(service.latestStorage()).thenReturn(view);

    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DemoImportController(service)).build();

    String body =
        mockMvc
            .perform(MockMvcRequestBuilders.get("/api/v1/demo/storage"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("\"runs\"");
    assertThat(body).contains("tour_api");
    assertThat(body).contains("areaBasedList2");
    assertThat(body).doesNotContain("serviceKey", "apiKey", "raw");
    verify(service).latestStorage();
  }

  @Test
  void storage_view_엔드포인트는_html_및_이미지_미리보기를_반환한다() throws Exception {
    DemoImportService service = mock(DemoImportService.class);
    when(service.storageView())
        .thenReturn("<html><body><pre>{\"runs\":[]}</pre><img src=\"/thumb.jpg\"/></body></html>");

    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DemoImportController(service)).build();

    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.get("/api/v1/demo/storage/view").accept(MediaType.TEXT_HTML))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("<html>");
    assertThat(body).contains("<pre>");
    verify(service).storageView();
  }
}
