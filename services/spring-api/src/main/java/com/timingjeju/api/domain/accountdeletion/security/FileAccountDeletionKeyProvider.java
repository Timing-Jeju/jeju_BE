package com.timingjeju.api.domain.accountdeletion.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class FileAccountDeletionKeyProvider implements AccountDeletionKeyProvider {
  private final Path descriptor;

  public FileAccountDeletionKeyProvider(Path descriptor) {
    this.descriptor = descriptor.toAbsolutePath().normalize();
  }

  @Override
  public AccountDeletionKeySet load() {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(descriptor)) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("회원 탈퇴 Secret Manager descriptor를 읽을 수 없습니다.");
    }
    String activeVersion = properties.getProperty("active-version");
    Map<String, SecretKey> keys = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith("key.")) continue;
      String version = name.substring("key.".length());
      byte[] material = decode(properties.getProperty(name));
      keys.put(version, new SecretKeySpec(material, "AES"));
    }
    return new AccountDeletionKeySet(activeVersion, keys);
  }

  private static byte[] decode(String encoded) {
    try {
      byte[] material = Base64.getDecoder().decode(encoded);
      if (material.length != 32) throw new IllegalArgumentException();
      return material;
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException("회원 탈퇴 descriptor의 key version이 유효하지 않습니다.");
    }
  }
}
