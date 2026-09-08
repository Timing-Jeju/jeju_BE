package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.ProfileImageSource;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class ProfileImageProviderFallback {

  private static final Map<String, Integer> PRIORITY = Map.of("google", 1, "kakao", 2, "naver", 3);
  private static final Pattern PUBLIC_URL =
      Pattern.compile("^https://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{1,2040}$");

  private ProfileImageProviderFallback() {}

  static Optional<String> choose(List<Candidate> candidates) {
    return candidates.stream()
        .filter(candidate -> PRIORITY.containsKey(candidate.provider()))
        .filter(candidate -> validHttps(candidate.imageUrl()))
        .sorted(Comparator.comparingInt(candidate -> PRIORITY.get(candidate.provider())))
        .map(Candidate::imageUrl)
        .findFirst();
  }

  static Selection resolve(List<Candidate> candidates, String legacyImageUrl) {
    String selected =
        choose(candidates)
            .or(() -> validHttps(legacyImageUrl) ? Optional.of(legacyImageUrl) : Optional.empty())
            .orElse(null);
    return new Selection(
        selected, selected == null ? ProfileImageSource.NONE : ProfileImageSource.PROVIDER);
  }

  private static boolean validHttps(String value) {
    if (value == null || !PUBLIC_URL.matcher(value).matches()) {
      return false;
    }
    try {
      URI uri = URI.create(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && !uri.getHost().isBlank()
          && uri.getRawUserInfo() == null;
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  record Candidate(String provider, String imageUrl) {}

  record Selection(String imageUrl, ProfileImageSource source) {}
}
