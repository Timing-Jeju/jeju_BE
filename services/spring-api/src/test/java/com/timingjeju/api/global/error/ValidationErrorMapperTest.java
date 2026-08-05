package com.timingjeju.api.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

@Tag("unit")
class ValidationErrorMapperTest {

  private final ValidationErrorMapper mapper = new ValidationErrorMapper();

  @Test
  void constraint_code만으로_고정된_한국어_detail을_선택한다() {
    Map<String, String> expectedDetails =
        Map.of(
            "NotBlank", "필수 입력값입니다.",
            "Min", "최솟값 조건을 확인해 주세요.",
            "Max", "최댓값 조건을 확인해 주세요.",
            "Size", "길이 조건을 확인해 주세요.",
            "Email", "입력 형식이 올바르지 않습니다.");

    expectedDetails.forEach(
        (code, expectedDetail) -> {
          FieldError rawError =
              new FieldError(
                  "request",
                  "items[0].name",
                  "raw-user@example.test",
                  false,
                  new String[] {code + ".request.items.name", code},
                  null,
                  "raw provider payload");

          assertThat(mapper.map(rawError))
              .isEqualTo(new FieldErrorDetail("items[0].name", expectedDetail));
        });
  }

  @Test
  void unknown_message와_동적_field_path는_고정값으로_비노출한다() {
    DefaultMessageSourceResolvable rawError =
        new DefaultMessageSourceResolvable(
            new String[] {"CustomConstraint"}, null, "provider-token-user@example.test");

    assertThat(mapper.map("map[secret-key].email", rawError))
        .isEqualTo(new FieldErrorDetail("request", "유효하지 않은 값입니다."));
    assertThat(
            mapper.map(
                new ObjectError(
                    "request", new String[] {"NotNull"}, null, "raw-user@example.test")))
        .isEqualTo(new FieldErrorDetail("request", "필수 입력값입니다."));
  }

  @Test
  void ConstraintViolation도_annotation_code와_안전한_path만_사용한다() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      var violation = factory.getValidator().validate(new ConstraintRequest("x")).iterator().next();

      assertThat(mapper.map(violation)).isEqualTo(new FieldErrorDetail("value", "길이 조건을 확인해 주세요."));
    }
  }

  record ConstraintRequest(@Size(min = 2, message = "raw-user@example.test") String value) {}
}
