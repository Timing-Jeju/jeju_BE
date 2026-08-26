# 푸시 기기·출발 알림 설정 계약

인증된 사용자의 canonical Supabase JWT `sub` UUID만 소유권 기준으로 사용한다. 앱이 만든 lowercase canonical UUID를 `deviceId`로 사용하며 광고 ID와 하드웨어 ID는 받지 않는다.

registration token은 printable ASCII/UTF-8 1..4096 bytes만 받고 Spring application 계층에서 AES-256-GCM으로 암호화한 뒤 SHA-256 fingerprint와 함께 저장한다. 암호화 실패는 cause 없이 `503 PUSH_NOTIFICATION_DATA_UNAVAILABLE`로 변환하고 store를 호출하지 않는다. 암호화 키는 `PUSH_TOKEN_ENCRYPTION_KEY` 환경 변수 또는 secret manager로만 주입한다. token 원문·ciphertext·fingerprint와 crypto 실패 원인은 API 응답, Problem Details, 로그, trace, metric, 계약 fixture에 포함하지 않는다.

기기 locale은 canonical BCP 47, 2..35자만 허용하며 extension/private-use를 포함한다. DTO, application, Swagger와 PostgreSQL CHECK가 같은 정책을 사용하고 canonical case가 아닌 값은 crypto/store 전에 400으로 거부한다.

알림 설정의 최초 값은 `nextDestinationDepartureEnabled=false`, `safetyBufferMinutes=10`이다. 안전 여유는 JSON integer `0..120` inclusive만 허용한다. 실제 발송 eligibility는 활성 기기, OS 권한 `GRANTED`, 서버 opt-in, 현재 유효한 최신 required 위치 동의를 예약 시점과 발송 직전에 모두 다시 검사한다. 위치 문서는 #19와 동일하게 사용자 profile locale 후보를 우선하고 없을 때만 `ko-KR`로 fallback하며, `effectiveAt DESC → semanticVersion DESC → documentId ASC`로 선택한다. profile, 후보 문서, 최종 동의·기기 조회는 `REPEATABLE READ` transaction의 첫 DB read 시점 snapshot 하나를 공유하며, 그 뒤 commit된 locale·문서·동의 변경은 다음 eligibility 호출부터 반영한다. 사용한 위치 동의 문서 ID/version은 예약 audit snapshot에 보존한다.

`DELETE /api/v1/me/push-devices/{deviceId}`는 로그아웃 시 단일 기기 해제다. 회원 탈퇴 접수는 #61/#106 owner가 additive `PushNotificationWithdrawalBoundary`를 같은 intake transaction에서 호출해 해당 사용자의 모든 기기를 즉시 비활성화하고 eligibility를 0으로 만든다. 최종 Auth 사용자 삭제 시 두 푸시 테이블은 FK cascade로 정리되며 타 사용자 행은 보존한다.

`push_devices`와 `notification_preferences`는 owner RLS를 활성화한다. `anon`과 `PUBLIC` 권한은 회수한다. `authenticated`의 device 조회는 token 보호 열을 제외한 safe column으로 제한한다. `SECURITY DEFINER`와 Flyway를 추가하지 않는다.
