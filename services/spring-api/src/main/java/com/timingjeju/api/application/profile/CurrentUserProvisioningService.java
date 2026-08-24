package com.timingjeju.api.application.profile;

import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class CurrentUserProvisioningService {

  private static final List<String> IDENTITY_ORDER = List.of("email", "google", "kakao", "naver");

  private final AuthIdentityReader identityReader;
  private final ProfileProvisioningStore store;
  private final Clock clock;

  public CurrentUserProvisioningService(
      AuthIdentityReader identityReader, ProfileProvisioningStore store, Clock clock) {
    this.identityReader = Objects.requireNonNull(identityReader, "identityReader must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ProvisionedCurrentUser provision(CurrentUser currentUser) {
    Objects.requireNonNull(currentUser, "currentUser must not be null");
    List<NormalizedIdentity> identities =
        normalize(identityReader.readByUserId(currentUser.userId()));
    ProfileProvisioningRequest request = request(currentUser, identities, clock.instant());
    return store.provision(request);
  }

  private static List<NormalizedIdentity> normalize(List<AuthIdentity> source) {
    if (source == null || source.isEmpty()) {
      throw ProfileProvisioningException.invalidAuthIdentity();
    }
    List<NormalizedIdentity> normalized =
        source.stream()
            .map(CurrentUserProvisioningService::normalize)
            .sorted(identityOrder())
            .toList();
    Map<String, NormalizedIdentity> byProvider = new LinkedHashMap<>();
    for (NormalizedIdentity identity : normalized) {
      NormalizedIdentity previous = byProvider.putIfAbsent(identity.provider, identity);
      if (previous != null && !previous.providerId.equals(identity.providerId)) {
        throw ProfileProvisioningException.providerSubjectConflict();
      }
    }
    return List.copyOf(byProvider.values());
  }

  private static NormalizedIdentity normalize(AuthIdentity identity) {
    if (identity == null) {
      throw ProfileProvisioningException.invalidAuthIdentity();
    }
    String provider = normalizedRequired(identity.provider()).toLowerCase(Locale.ROOT);
    String providerId = opaqueProviderId(identity.providerId());
    provider = databaseProvider(provider);
    return new NormalizedIdentity(
        provider,
        providerId,
        normalizedOptional(identity.email()),
        normalizedOptional(identity.nickname()),
        normalizedOptional(identity.profileImageUrl()));
  }

  private static ProfileProvisioningRequest request(
      CurrentUser currentUser, List<NormalizedIdentity> identities, java.time.Instant requestedAt) {
    List<ProvisioningSocialAccount> socialAccounts = new ArrayList<>();
    for (NormalizedIdentity identity : identities) {
      if (!"email".equals(identity.provider)) {
        socialAccounts.add(identity.socialAccount());
      }
    }
    return new ProfileProvisioningRequest(
        currentUser.userId(),
        first(identities, Value.EMAIL),
        first(identities, Value.NICKNAME),
        first(identities, Value.PROFILE_IMAGE),
        socialAccounts,
        requestedAt);
  }

  private static String first(List<NormalizedIdentity> identities, Value value) {
    return identities.stream().map(value::read).filter(Objects::nonNull).findFirst().orElse(null);
  }

  private static Comparator<NormalizedIdentity> identityOrder() {
    return Comparator.comparingInt(
            (NormalizedIdentity identity) -> IDENTITY_ORDER.indexOf(identity.provider))
        .thenComparing(identity -> identity.providerId)
        .thenComparing(identity -> Objects.toString(identity.email, ""))
        .thenComparing(identity -> Objects.toString(identity.nickname, ""))
        .thenComparing(identity -> Objects.toString(identity.profileImageUrl, ""));
  }

  private static String normalizedRequired(String value) {
    String normalized = normalizedOptional(value);
    if (normalized == null) {
      throw ProfileProvisioningException.invalidAuthIdentity();
    }
    return normalized;
  }

  private static String opaqueProviderId(String value) {
    if (value == null || unicodeBlank(value) || value.length() > 512) {
      throw ProfileProvisioningException.invalidAuthIdentity();
    }
    return value;
  }

  private static boolean unicodeBlank(String value) {
    return value.isEmpty()
        || value
            .codePoints()
            .allMatch(
                codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint));
  }

  private static String databaseProvider(String authProvider) {
    return switch (authProvider) {
      case "email", "google", "kakao" -> authProvider;
      case "custom:naver" -> "naver";
      default -> throw ProfileProvisioningException.invalidAuthIdentity();
    };
  }

  private static String normalizedOptional(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }

  private enum Value {
    EMAIL {
      @Override
      String read(NormalizedIdentity identity) {
        return identity.email;
      }
    },
    NICKNAME {
      @Override
      String read(NormalizedIdentity identity) {
        return identity.nickname;
      }
    },
    PROFILE_IMAGE {
      @Override
      String read(NormalizedIdentity identity) {
        return identity.profileImageUrl;
      }
    };

    abstract String read(NormalizedIdentity identity);
  }

  private record NormalizedIdentity(
      String provider, String providerId, String email, String nickname, String profileImageUrl) {

    ProvisioningSocialAccount socialAccount() {
      return new ProvisioningSocialAccount(provider, providerId, email, nickname, profileImageUrl);
    }
  }
}
