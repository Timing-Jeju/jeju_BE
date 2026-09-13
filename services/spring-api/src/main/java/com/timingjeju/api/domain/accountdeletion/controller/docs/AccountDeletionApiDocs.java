package com.timingjeju.api.domain.accountdeletion.controller.docs;

import com.timingjeju.api.domain.accountdeletion.dto.AccountDeletionAcceptedResponse;
import com.timingjeju.api.domain.accountdeletion.dto.AccountDeletionRequest;
import com.timingjeju.api.domain.accountdeletion.dto.AccountDeletionStatusResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;

public interface AccountDeletionApiDocs {
  @Operation(
      summary = "회원 탈퇴 요청",
      description = "최근 재인증 session을 확인하고 실제 삭제 worker가 처리할 탈퇴 요청을 멱등 접수합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "202",
        content =
            @Content(schema = @Schema(implementation = AccountDeletionAcceptedResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "409",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "410",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "428",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<AccountDeletionAcceptedResponse> request(
      @Parameter(
              name = "Idempotency-Key",
              in = ParameterIn.HEADER,
              required = true,
              description = "1~128자 printable ASCII 멱등성 키",
              schema = @Schema(minLength = 1, maxLength = 128, pattern = "^[\\x20-\\x7E]+$"))
          String idempotencyKey,
      AccountDeletionRequest request);

  @Operation(
      summary = "회원 탈퇴 상태 조회",
      description = "Auth 사용자 삭제 뒤에도 만료 전 opaque status token으로 상태를 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = AccountDeletionStatusResponse.class))),
    @ApiResponse(
        responseCode = "401",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "404",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "410",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  AccountDeletionStatusResponse status(
      @Parameter(schema = @Schema(pattern = "^[0-9A-HJKMNP-TV-Z]{26}$")) String deletionRequestId,
      @Parameter(
              name = "X-Deletion-Status-Token",
              in = ParameterIn.HEADER,
              required = true,
              description = "탈퇴 요청에서 발급한 opaque bearer status token",
              schema = @Schema(minLength = 43, maxLength = 43, pattern = "^[A-Za-z0-9_-]{43}$"))
          String statusToken);
}
