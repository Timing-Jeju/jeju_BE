package com.timingjeju.api.domain.savedplaces.controller.docs;

import com.timingjeju.api.domain.savedplaces.dto.CreateSavedPlaceRequest;
import com.timingjeju.api.domain.savedplaces.dto.PatchSavedPlaceRequest;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceResponse;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlacesListResponse;
import com.timingjeju.api.global.error.ApiProblemDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;

public interface SavedPlacesApiDocs {
  @Operation(summary = "관심 장소 목록", description = "인증 사용자 범위의 안정적인 keyset cursor 목록입니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = SavedPlacesListResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  SavedPlacesListResponse list(
      @Parameter(schema = @Schema(minLength = 1, maxLength = 50)) String tag,
      @Pattern(regexp = "^(?:[A-Z]{2}|content-type:[0-9]{1,10})$") String category,
      @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") @Size(max = 50) String regionCode,
      @Pattern(regexp = "^(?:saved_at_desc|priority_desc|target_day_asc)$") String sort,
      @Size(min = 1, max = 2048) String cursor,
      @Min(1) @Max(100) Integer size,
      @Parameter(hidden = true) HttpServletRequest httpRequest);

  @Operation(summary = "관심 장소 저장")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        headers = {
          @Header(name = "Location"),
          @Header(name = "ETag"),
          @Header(name = "Idempotency-Replayed")
        },
        content = @Content(schema = @Schema(implementation = SavedPlaceResponse.class))),
    @ApiResponse(
        responseCode = "200",
        description = "동일 요청 replay 또는 동일한 현재 resource",
        headers = {
          @Header(name = "Location"),
          @Header(name = "ETag"),
          @Header(name = "Idempotency-Replayed")
        },
        content = @Content(schema = @Schema(implementation = SavedPlaceResponse.class))),
    @ApiResponse(
        responseCode = "400",
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
        responseCode = "409",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "422",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<byte[]> create(
      @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String key,
      CreateSavedPlaceRequest request,
      @Parameter(hidden = true) HttpServletRequest httpRequest);

  @Operation(summary = "관심 장소 수정", description = "If-Match strong ETag로 원자 비교 갱신합니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        headers = @Header(name = "ETag"),
        content = @Content(schema = @Schema(implementation = SavedPlaceResponse.class))),
    @ApiResponse(
        responseCode = "400",
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
        responseCode = "409",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "422",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<SavedPlaceResponse> patch(
      @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
          String placeId,
      @Pattern(regexp = "^\"[A-Za-z0-9._:-]{1,128}\"$") String ifMatch,
      PatchSavedPlaceRequest request,
      @Parameter(hidden = true) HttpServletRequest httpRequest);

  @Operation(summary = "관심 장소 삭제")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "삭제 완료"),
    @ApiResponse(
        responseCode = "400",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class))),
    @ApiResponse(
        responseCode = "404",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = ApiProblemDetails.class)))
  })
  ResponseEntity<Void> delete(
      @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
          String placeId,
      @Parameter(hidden = true) HttpServletRequest httpRequest);
}
