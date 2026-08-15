# TourAPI 기준 코드 동기화

## 경계

Issue #25는 공개 API나 scheduler를 추가하지 않고 내부 `ReferenceCodeSyncService` 수동 command만 제공합니다. 장소 목록 적재, legacy 코드 삭제, 관리자 UI와 실제 운영 credential 검증은 범위 밖입니다.

| 응답 operation | provenance operation | 정규화 타입 |
| --- | --- | --- |
| `ldongCode2` | `areaCode2` | `ldong-region`, `ldong-signgu` |
| `lclsSystmCode2` | `categoryCode2` | `lcls-1`, `lcls-2`, `lcls-3` |

법정동 응답은 제주특별자치도 코드 `50`과 그 부모를 공유하는 시군구만 저장합니다. 관광 분류는 1·2·3단계 부모가 같은 batch에 존재해야 합니다. 빈 items, 오류 envelope, 필수 code/name 누락과 끊어진 부모 관계는 원문 provider message를 노출하지 않고 전체 응답을 거부합니다. JSON과 XML parser는 같은 정규화 계약을 사용합니다.

JSON은 envelope를 포함한 모든 object에서 중복 key를 거부합니다. XML은 namespace 없는 exact envelope와 item의 고유한 direct field만 허용합니다. XML scalar는 text와 CDATA node만 이어 붙이며 element, comment, processing instruction 같은 child node가 섞인 mixed content는 거부합니다. 두 형식 모두 duplicate와 nested scalar spoof를 전체 응답 실패로 처리합니다.

## 실행과 계보

한 command는 한 operation의 별도 `data_import_runs`를 시작합니다. 외부 응답은 공통 `SnapshotStoreService`에서 redaction한 뒤 `external_api_snapshots`에 저장하고, parser 성공 후에만 `parsed`로 전환합니다. 정규화 writer는 #107의 `TourApiProvenanceWriter`를 호출해 다음 작업을 같은 transaction에서 수행합니다.

1. active operation과 snapshot/run/request fingerprint 일치 검증
2. `external_reference_codes` 유효기간별 멱등 upsert
3. `tour_api_operation_provenance` 기록

같은 snapshot의 동일 값 replay는 UUID, 업무 값, `updated_at`과 행 수를 바꾸지 않습니다. 같은 snapshot으로 값을 바꾸거나 겹치는 유효기간을 추가하면 DB 계보·exclusion constraint가 전체 batch를 rollback합니다. 새 정규화 migration이나 Flyway는 추가하지 않으며 기존 canonical `supabase/migrations` 계약만 사용합니다.

같은 idempotency key가 이미 시작된 경우 provider, snapshot, 정규화 DB와 terminal mutation을 다시 실행하지 않고 명시적인 replay 결과를 반환합니다. 실패 run의 재시도는 기존 run을 재실행하지 않고 #22의 parent run과 새 idempotency key 계약을 따릅니다.

## 보안과 운영

- client는 고정 base URL 설정 아래 `ldongCode2` 또는 `lclsSystmCode2` 상대 path만 선택합니다.
- query에는 페이지, 응답 형식, 앱 식별자와 제주 범위만 포함하며 `serviceKey`는 공통 credential adapter가 주입합니다.
- API key, Authorization, raw URL, provider 오류 원문과 사용자 데이터는 run metadata, 예외와 로그에 남기지 않습니다.
- 기본 검증은 deterministic JSON/XML fixture를 사용하며 실제 외부 API나 credential을 요구하지 않습니다.
