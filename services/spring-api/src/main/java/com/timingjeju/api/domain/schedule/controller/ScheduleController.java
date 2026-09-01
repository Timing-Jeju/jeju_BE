package com.timingjeju.api.domain.schedule.controller;

import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.service.ScheduleQueryService;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.schedule.controller.docs.ScheduleApiDocs;
import com.timingjeju.api.domain.schedule.dto.ScheduleResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/schedule")
public final class ScheduleController implements ScheduleApiDocs {
  private static final Set<String> QUERY_PARAMETERS = Set.of("versionId");
  private static final Pattern CANONICAL_UUID = Pattern.compile(ScheduleApiDocs.UUID_PATTERN);
  private final ScheduleQueryService schedules;
  private final CurrentUserAccessor currentUsers;

  public ScheduleController(ScheduleQueryService schedules, CurrentUserAccessor currentUsers) {
    this.schedules = schedules;
    this.currentUsers = currentUsers;
  }

  @Override
  @GetMapping
  public ScheduleResponse read(
      @PathVariable String tripId,
      @RequestParam(required = false) String versionId,
      HttpServletRequest request) {
    validateShape(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    UUID canonicalVersionId = versionId == null ? null : parseCanonicalUuid(versionId);
    return ScheduleResponse.from(
        schedules.read(currentUsers.getRequired(), canonicalTripId, canonicalVersionId));
  }

  private static void validateShape(HttpServletRequest request) {
    if (!QUERY_PARAMETERS.containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().entrySet().stream()
            .anyMatch(entry -> entry.getValue().length != 1 || entry.getValue()[0].isBlank())
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ScheduleException.invalidRequest();
    }
  }

  private static UUID parseCanonicalUuid(String raw) {
    if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
      throw ScheduleException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) {
        throw ScheduleException.invalidRequest();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw ScheduleException.invalidRequest();
    }
  }
}
