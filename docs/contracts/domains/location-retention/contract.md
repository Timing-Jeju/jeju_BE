# 위치정보 수집·보존·삭제 정책 계약

Issue #73의 실행 가능한 canonical 정책은 같은 디렉터리의 `contract.json`입니다. 이 문서는 Product가 확정한 목적·정밀도·보존·redaction 값을 설명하지만 법률 자문을 대신하지 않으며, 구체 기간은 **법률기관의 승인값이 아니다**.

## 개발과 공개 출시의 분리

- 후속 기능의 개발과 내부 QA는 허용한다.
- production default-off를 유지한다.
- 공개 활성화는 별도 Release gate인 Issue #168의 신고·Privacy/Legal·약관·스토어·동의 UI 증거가 모두 갖춰진 뒤에만 검토한다.
- 코드와 문서는 신고가 끝났거나 법률 적합성이 확인됐다고 추정하지 않는다.

## 동의와 기능 matrix

동의 문서 type은 `location`, 초기 version은 `2026-08-11.v1`이다. live state, execution event, 빈 시간 추천, 복구안, live 재계산, 출발 알림은 최신 required 동의가 없으면 `LOCATION_CONSENT_REQUIRED`로 차단한다. 장소 검색·상세와 여행·일정 수동 CRUD는 위치 동의 없이 사용할 수 있다. 철회는 새 위치 처리를 즉시 차단하고 기존 위치 redaction을 예약한다.

## 처리와 보존

| 대상 | 저장 | 정밀도 | cutoff |
| --- | --- | --- | --- |
| execution event 위치 | 허용 | WGS84, accuracy 100m 이하 | 여행 종료+7일과 마지막 이벤트+14일 중 빠른 시각 |
| live snapshot 현재 위치 | 허용 | WGS84, accuracy 100m 이하 | 여행 종료+24시간과 생성+72시간 중 빠른 시각 |
| 비동기 command 위치 | 제한 허용 | 100m grid 또는 place/stop ID 우선 | terminal+24시간과 여행 종료+24시간 중 빠른 시각 |
| FastAPI MCP 입력 | DB 저장 금지 | grid/place/stop ID 또는 이동 facts | 요청 종료 시 폐기 |
| 로그·metric·trace·Problem Details | 저장 금지 | 위치·accuracy 원문 없음 | emit 전 거부 |

아직 발생하지 않은 종료 시각이나 terminal 시각은 후보에서 제외한다. 도착한 anchor만으로 cutoff를 계산하고, 후보가 하나도 없으면 `not-due`다. 이후 anchor가 실제 도착하면 같은 정책으로 다시 계산한다. 모든 cutoff는 `now >= cutoff`에서 만료된다. TTL job, 실제 API와 DB migration은 이 Issue 범위가 아니다.

## 삭제 순서

철회는 철회 기록 → 기능 중지 → 신규 접수 거부 → live/event/command redaction → 비위치 감사 보존 순서다. 여행 종료·삭제와 탈퇴도 위치를 먼저 제거하고, 필요한 최소 비위치 run/event 계보만 보존한다. 외부 공용 TourAPI/TAGO/KMA facts는 사용자 위치가 아니므로 사용자 위치 redaction 대상과 구분한다.

`deletionFieldActions`는 철회·여행 종료·여행 삭제·탈퇴 각각의 `redact`, `delete`, `preserve` field path를 닫힌 allowlist로 고정한다. 후속 구현은 이 목록 밖 필드를 임의로 보존하거나 삭제하지 않고, 계약 version 변경 없이 action을 완화하지 않는다.

## 보안

fixture·로그·metric·trace·오류에 실제 좌표, accuracy, 이동 경로, token/key, 원문 provider payload, prompt/completion을 넣지 않는다. 이 계약은 공개 API나 migration을 추가하지 않는다.

브라우저의 DB 직접 접근과 사용자 간 위치 접근은 금지한다. 후속 Spring API는 canonical JWT `sub`와 소유 여행 권한을 먼저 검증해야 하고, `service_role`은 서버 내부 redaction/retention job에만 사용한다. FastAPI는 DB·JWT·credential을 받지 않고 bounded redacted facts만 받는다.

```bash
python3 -m unittest scripts.tests.test_location_retention_policy_contract scripts.tests.test_contract_suite_integration
python3 scripts/validate_location_retention_contract.py
```
