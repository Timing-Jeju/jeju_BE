package com.timingjeju.api.application.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.timingjeju.api.application.profile.CurrentUserProfile;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.CurrentUserProfileStore;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfilePatchCommand;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CurrentUserProfileServiceTest {

  private static final UUID USER_ID = UUID.fromString("18000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
  private static final CurrentUser USER =
      new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null);

  @Test
  void GET은_64_provisioning을_먼저_호출하고_canonical_sub로만_읽는다() {
    CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
    RecordingStore store = new RecordingStore();
    store.profile = Optional.of(profile());

    CurrentUserProfile result = service(provisioning, store).read(USER);

    verify(provisioning).provision(USER);
    assertThat(store.readUserId).isEqualTo(USER_ID);
    assertThat(result.userId()).isEqualTo(USER_ID);
  }

  @Test
  void provisioning후_profile이_없으면_닫힌_503오류로_변환한다() {
    CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
    RecordingStore store = new RecordingStore();

    assertThatThrownBy(() -> service(provisioning, store).read(USER))
        .isInstanceOf(CurrentUserProfileException.class)
        .extracting(failure -> ((CurrentUserProfileException) failure).code())
        .isEqualTo("PROFILE_DATA_UNAVAILABLE");
    verify(provisioning).provision(USER);
  }

  @Test
  void PATCH는_64_provisioning후_canonical_sub와_서버시각만_store에_전달한다() {
    CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
    RecordingStore store = new RecordingStore();
    store.profile = Optional.of(profile());
    ProfilePatchCommand command = new ProfilePatchCommand(true, " 여행자 ", false, null);

    service(provisioning, store).update(USER, command);

    verify(provisioning).provision(USER);
    assertThat(store.updatedUserId).isEqualTo(USER_ID);
    assertThat(store.command.nickname()).isEqualTo("여행자");
    assertThat(store.updatedAt).isEqualTo(NOW);
  }

  private static CurrentUserProfileService service(
      CurrentUserProvisioningService provisioning, RecordingStore store) {
    return new CurrentUserProfileService(provisioning, store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static CurrentUserProfile profile() {
    return new CurrentUserProfile(
        USER_ID, "user@example.invalid", "여행자", null, "ko-KR", List.of(), false, NOW);
  }

  private static final class RecordingStore implements CurrentUserProfileStore {
    private Optional<CurrentUserProfile> profile = Optional.empty();
    private UUID readUserId;
    private UUID updatedUserId;
    private ProfilePatchCommand command;
    private Instant updatedAt;

    @Override
    public Optional<CurrentUserProfile> read(UUID userId) {
      readUserId = userId;
      return profile;
    }

    @Override
    public CurrentUserProfile update(
        UUID userId, ProfilePatchCommand patchCommand, Instant updateInstant) {
      updatedUserId = userId;
      command = patchCommand;
      updatedAt = updateInstant;
      return profile.orElseThrow();
    }
  }
}
