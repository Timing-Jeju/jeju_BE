package com.timingjeju.api.domain.accommodation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.accommodation.AccommodationException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class AccommodationRequestBoundaryTest {
  @Test
  void Idempotency_Key는_printable_ASCII_경계만_허용한다() {
    for (String valid : List.of(" ", "!", "~", "client key,2026/09", "Z".repeat(128))) {
      MockHttpServletRequest request = request(valid);
      assertThat(
              AccommodationRequestBoundary.requiredPrintableAsciiHeader(request, "Idempotency-Key"))
          .isEqualTo(valid);
    }

    for (String invalid : List.of("\u001f", "\u007f", "비ASCII", "Z".repeat(129))) {
      assertThatThrownBy(
              () ->
                  AccommodationRequestBoundary.requiredPrintableAsciiHeader(
                      request(invalid), "Idempotency-Key"))
          .isInstanceOf(AccommodationException.class);
    }
  }

  private static MockHttpServletRequest request(String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Idempotency-Key", value);
    return request;
  }
}
