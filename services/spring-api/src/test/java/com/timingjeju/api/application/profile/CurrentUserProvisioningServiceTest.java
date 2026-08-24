package com.timingjeju.api.application.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class CurrentUserProvisioningServiceTest {

  private static final UUID USER_ID = UUID.fromString("64000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
  private static final CurrentUser CURRENT_USER =
      new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null);

  @Test
  void email_identity는_profile만_만들고_social_account를_만들지_않는다() {
    RecordingStore store = new RecordingStore();
    CurrentUserProvisioningService service =
        service(
            List.of(
                identity(
                    "email",
                    "email-subject",
                    "member@timing-jeju.local",
                    "이메일 사용자",
                    "profiles/email.png")),
            store);

    ProvisionedCurrentUser result = service.provision(CURRENT_USER);

    assertThat(store.requests)
        .containsExactly(
            new ProfileProvisioningRequest(
                USER_ID,
                "member@timing-jeju.local",
                "이메일 사용자",
                "profiles/email.png",
                List.of(),
                NOW));
    assertThat(result.userId()).isEqualTo(USER_ID);
    assertThat(result.providers()).isEmpty();
  }

  @Test
  void profile_legal_계약의_email_only_projection과_provisioning_결과는_빈_provider로_일치한다()
      throws IOException {
    Path contractPath =
        Path.of("..", "..", "docs", "contracts", "domains", "profile-legal", "contract.json");
    JsonNode contract = new ObjectMapper().readTree(Files.readString(contractPath));
    JsonNode providerPolicy = contract.path("profileProviderPolicy");

    assertThat(
            contract
                .path("schemas")
                .path("ProfileResponse")
                .path("properties")
                .path("providers")
                .path("minItems")
                .intValue())
        .isZero();
    assertThat(providerPolicy.path("allowed").toString())
        .isEqualTo("[\"google\",\"kakao\",\"custom:naver\"]");
    assertThat(providerPolicy.path("stableOrder").toString())
        .isEqualTo("[\"google\",\"kakao\",\"custom:naver\"]");
    assertThat(providerPolicy.path("emailIdentity").textValue())
        .isEqualTo("exclude; email-only identity projects []");

    RecordingStore store = new RecordingStore();
    ProvisionedCurrentUser result =
        service(
                List.of(
                    identity(
                        "email",
                        "email-contract-subject",
                        "contract@timing-jeju.local",
                        null,
                        null)),
                store)
            .provision(CURRENT_USER);

    assertThat(store.requests.getFirst().socialAccounts()).isEmpty();
    assertThat(result.providers()).isEmpty();
  }

  @Test
  void oauth_identity는_세_provider를_DB값과_공개값으로_각각_정규화한다() {
    RecordingStore store = new RecordingStore();
    CurrentUserProvisioningService service =
        service(
            List.of(
                identity("custom:naver", "naver-64", "n@local.test", "네이버", "n.png"),
                identity("kakao", "kakao-64", "k@local.test", "카카오", "k.png"),
                identity("google", "google-64", "g@local.test", "구글", "g.png")),
            store);

    ProvisionedCurrentUser result = service.provision(CURRENT_USER);

    assertThat(store.requests.getFirst().socialAccounts())
        .containsExactly(
            new ProvisioningSocialAccount("google", "google-64", "g@local.test", "구글", "g.png"),
            new ProvisioningSocialAccount("kakao", "kakao-64", "k@local.test", "카카오", "k.png"),
            new ProvisioningSocialAccount("naver", "naver-64", "n@local.test", "네이버", "n.png"));
    assertThat(result.providers()).containsExactly("google", "kakao", "custom:naver");
  }

  @Test
  void identity_조회_순서가_달라도_profile_표시값과_social_순서는_결정적이다() {
    List<AuthIdentity> identities =
        List.of(
            identity("kakao", "kakao-64", "k@local.test", "카카오", "k.png"),
            identity("email", "email-64", "owner@local.test", null, null),
            identity("google", "google-64", "g@local.test", "구글", "g.png"));
    RecordingStore first = new RecordingStore();
    RecordingStore second = new RecordingStore();

    service(identities, first).provision(CURRENT_USER);
    service(identities.reversed(), second).provision(CURRENT_USER);

    assertThat(first.requests).isEqualTo(second.requests);
    assertThat(first.requests.getFirst().email()).isEqualTo("owner@local.test");
    assertThat(first.requests.getFirst().nickname()).isEqualTo("구글");
    assertThat(first.requests.getFirst().profileImageUrl()).isEqualTo("g.png");
  }

  @Test
  void 동일한_현재사용자의_반복_provision은_같은_request로_수렴한다() {
    RecordingStore store = new RecordingStore();
    CurrentUserProvisioningService service =
        service(List.of(identity("google", "google-64", "g@local.test", "구글", "g.png")), store);

    service.provision(CURRENT_USER);
    service.provision(CURRENT_USER);

    assertThat(store.requests).hasSize(2);
    assertThat(store.requests.get(0)).isEqualTo(store.requests.get(1));
  }

  @Test
  void providerId는_opaque값의_Unicode_공백과_case를_그대로_보존한다() {
    String opaqueProviderId = "\u2003Provider-Subject-Case\u2003";
    RecordingStore store = new RecordingStore();

    service(List.of(identity("google", opaqueProviderId, null, null, null)), store)
        .provision(CURRENT_USER);

    assertThat(store.requests.getFirst().socialAccounts().getFirst().providerUserId())
        .isEqualTo(opaqueProviderId);
  }

  @Test
  void providerId는_공백_only와_상한_초과를_거부하고_상한값은_exact_보존한다() {
    RecordingStore accepted = new RecordingStore();
    String max = "A".repeat(512);
    service(List.of(identity("google", max, null, null, null)), accepted).provision(CURRENT_USER);
    assertThat(accepted.requests.getFirst().socialAccounts().getFirst().providerUserId())
        .isEqualTo(max);

    for (String invalid : List.of("\u2003\u2003", "\u00a0\u00a0", "A".repeat(513))) {
      RecordingStore rejected = new RecordingStore();
      assertThatThrownBy(
              () ->
                  service(List.of(identity("google", invalid, null, null, null)), rejected)
                      .provision(CURRENT_USER))
          .isInstanceOf(ProfileProvisioningException.class)
          .extracting("code")
          .isEqualTo(ProfileProvisioningError.INVALID_AUTH_IDENTITY);
      assertThat(rejected.requests).isEmpty();
    }
  }

  @Test
  void provider나_provider_id가_누락되거나_미지원이면_저장_전에_거부한다() {
    for (AuthIdentity invalid :
        List.of(
            identity(null, "subject", null, null, null),
            identity(" ", "subject", null, null, null),
            identity("google", null, null, null, null),
            identity("google", " ", null, null, null),
            identity("naver", "naver-db-name", null, null, null),
            identity("github", "github-64", null, null, null))) {
      RecordingStore store = new RecordingStore();

      assertThatThrownBy(() -> service(List.of(invalid), store).provision(CURRENT_USER))
          .isInstanceOf(ProfileProvisioningException.class)
          .extracting("code")
          .isEqualTo(ProfileProvisioningError.INVALID_AUTH_IDENTITY);
      assertThat(store.requests).isEmpty();
    }
  }

  @Test
  void 같은_provider에_상이한_subject가_있으면_자동_연결하지_않는다() {
    RecordingStore store = new RecordingStore();

    assertThatThrownBy(
            () ->
                service(
                        List.of(
                            identity("google", "google-a", null, null, null),
                            identity("google", "google-b", null, null, null)),
                        store)
                    .provision(CURRENT_USER))
        .isInstanceOf(ProfileProvisioningException.class)
        .extracting("code")
        .isEqualTo(ProfileProvisioningError.PROVIDER_SUBJECT_CONFLICT);
    assertThat(store.requests).isEmpty();
  }

  @Test
  void canonical_sub만_reader와_store의_소유권_근거로_사용한다() {
    RecordingIdentityReader reader =
        new RecordingIdentityReader(
            List.of(identity("google", "forged-metadata-sub", "mail@local.test", null, null)));
    RecordingStore store = new RecordingStore();
    CurrentUserProvisioningService service =
        new CurrentUserProvisioningService(reader, store, Clock.fixed(NOW, ZoneOffset.UTC));

    service.provision(CURRENT_USER);

    assertThat(reader.requestedUserIds).containsExactly(USER_ID);
    assertThat(store.requests.getFirst().userId()).isEqualTo(USER_ID);
    assertThat(store.requests.getFirst().socialAccounts().getFirst().providerUserId())
        .isEqualTo("forged-metadata-sub");
  }

  @Test
  void 저장소의_email과_provider_subject_충돌은_안정적인_도메인_code를_보존한다() {
    RecordingStore store = new RecordingStore();
    store.failure = ProfileProvisioningException.emailConflict();
    CurrentUserProvisioningService service =
        service(List.of(identity("email", "email-64", "same@local.test", null, null)), store);

    assertThatThrownBy(() -> service.provision(CURRENT_USER))
        .isInstanceOf(ProfileProvisioningException.class)
        .extracting("code")
        .isEqualTo(ProfileProvisioningError.EMAIL_OWNERSHIP_CONFLICT);

    store.failure = ProfileProvisioningException.providerSubjectConflict();
    assertThatThrownBy(() -> service.provision(CURRENT_USER))
        .isInstanceOf(ProfileProvisioningException.class)
        .extracting("code")
        .isEqualTo(ProfileProvisioningError.PROVIDER_SUBJECT_CONFLICT);
  }

  private static CurrentUserProvisioningService service(
      List<AuthIdentity> identities, RecordingStore store) {
    return new CurrentUserProvisioningService(
        userId -> identities, store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static AuthIdentity identity(
      String provider, String providerId, String email, String nickname, String profileImageUrl) {
    return new AuthIdentity(provider, providerId, email, nickname, profileImageUrl);
  }

  private static final class RecordingIdentityReader implements AuthIdentityReader {
    private final List<AuthIdentity> identities;
    private final List<UUID> requestedUserIds = new ArrayList<>();

    private RecordingIdentityReader(List<AuthIdentity> identities) {
      this.identities = identities;
    }

    @Override
    public List<AuthIdentity> readByUserId(UUID userId) {
      requestedUserIds.add(userId);
      return identities;
    }
  }

  private static final class RecordingStore implements ProfileProvisioningStore {
    private final List<ProfileProvisioningRequest> requests = new ArrayList<>();
    private RuntimeException failure;

    @Override
    public ProvisionedCurrentUser provision(ProfileProvisioningRequest request) {
      requests.add(request);
      if (failure != null) {
        throw failure;
      }
      List<String> providers =
          request.socialAccounts().stream().map(ProvisioningSocialAccount::publicProvider).toList();
      return new ProvisionedCurrentUser(request.userId(), providers);
    }
  }
}
