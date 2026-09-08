package com.timingjeju.api.domain.profile.controller.docs;

import com.timingjeju.api.domain.profile.dto.request.ProfileImageRequest;
import com.timingjeju.api.domain.profile.dto.response.ProfileImageResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface ProfileImageApiDocs {

  String PROFILE_ETAG_PATTERN = "^\\\"profile-image-(?:0|[1-9][0-9]*)\\\"$";
  String TRACE_ID = "00000000000000000000000000000078";
  String AUTHENTICATION_REQUIRED =
      "{\"type\":\"https://api.timing-jeju.com/problems/authentication-required\","
          + "\"title\":\"인증이 필요합니다\",\"status\":401,\"detail\":\"로그인 후 다시 요청해 주세요.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"AUTHENTICATION_REQUIRED\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String INVALID_ACCESS_TOKEN =
      "{\"type\":\"https://api.timing-jeju.com/problems/invalid-access-token\","
          + "\"title\":\"인증 정보가 올바르지 않습니다\",\"status\":401,\"detail\":\"유효한 인증 정보로 다시 요청해 주세요.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"INVALID_ACCESS_TOKEN\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROFILE_DATA_UNAVAILABLE =
      "{\"type\":\"https://api.timing-jeju.com/problems/profile-data-unavailable\","
          + "\"title\":\"프로필 조회 불가\",\"status\":503,\"detail\":\"프로필 데이터를 불러올 수 없습니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"PROFILE_DATA_UNAVAILABLE\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String INVALID_PROFILE_IMAGE_REQUEST =
      "{\"type\":\"https://api.timing-jeju.com/problems/invalid-profile-image-request\","
          + "\"title\":\"프로필 이미지 요청 오류\",\"status\":400,\"detail\":\"프로필 이미지 요청 형식이 올바르지 않습니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"INVALID_PROFILE_IMAGE_REQUEST\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROFILE_IMAGE_NOT_FOUND =
      "{\"type\":\"https://api.timing-jeju.com/problems/profile-image-not-found\","
          + "\"title\":\"프로필 이미지 없음\",\"status\":404,\"detail\":\"확정할 프로필 이미지를 찾을 수 없습니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"PROFILE_IMAGE_NOT_FOUND\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROFILE_IMAGE_VERSION_CONFLICT =
      "{\"type\":\"https://api.timing-jeju.com/problems/profile-image-version-conflict\","
          + "\"title\":\"프로필 이미지 버전 충돌\",\"status\":409,\"detail\":\"프로필 이미지 상태가 변경되었습니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"PROFILE_IMAGE_VERSION_CONFLICT\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String IDEMPOTENCY_PAYLOAD_CONFLICT =
      "{\"type\":\"https://api.timing-jeju.com/problems/idempotency-payload-conflict\","
          + "\"title\":\"멱등 요청 충돌\",\"status\":409,\"detail\":\"같은 멱등성 키의 요청 본문이 다릅니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"IDEMPOTENCY_PAYLOAD_CONFLICT\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String IDEMPOTENCY_REQUEST_IN_PROGRESS =
      "{\"type\":\"https://api.timing-jeju.com/problems/idempotency-request-in-progress\","
          + "\"title\":\"멱등 요청 처리 중\",\"status\":409,\"detail\":\"동일한 요청이 처리 중입니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"IDEMPOTENCY_REQUEST_IN_PROGRESS\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROFILE_IMAGE_TOO_LARGE =
      "{\"type\":\"https://api.timing-jeju.com/problems/profile-image-too-large\","
          + "\"title\":\"프로필 이미지 용량 초과\",\"status\":413,\"detail\":\"프로필 이미지는 5 MiB 이하여야 합니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"PROFILE_IMAGE_TOO_LARGE\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED =
      "{\"type\":\"https://api.timing-jeju.com/problems/profile-image-media-type-unsupported\","
          + "\"title\":\"프로필 이미지 형식 오류\",\"status\":415,\"detail\":\"지원하지 않는 프로필 이미지 형식입니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";
  String PROFILE_IMAGE_STORAGE_UNAVAILABLE =
      "{\"type\":\"https://api.timing-jeju.com/problems/profile-image-storage-unavailable\","
          + "\"title\":\"프로필 이미지 저장소 오류\",\"status\":503,\"detail\":\"프로필 이미지 저장소를 확인할 수 없습니다.\","
          + "\"instance\":\"urn:timing-jeju:problem:"
          + TRACE_ID
          + "\",\"code\":\"PROFILE_IMAGE_STORAGE_UNAVAILABLE\",\"traceId\":\""
          + TRACE_ID
          + "\",\"fieldErrors\":[]}";

  @Operation(
      operationId = "profileImageRead",
      tags = "프로필 이미지",
      summary = "현재 프로필 이미지 조회",
      description = "현재 이미지 상태와 strong ETag를 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        headers = @Header(name = "ETag", schema = @Schema(type = "string")),
        content = @Content(schema = @Schema(implementation = ProfileImageResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "AUTHENTICATION_REQUIRED, INVALID_ACCESS_TOKEN",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples = {
                  @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = AUTHENTICATION_REQUIRED),
                  @ExampleObject(name = "INVALID_ACCESS_TOKEN", value = INVALID_ACCESS_TOKEN)
                })),
    @ApiResponse(
        responseCode = "503",
        description = "PROFILE_DATA_UNAVAILABLE",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PROFILE_DATA_UNAVAILABLE",
                        value = PROFILE_DATA_UNAVAILABLE)))
  })
  ResponseEntity<ProfileImageResponse> read(
      @Parameter(hidden = true) HttpServletRequest httpRequest);

  @Operation(
      operationId = "profileImageUpdate",
      tags = "프로필 이미지",
      summary = "프로필 이미지 확정 또는 해제",
      description = "immutable Storage generation을 확정하거나 해제합니다.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content = @Content(schema = @Schema(implementation = ProfileImageRequest.class))))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        headers = {
          @Header(name = "ETag", schema = @Schema(type = "string")),
          @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean"))
        },
        content = @Content(schema = @Schema(implementation = ProfileImageResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_PROFILE_IMAGE_REQUEST",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "INVALID_PROFILE_IMAGE_REQUEST",
                        value = INVALID_PROFILE_IMAGE_REQUEST))),
    @ApiResponse(
        responseCode = "401",
        description = "AUTHENTICATION_REQUIRED, INVALID_ACCESS_TOKEN",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples = {
                  @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = AUTHENTICATION_REQUIRED),
                  @ExampleObject(name = "INVALID_ACCESS_TOKEN", value = INVALID_ACCESS_TOKEN)
                })),
    @ApiResponse(
        responseCode = "404",
        description = "PROFILE_IMAGE_NOT_FOUND",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PROFILE_IMAGE_NOT_FOUND",
                        value = PROFILE_IMAGE_NOT_FOUND))),
    @ApiResponse(
        responseCode = "409",
        description =
            "PROFILE_IMAGE_VERSION_CONFLICT, IDEMPOTENCY_PAYLOAD_CONFLICT,"
                + " IDEMPOTENCY_REQUEST_IN_PROGRESS",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples = {
                  @ExampleObject(
                      name = "PROFILE_IMAGE_VERSION_CONFLICT",
                      value = PROFILE_IMAGE_VERSION_CONFLICT),
                  @ExampleObject(
                      name = "IDEMPOTENCY_PAYLOAD_CONFLICT",
                      value = IDEMPOTENCY_PAYLOAD_CONFLICT),
                  @ExampleObject(
                      name = "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                      value = IDEMPOTENCY_REQUEST_IN_PROGRESS)
                })),
    @ApiResponse(
        responseCode = "413",
        description = "PROFILE_IMAGE_TOO_LARGE",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PROFILE_IMAGE_TOO_LARGE",
                        value = PROFILE_IMAGE_TOO_LARGE))),
    @ApiResponse(
        responseCode = "415",
        description = "PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED",
                        value = PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED))),
    @ApiResponse(
        responseCode = "503",
        description = "PROFILE_IMAGE_STORAGE_UNAVAILABLE",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PROFILE_IMAGE_STORAGE_UNAVAILABLE",
                        value = PROFILE_IMAGE_STORAGE_UNAVAILABLE)))
  })
  ResponseEntity<ProfileImageResponse> apply(
      @Parameter(
              name = "Idempotency-Key",
              in = ParameterIn.HEADER,
              required = true,
              schema =
                  @Schema(
                      type = "string",
                      minLength = 1,
                      maxLength = 128,
                      pattern = "^[\\x20-\\x7E]{1,128}$"))
          String idempotencyKey,
      @Parameter(
              name = "If-Match",
              in = ParameterIn.HEADER,
              required = true,
              schema = @Schema(type = "string", pattern = PROFILE_ETAG_PATTERN))
          String ifMatch,
      @Parameter(hidden = true) HttpServletRequest httpRequest);
}
