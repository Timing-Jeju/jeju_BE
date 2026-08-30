package com.timingjeju.api.domain.savedplaces.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.savedplaces.model.SavedPlaceHttpSnapshot;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SavedPlaceHttpSnapshotTest {
  @Test
  void response_body는_직렬화기_변경과_호출자_mutation에서_격리된_immutable_bytes다() {
    byte[] original = "{\"memo\":\"원본\"}".getBytes(StandardCharsets.UTF_8);
    var snapshot =
        new SavedPlaceHttpSnapshot(
            201,
            "application/json",
            "/api/v1/me/saved-places/20000000-0000-0000-0000-000000000003",
            "\"etag\"",
            original);

    original[0] = '!';
    byte[] returned = snapshot.body();
    returned[1] = '!';

    assertThat(new String(snapshot.body(), StandardCharsets.UTF_8)).isEqualTo("{\"memo\":\"원본\"}");
  }
}
