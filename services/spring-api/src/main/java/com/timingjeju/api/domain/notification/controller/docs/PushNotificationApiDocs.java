package com.timingjeju.api.domain.notification.controller.docs;

import com.timingjeju.api.domain.notification.dto.request.NotificationPreferencePatchRequest;
import com.timingjeju.api.domain.notification.dto.request.PushDeviceRegistrationRequest;
import com.timingjeju.api.domain.notification.dto.response.NotificationPreferenceResponse;
import com.timingjeju.api.domain.notification.dto.response.PushDeviceResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

public interface PushNotificationApiDocs {

  String AUTH_DESCRIPTION =
      "현재 runtime stable code AUTH_TOKEN_INVALID; canonical common contract의 "
          + "AUTHENTICATION_REQUIRED/INVALID_ACCESS_TOKEN과 drift가 있어 implementation not-ready";

  @Operation(summary = "현재 사용자 푸시 기기 등록", description = "FCM token을 응답에 노출하지 않고 기기를 멱등 등록·회전합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "200 PushDeviceResponse",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PushDeviceResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_PUSH_NOTIFICATION_REQUEST",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "INVALID_PUSH_NOTIFICATION_REQUEST",
                        value = "{\"code\":\"INVALID_PUSH_NOTIFICATION_REQUEST\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = AUTH_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "AUTH_TOKEN_INVALID",
                        value = "{\"code\":\"AUTH_TOKEN_INVALID\"}"))),
    @ApiResponse(
        responseCode = "403",
        description = "AUTH_ACCESS_DENIED",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "AUTH_ACCESS_DENIED",
                        value = "{\"code\":\"AUTH_ACCESS_DENIED\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "INTERNAL_SERVER_ERROR",
                        value = "{\"code\":\"INTERNAL_SERVER_ERROR\"}"))),
    @ApiResponse(
        responseCode = "503",
        description = "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
                        value = "{\"code\":\"PUSH_NOTIFICATION_DATA_UNAVAILABLE\"}")))
  })
  PushDeviceResponse register(
      @Parameter(
              name = "deviceId",
              in = ParameterIn.PATH,
              required = true,
              description =
                  "application-generated lowercase canonical UUID; hardware/advertising ID 금지",
              example = "11300000-0000-0000-0000-000000000101",
              schema =
                  @Schema(
                      type = "string",
                      format = "uuid",
                      pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
          String deviceId,
      PushDeviceRegistrationRequest request);

  @Operation(
      summary = "현재 사용자 푸시 기기 해제",
      description = "로그아웃 단일 기기 해제이며 회원 탈퇴 전체 차단 boundary와 구분됩니다.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "204 No Content; response body forbidden"),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_PUSH_NOTIFICATION_REQUEST",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "INVALID_PUSH_NOTIFICATION_REQUEST",
                        value = "{\"code\":\"INVALID_PUSH_NOTIFICATION_REQUEST\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = AUTH_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "AUTH_TOKEN_INVALID",
                        value = "{\"code\":\"AUTH_TOKEN_INVALID\"}"))),
    @ApiResponse(
        responseCode = "403",
        description = "AUTH_ACCESS_DENIED",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "AUTH_ACCESS_DENIED",
                        value = "{\"code\":\"AUTH_ACCESS_DENIED\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "INTERNAL_SERVER_ERROR",
                        value = "{\"code\":\"INTERNAL_SERVER_ERROR\"}"))),
    @ApiResponse(
        responseCode = "503",
        description = "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
                        value = "{\"code\":\"PUSH_NOTIFICATION_DATA_UNAVAILABLE\"}")))
  })
  void invalidate(
      @Parameter(
              name = "deviceId",
              in = ParameterIn.PATH,
              required = true,
              description =
                  "application-generated lowercase canonical UUID; hardware/advertising ID 금지",
              example = "11300000-0000-0000-0000-000000000101",
              schema =
                  @Schema(
                      type = "string",
                      format = "uuid",
                      pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
          String deviceId);

  @Operation(summary = "현재 사용자 출발 알림 설정 조회")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "200 NotificationPreferenceResponse",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = NotificationPreferenceResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = AUTH_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "AUTH_TOKEN_INVALID",
                        value = "{\"code\":\"AUTH_TOKEN_INVALID\"}"))),
    @ApiResponse(
        responseCode = "403",
        description = "AUTH_ACCESS_DENIED",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "AUTH_ACCESS_DENIED",
                        value = "{\"code\":\"AUTH_ACCESS_DENIED\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "INTERNAL_SERVER_ERROR",
                        value = "{\"code\":\"INTERNAL_SERVER_ERROR\"}"))),
    @ApiResponse(
        responseCode = "503",
        description = "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
                        value = "{\"code\":\"PUSH_NOTIFICATION_DATA_UNAVAILABLE\"}")))
  })
  NotificationPreferenceResponse readPreferences();

  @Operation(summary = "현재 사용자 출발 알림 설정 변경")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "200 NotificationPreferenceResponse",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = NotificationPreferenceResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_PUSH_NOTIFICATION_REQUEST",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "INVALID_PUSH_NOTIFICATION_REQUEST",
                        value = "{\"code\":\"INVALID_PUSH_NOTIFICATION_REQUEST\"}"))),
    @ApiResponse(
        responseCode = "401",
        description = AUTH_DESCRIPTION,
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "AUTH_TOKEN_INVALID",
                        value = "{\"code\":\"AUTH_TOKEN_INVALID\"}"))),
    @ApiResponse(
        responseCode = "403",
        description = "AUTH_ACCESS_DENIED",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "AUTH_ACCESS_DENIED",
                        value = "{\"code\":\"AUTH_ACCESS_DENIED\"}"))),
    @ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "INTERNAL_SERVER_ERROR",
                        value = "{\"code\":\"INTERNAL_SERVER_ERROR\"}"))),
    @ApiResponse(
        responseCode = "503",
        description = "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class),
                examples =
                    @ExampleObject(
                        name = "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
                        value = "{\"code\":\"PUSH_NOTIFICATION_DATA_UNAVAILABLE\"}")))
  })
  NotificationPreferenceResponse updatePreferences(NotificationPreferencePatchRequest request);
}
