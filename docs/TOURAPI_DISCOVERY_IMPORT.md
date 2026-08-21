# TourAPI 후보 보강 importer

Issue #75는 공개 Controller 없이 Spring application command로 `locationBasedList2`,
`searchKeyword2`, `searchStay2` 후보를 DB cache에 보강한다. 외부 API 호출과 DB write는
Spring이 소유하며 FastAPI, 프론트, 공개 API에 service key나 원문 응답을 전달하지 않는다.

## command와 쿼터

- 위치 command는 제주 경계(경도 126.0~127.0, 위도 33.0~34.0), 반경 1~20,000m만 허용한다.
- 키워드는 Unicode NFC, 양끝 공백 제거, 연속 공백 한 칸으로 정규화한다.
- 숙박 command는 응답 `contenttypeid=32`만 정규화한다.
- 수동 command는 실행당 1~100 page로 제한한다.
- scheduler 정책은 기본 비활성이고 실행당 10 page, 일일 100 provider call, 최소 1시간 간격이다.
  활성화·호출량 계측을 연결하는 운영 job은 이 정책의 두 한도를 모두 통과해야 한다.

별도 환경변수는 추가하지 않는다. 공통 `TOUR_API_ENABLED`, `TOUR_API_API_KEY`,
`TOUR_API_BASE_URL`, connect/read timeout만 사용한다. 실제 credential을 fixture, 문서, 로그에
기록하지 않는다.

## 적재와 계보

세 operation은 provider/service/contentid natural key를 공유하므로 `tour_places`와
`tour_place_sources`를 한 행으로 합친다. 각 operation의 request fingerprint, raw snapshot,
import run은 `tour_api_operation_provenance`에 append-only로 남겨 마지막 포인터로 축소하지 않는다.
키워드는 NFC query를 `place_aliases(alias_type=keyword)`에 저장하고 alias 행도 같은 snapshot/run
provenance를 가진다.

페이지별 원문을 먼저 저장한 뒤 JSON envelope, page/total, 제주 법정동·좌표, contentid 중복과
숙박 type을 검증한다. 일시 provider 실패는 같은 page를 최대 3회 재시도한다. complete manifest만
정규화 write, run terminal 전이, operation별 `data_import_checkpoints` CAS를 한 transaction에서
수행한다. 동일 watermark의 다른 manifest, stale checkpoint writer, provenance 불일치는 transaction
전체를 rollback한다. 같은 idempotency key의 exact replay는 provider, snapshot, normalized row와
checkpoint를 다시 쓰지 않는다.

`place_aliases`와 수집 내부 테이블은 RLS를 유지하고 `anon`, `authenticated` 직접 권한을 허용하지
않는다. 서버 전용 `service_role`만 기존 적재 권한을 사용한다. query, raw payload, credential은
애플리케이션 로그에 남기지 않는다.
