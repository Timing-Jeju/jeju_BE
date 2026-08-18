package com.timingjeju.api.application.staypolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

final class StayPolicyPayloadHasher {

  String hash(StayPolicyPayload payload) {
    StringBuilder canonical =
        new StringBuilder(normalize(payload.version()))
            .append('\n')
            .append(payload.effectiveAt())
            .append('\n');
    payload.policies().stream()
        .sorted(
            java.util.Comparator.comparing((StayPolicyCandidate policy) -> policy.scope().name())
                .thenComparing(policy -> normalize(policy.targetKey()))
                .thenComparingInt(StayPolicyCandidate::minutes))
        .forEach(
            policy ->
                canonical
                    .append(policy.scope())
                    .append('\u001f')
                    .append(normalize(policy.targetKey()))
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

  private static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC);
  }
}
