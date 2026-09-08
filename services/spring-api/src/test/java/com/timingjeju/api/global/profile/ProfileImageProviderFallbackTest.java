package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.profile.ProfileImageSource;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageProviderFallbackTest {

  @Test
  void active_HTTPS_provider는_google_kakao_naver_순서로_선택한다() {
    assertThat(
            ProfileImageProviderFallback.choose(
                List.of(
                    new ProfileImageProviderFallback.Candidate(
                        "naver", "https://images.example.invalid/naver.png"),
                    new ProfileImageProviderFallback.Candidate(
                        "kakao", "https://images.example.invalid/kakao.png"),
                    new ProfileImageProviderFallback.Candidate(
                        "google", "https://images.example.invalid/google.png"))))
        .contains("https://images.example.invalid/google.png");
  }

  @Test
  void email_unknown_non_https_userinfo와_malformed_candidate를_제외하고_다음_provider를_선택한다() {
    assertThat(
            ProfileImageProviderFallback.choose(
                List.of(
                    new ProfileImageProviderFallback.Candidate(
                        "email", "https://images.example.invalid/email.png"),
                    new ProfileImageProviderFallback.Candidate(
                        "google", "http://images.example.invalid/google.png"),
                    new ProfileImageProviderFallback.Candidate(
                        "kakao", "https://user@images.example.invalid/kakao.png"),
                    new ProfileImageProviderFallback.Candidate("naver", "not a url"),
                    new ProfileImageProviderFallback.Candidate(
                        "naver", "https://images.example.invalid/naver.png"))))
        .contains("https://images.example.invalid/naver.png");
  }

  @Test
  void 유효한_active_provider_candidate가_없으면_none이다() {
    assertThat(
            ProfileImageProviderFallback.choose(
                List.of(
                    new ProfileImageProviderFallback.Candidate("google", null),
                    new ProfileImageProviderFallback.Candidate(
                        "kakao", "http://insecure.invalid"))))
        .isEmpty();
  }

  @Test
  void persisted_none이어도_새_social_image가_있으면_effective_provider다() {
    ProfileImageProviderFallback.Selection selection =
        ProfileImageProviderFallback.resolve(
            List.of(
                new ProfileImageProviderFallback.Candidate(
                    "google", "https://images.example.invalid/google.png")),
            null);

    assertThat(selection.source()).isEqualTo(ProfileImageSource.PROVIDER);
    assertThat(selection.imageUrl()).isEqualTo("https://images.example.invalid/google.png");
  }

  @Test
  void persisted_provider여도_social과_legacy가_모두_invalid면_effective_none이다() {
    ProfileImageProviderFallback.Selection selection =
        ProfileImageProviderFallback.resolve(
            List.of(
                new ProfileImageProviderFallback.Candidate(
                    "google", "http://images.example.invalid/google.png")),
            "https://user@legacy.example.invalid/image.png");

    assertThat(selection.source()).isEqualTo(ProfileImageSource.NONE);
    assertThat(selection.imageUrl()).isNull();
  }

  @Test
  void legacy_only_HTTPS는_compatibility_provider이고_social이_생기면_social이_우선한다() {
    String legacy = "https://legacy.example.invalid/image.png";
    assertThat(ProfileImageProviderFallback.resolve(List.of(), legacy))
        .isEqualTo(new ProfileImageProviderFallback.Selection(legacy, ProfileImageSource.PROVIDER));

    assertThat(
            ProfileImageProviderFallback.resolve(
                List.of(
                    new ProfileImageProviderFallback.Candidate(
                        "kakao", "https://images.example.invalid/kakao.png")),
                legacy))
        .isEqualTo(
            new ProfileImageProviderFallback.Selection(
                "https://images.example.invalid/kakao.png", ProfileImageSource.PROVIDER));
  }

  @Test
  void public_URL_schema의_ASCII_2040자_tail경계와_query_fragment를_정확히_허용한다() {
    String maximum = "https://a/" + "a".repeat(2038);
    String queryAndFragment = "https://images.example.invalid/p.png?v=1#preview";

    assertThat(ProfileImageProviderFallback.resolve(List.of(), maximum).imageUrl())
        .isEqualTo(maximum);
    assertThat(ProfileImageProviderFallback.resolve(List.of(), queryAndFragment).imageUrl())
        .isEqualTo(queryAndFragment);
  }

  @Test
  void public_URL_schema의_2040자_tail초과와_non_ASCII_path를_거부한다() {
    String overMaximum = "https://a/" + "a".repeat(2039);
    String unicodePath = "https://images.example.invalid/제주.png";

    assertThat(ProfileImageProviderFallback.resolve(List.of(), overMaximum).source())
        .isEqualTo(ProfileImageSource.NONE);
    assertThat(ProfileImageProviderFallback.resolve(List.of(), unicodePath).source())
        .isEqualTo(ProfileImageSource.NONE);
  }
}
