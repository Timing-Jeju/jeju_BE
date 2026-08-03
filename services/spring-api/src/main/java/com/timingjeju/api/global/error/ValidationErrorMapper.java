package com.timingjeju.api.global.error;

import jakarta.validation.ConstraintViolation;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

public final class ValidationErrorMapper {

  private static final String DEFAULT_DETAIL = "유효하지 않은 값입니다.";
  private static final Pattern SAFE_FIELD_PATH =
      Pattern.compile(
          "[A-Za-z_][A-Za-z0-9_]*(?:\\[\\d+])?(?:\\.[A-Za-z_][A-Za-z0-9_]*(?:\\[\\d+])?)*");

  public FieldErrorDetail map(FieldError error) {
    return new FieldErrorDetail(safeField(error.getField()), detail(error));
  }

  public FieldErrorDetail map(ObjectError error) {
    return new FieldErrorDetail("request", detail(error));
  }

  public FieldErrorDetail map(String field, MessageSourceResolvable error) {
    return new FieldErrorDetail(safeField(field), detail(error));
  }

  public FieldErrorDetail map(ConstraintViolation<?> violation) {
    String constraintName =
        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    return new FieldErrorDetail(
        safeField(violation.getPropertyPath().toString()), detail(constraintName));
  }

  private static String safeField(String field) {
    return field != null && SAFE_FIELD_PATH.matcher(field).matches() ? field : "request";
  }

  private static String detail(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    if (codes == null) {
      return DEFAULT_DETAIL;
    }
    return Arrays.stream(codes)
        .map(ValidationErrorMapper::constraintName)
        .map(ValidationErrorMapper::detail)
        .filter(value -> !DEFAULT_DETAIL.equals(value))
        .findFirst()
        .orElse(DEFAULT_DETAIL);
  }

  private static String constraintName(String code) {
    int separator = code.indexOf('.');
    return separator < 0 ? code : code.substring(0, separator);
  }

  private static String detail(String constraintName) {
    return switch (constraintName) {
      case "NotBlank", "NotEmpty", "NotNull" -> "필수 입력값입니다.";
      case "Min", "DecimalMin", "Positive", "PositiveOrZero" -> "최솟값 조건을 확인해 주세요.";
      case "Max", "DecimalMax", "Negative", "NegativeOrZero" -> "최댓값 조건을 확인해 주세요.";
      case "Size" -> "길이 조건을 확인해 주세요.";
      case "Email", "Pattern" -> "입력 형식이 올바르지 않습니다.";
      default -> DEFAULT_DETAIL;
    };
  }
}
