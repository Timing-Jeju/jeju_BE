package com.timingjeju.api.application.profile;

import java.util.UUID;

/**
 * Runtime ownership proof derived from the canonical key admitted by the immutable owner-only
 * Storage INSERT policy. Supabase InfoRenderer user metadata is only a consistency signal.
 */
public record ProfileImageOwnershipProof(UUID ownerId, String generation, String objectKey) {

  public static ProfileImageOwnershipProof fromCanonicalKey(String objectKey) {
    if (objectKey == null) {
      throw ProfileImageException.storageUnavailable();
    }
    String[] segments = objectKey.split("/", -1);
    if (segments.length != 3 || !"profile".equals(segments[1])) {
      throw ProfileImageException.storageUnavailable();
    }
    try {
      UUID owner = UUID.fromString(segments[0]);
      UUID generation = UUID.fromString(segments[2]);
      if (!owner.toString().equals(segments[0]) || !generation.toString().equals(segments[2])) {
        throw ProfileImageException.storageUnavailable();
      }
      return new ProfileImageOwnershipProof(owner, generation.toString(), objectKey);
    } catch (IllegalArgumentException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }
}
