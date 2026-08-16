package com.timingjeju.api.application.staypolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

final class StayPolicyPayloadHasher {

  String hash(StayPolicyPayload payload) {
    StringBuilder canonical =
        new StringBuilder(Normalizer.normalize(payload.version(), Normalizer.Form.NFKC))
            .append('\n')
            .append(payload.effectiveAt())
            .append('\n');
    payload.policies().stream()
        .sorted(
            java.util.Comparator.comparing((StayPolicyCandidate policy) -> policy.scope().name())
                .thenComparing(StayPolicyCandidate::targetKey)
                .thenComparingInt(StayPolicyCandidate::minutes))
        .forEach(
            policy ->
                canonical
                    .append(policy.scope())
                    .append('\u001f')
                    .append(policy.targetKey())
                    .append('\u001f')
                    .append(policy.minutes())
                    .append('\n'));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
