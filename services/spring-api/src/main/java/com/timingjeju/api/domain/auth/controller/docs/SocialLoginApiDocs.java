package com.timingjeju.api.domain.auth.controller.docs;

import com.timingjeju.api.domain.auth.dto.response.NaverUserInfoResponse;
import com.timingjeju.api.domain.auth.dto.response.SocialLoginProvidersResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

public interface SocialLoginApiDocs {

  @Operation(
      summary = "소셜 로그인 공급자 조회",
      description =
          "프론트엔드는 반환한 id를 Supabase signInWithOAuth provider로 사용합니다. secret과 token은 반환하지 않습니다.",
      security = {})
  SocialLoginProvidersResponse getProviders();

  @Operation(
      summary = "Naver Custom OAuth UserInfo 변환",
      description =
          "Supabase Auth custom:naver provider만 호출하는 공개 adapter입니다. Naver provider access token을 고정 Naver UserInfo endpoint로 전달하고 표준 UserInfo만 반환합니다.",
      security = {})
  @Parameter(
      name = "Authorization",
      in = ParameterIn.HEADER,
      required = true,
      description = "Naver OAuth provider access token. Supabase JWT가 아닙니다.",
      schema = @Schema(type = "string", example = "Bearer <naver-provider-access-token>"))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "표준 UserInfo",
        content = @Content(schema = @Schema(implementation = NaverUserInfoResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "Bearer 형식 또는 Naver token이 유효하지 않음",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "403",
        description = "Naver 사용자 정보 접근 거부",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "422",
        description = "Naver email 동의가 없음",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "502",
        description = "Naver 응답 오류",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "429",
        description = "Spring API 요청 제한",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Naver rate limit 또는 Spring API 동시 처리 상한",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "504",
        description = "Naver 응답 시간 초과",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  Map<String, Object> getNaverUserInfo(HttpServletRequest request);
}
