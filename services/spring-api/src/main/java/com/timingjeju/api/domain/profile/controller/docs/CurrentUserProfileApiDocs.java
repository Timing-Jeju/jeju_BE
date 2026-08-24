package com.timingjeju.api.domain.profile.controller.docs;

import com.timingjeju.api.domain.profile.dto.request.CurrentUserProfilePatchRequest;
import com.timingjeju.api.domain.profile.dto.response.CurrentUserProfileResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

public interface CurrentUserProfileApiDocs {

  @Operation(summary = "현재 사용자 프로필 조회", description = "canonical JWT sub의 프로필을 생성 보장한 뒤 조회합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = CurrentUserProfileResponse.class))),
    @ApiResponse(
        responseCode = "401",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  CurrentUserProfileResponse read();

  @Operation(
      summary = "현재 사용자 프로필 수정",
      description = "nickname과 locale만 canonical JWT sub의 프로필에 반영합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = CurrentUserProfileResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "401",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  CurrentUserProfileResponse update(CurrentUserProfilePatchRequest request);
}
