# 타이밍제주 백엔드 설계 검증 체크리스트 v1.1

## 1. 검증 상태

| 항목 | 상태 | 판정 |
| --- | --- | --- |
| Figma 화면 기능 매핑 | PASS | 캔버스의 와이어프레임/설명 텍스트 기준 핵심 흐름 매핑 완료 |
| PostgreSQL 스키마 실행 | PASS | 신규 DB에서 extension -> schema -> seed -> smoke 순서 통과 |
| 관계/상태 제약 | PASS | 교차 일정 참조, 봉인 버전 수정, 기간 밖 데이터, 숙소 중복 차단 |
| RLS/Data API 경계 | PASS | public 앱 테이블 RLS 적용, 클라이언트 쓰기 권한 제거 |
| Spring REST 계약 | PASS | 화면용 API 45개, 요청/응답/오류/멱등성/동시성 규칙 작성 |
| API별 MCP 직접 호출 분류 | PASS | 전체 45개 중 호출 5개, 미호출 40개, 누락 0개 |
| FastAPI MCP 계약 | PASS | Phase 1 계산 도구 8개 + Phase 2 의도 파싱 도구 1개 정의 |
| Spring-FastAPI wire 계약 | PASS | `/mcp` tools/call, structuredContent, service JWT, 저장 매핑 작성 |
| 외부 API 필드 검증 | PASS | TourAPI/TAGO/KMA 공식 문서 기준 원천값과 계산값 분리 |
| 길찾기 공급자 POC | PENDING | TMAP을 설계 기본값으로 두었으나 키/쿼터/제주 품질 실측 필요 |
| Figma 우측 댓글 스레드 | PASS | 2026-07-21 브라우저에서 미해결 댓글과 답글을 직접 확인해 정책 반영 |

`PASS`는 설계와 로컬 검증 완료, `PENDING`은 구현 전 POC 필요를 뜻한다.

## 2. 확정 제품 결정

- 인증: Supabase Auth의 `kakao`, `naver`, `google`, 이메일/비밀번호를 사용한다.
- 프로필: 별도 아이디와 생년월일은 받지 않는다. 닉네임, 이메일, 프로필 이미지만 앱 프로필로 관리한다.
- 이동수단: `public_transit`, `rental_car`, `taxi`를 복수 선택하고 `priority`와 `primary`로 우선순위를 둔다. `walk`는 구간 이동수단이다.
- AI Phase 1: 구조화된 여행 조건을 Spring이 FastAPI MCP에 보내고 Day 일정 전체를 한 번에 생성한다.
- AI Phase 2: 대화형 입력은 후속 구현이지만 `parse_trip_intent` 계약과 대화 저장 구조는 지금 확보한다.
- 저장소: Supabase Postgres/PostGIS 하나를 공유하되 FastAPI가 직접 접속하지 않는다.

## 3. 서비스 책임 경계

| 책임 | Supabase Auth | Spring Boot | FastAPI MCP | Postgres |
| --- | --- | --- | --- | --- |
| 소셜/이메일 로그인, OTP, 비밀번호 재설정 | OWNER | JWT 검증 | 금지 | `auth.users` 원본 |
| 프로필/약관/관심 장소/여행 CRUD | - | OWNER | 금지 | 영속화/RLS |
| TourAPI/TAGO/KMA/길찾기 호출 | - | OWNER | 금지 | 정규화 cache/snapshot |
| 계산 입력 facts 조립 및 검증 | - | OWNER | 입력 검증 | 원천/사용자 데이터 조회 |
| Day 생성/수정/가능성/추천/복구/라이브 재계산 | - | 호출/timeout/저장 | OWNER | 실행과 결과 이력 |
| 일정 후보 적용과 동시성 제어 | - | OWNER | 금지 | 트랜잭션/제약 |
| JWT, refresh token, provider access token 저장 | Supabase Auth | 금지 | 금지 | 앱 public schema에 저장 금지 |

FastAPI는 `service_role` DB 키, Supabase JWT, 외부 API 키를 받지 않는다. Spring이 ID가 포함된 정규화 facts를 보내고 FastAPI는 입력에 없는 ID를 결과로 만들 수 없다.

## 4. Figma 기능 커버리지

| Figma 기능 | 소유 API | DB | 계산 | 상태/메모 |
| --- | --- | --- | --- | --- |
| 로그인/회원가입/소셜 로그인 | Supabase Auth SDK | `auth.users`, `user_profiles`, `social_accounts` | - | PASS |
| 이메일 인증/비밀번호 재설정 | Supabase Auth SDK | Auth 내부 | - | PASS, Spring endpoint를 만들지 않음 |
| 이용약관/개인정보/위치 동의 | Spring `/legal-documents`, `/me/consents` | `legal_documents`, `user_consents` | - | PASS |
| 마이페이지/회원탈퇴 | Spring `/me`, `/trips` | profile/trip aggregate | - | PASS |
| 지도 장소 검색/카테고리/내 근처 | Spring `/places` | `tour_places`, images, PostGIS | - | PASS |
| 관심 장소만 보기 | Spring `/saved-places` + places filter | `saved_places` | - | PASS |
| 장소 상세/이미지/이용정보/주변 정류장 | Spring `/places/{placeId}` | place detail/image/hour/stop link | - | PASS |
| 장소 메모/필수 여부/태그/희망 Day | Spring saved-place CRUD | `saved_places`, `trip_place_preferences` | - | PASS |
| 지도 위 Day 경로/미니 일정/상태 | Spring `/schedule`, `/live-state` | active version/items/legs/risk | - | PASS |
| 여행 기간/권역/속도/이동수단 | Spring trip/preference APIs | trip input tables | - | PASS |
| 항공/선박 도착·출발 | Spring transport-event APIs | `trip_transport_events` | FastAPI 입력 facts | PASS |
| 복수 숙소 입력/수정/삭제 | Spring accommodation APIs | `trip_accommodations` | FastAPI 입력 facts | PASS |
| 식당/관광지/카페 선택 또는 생략 | Spring place-preferences API | `trip_place_preferences` | 생성 조건 | PASS |
| Day별 AI 일정 일괄 생성 | Spring generation-runs | generation/schedule tables | `generate_day_itinerary` | PASS |
| 일정 항목 추가/수정/삭제/순서/Day 이동 | Spring schedule mutation APIs | immutable schedule versions | optional revise/validate | PASS |
| 가능성 안전/주의/위험과 이유 | Spring feasibility-runs | compute/risk/weather impacts | `calculate_feasibility` | PASS |
| 이동 구간 시간/정류장/환승/요금 | Spring `/legs/{legId}` | legs + mobility/transit snapshots | 계산 facts | PASS |
| 빈 시간 장소 추천과 재검사 | Spring spare-time-runs/apply flow | recommendations/new version | `recommend_spare_time` | PASS |
| 기존 일정 유지 수정안/교통수단 대안 | Spring recovery-runs | recovery options/diffs | `generate_recovery_options` | PASS |
| 여행 당일 현재 위치/출발/도착/놓침 | Spring live APIs | progress/events/live snapshots | `recalculate_live_state` | PASS |
| 실시간 값 변경 표시 | Spring live-state response | snapshot `observedAt/expiresAt/stale` | confidence/reason | PASS |

## 5. Figma 문구에서 확인한 계약

- 상태는 API `safe`, `caution`, `danger`; DB 계산 level `green`, `yellow`, `red`; UI `안전`, `주의`, `위험`으로 매핑한다.
- 가능성 화면은 기존 일정을 최대한 유지하면서 대체 교통수단과 추가 비용을 비교해야 한다.
- 이동 구간에는 출발/환승/도착 정류장, 이동 시간, 체류 시간, 도보 거리, 요금, 위험 이유가 필요하다.
- 라이브 카드의 버스/이동 시간은 최신 snapshot에 따라 바뀌므로 모든 응답에 관측 시각과 stale 여부가 필요하다.
- 장소 카드는 `category`, `recommendedStayMinutes`, `regionLabel`, `memo`, `saved`, 사용자 태그를 노출한다.
- 추천 장소 적용 후에는 새 일정 버전을 만든 뒤 가능성 검사를 다시 실행한다.

### 5.1 댓글 스레드에서 확정한 보완

| 댓글 요구 | 반영 결과 |
| --- | --- |
| AI가 Day 단위로 한 번에 만들고 사용자가 적용 | `generation-runs` 비동기 후보와 명시적 apply 분리 |
| 기존/아래 일정을 덮지 않고 새 제안 제공 | 불변 일정 버전과 candidate 상태로 분리 |
| 후보 선택 전에 전체 일정, 체류시간, 대기 이유 확인 | generation run 응답의 `previewDays`, `stayMinutes`, `reasonCodes`, `scheduleUrl` |
| 사용자가 순서 변경/삭제 후 확정 | Spring schedule mutation API가 새 draft/version 생성 |
| 자동 복구가 이미 최적화된 순서를 불필요하게 변경하지 않음 | 복구 enum에서 `reorder` 제외, `preserveOriginalOrder=true` |
| 일정 검토에는 위치와 시간이 필수 | candidate/active 봉인 trigger가 위치·시간·체류·인접 leg 검사 |
| 장소만 저장하는 흐름은 일정과 분리 | `saved_places`는 시간 없이 허용, `trip_items` 봉인 시 필수값 검증 |
| 일정 생성 방향/권역을 AI 입력으로 사용 | `trip_preferences`의 region/start/end와 구조화 MCP 입력에 포함 |
| 일정 지연 시 놓친 장소를 다른 Day로 이동 | 복구 `move_day` option/diff 지원 |
| 자연어 입력과 구조화 입력은 분리 | Phase 1 structured, Phase 2 `parse_trip_intent` 계약 |
| 마이페이지 필요 범위 확인 | 프로필, 로그인 제공자, 내 여행, 저장 장소, 동의, 탈퇴 API 조합 명시 |

## 6. 발견된 보완 사항

### 구현 전 필수

- [ ] TMAP 대중교통/자동차/보행자 API 키와 상용 쿼터를 확보한다.
- [ ] 제주 10개 대표 A-B 구간에서 경로 누락률, duration, fare, walk segment를 실측한다.
- [ ] 위치정보 이용약관 버전과 여행 당일 위치 수집/보존 기간을 법무 기준으로 확정한다.
- [ ] 외부 API 키, Supabase service role, MCP 내부 인증키의 secret 관리 방식을 확정한다.

### 구현 중 필수

- [ ] Supabase Auth redirect URL과 Kakao/Naver/Google provider 설정을 환경별로 검증한다.
- [ ] 이메일 중복/소셜 계정 연결 충돌 UX를 결정하고 Auth webhook/profile upsert 테스트를 만든다.
- [ ] Spring OpenAPI 문서를 본 계약의 JSON 필드와 contract test로 고정한다.
- [ ] Spring-FastAPI 호출에 `contractVersion`, `requestId`, `inputHash`, timeout, retry 정책을 적용한다.
- [ ] 비동기 run 중복 요청은 `Idempotency-Key`로 같은 run을 반환한다.
- [ ] 후보 적용은 `expectedActiveScheduleVersionId` 불일치 시 `409`를 반환한다.
- [ ] 위치 좌표는 live 계산에 필요한 최소 기간만 보존하고 정밀 좌표 로그의 TTL 삭제 작업을 둔다.

### Phase 2

- [ ] 대화 메시지의 개인정보 마스킹, 보존 기간, 삭제 연쇄 정책을 확정한다.
- [ ] `parse_trip_intent`가 만든 값은 사용자 확인 전 확정 여행 조건으로 저장하지 않는다.
- [ ] LLM 설명 실패 시 reason code 기반 한국어 template을 제공한다.

## 7. DB 검증

| 검증 | 기대 결과 | 현재 |
| --- | --- | --- |
| 46개 public 앱 테이블 + `auth.users` shim 생성 | 성공 | PASS |
| `pgcrypto`, `postgis`, `btree_gist` | 설치 | PASS |
| 모든 public 앱 테이블 RLS | enabled | PASS |
| FK 선두 인덱스 | 누락 0 | PASS |
| 사용자 클라이언트 public write grant/policy | 0 | PASS |
| 외부 원천 식별자 중복 | unique/upsert로 차단 | PASS |
| 다른 여행/버전/Day 항목 참조 | FK/trigger로 실패 | PASS |
| active/sealed 일정 항목 직접 수정 | 실패 | PASS |
| 위치/시간/체류/leg가 불완전한 draft 봉인 | 실패 | PASS |
| leg 시간차/duration/구성시간 합계 불일치 | 실패 | PASS |
| candidate/active 버전의 인접 item 연결 leg 누락 | 0 | PASS |
| 복구 enum의 자동 `reorder` 허용 | 0 | PASS |
| 여행 기간 밖 Day/교통/숙소 | 실패 | PASS |
| 같은 여행 숙소 날짜 중복 | exclusion constraint로 실패 | PASS |
| 진행 상태 terminal -> 이전 상태 회귀 | 실패 | PASS |
| 실행 이벤트 update/delete | 실패 | PASS |
| 일정 후보 원자 적용 | 한 active version만 존재 | PASS |
| PostGIS 최근 정류장 검색 | 거리순 결과 | PASS |

실행 명령:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f db/init/001_extensions.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f db/init/002_schema.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f db/init/003_seed_fixtures.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f db/queries/smoke_check.sql
```

## 8. API 계약 검증

- [x] 모든 화면 endpoint는 인증 여부와 owner가 있다.
- [x] 모든 상세 endpoint는 보기 좋게 들여쓴 Request/Response JSON을 가진다.
- [x] 목록은 cursor pagination을 사용한다.
- [x] 오류는 RFC 7807 `application/problem+json`을 사용한다.
- [x] 생성/계산/적용 요청은 멱등성과 재시도 규칙을 가진다.
- [x] 오래 걸리는 생성/계산은 `202 runId` + polling 계약이다.
- [x] 사용자 일정 변경은 새 immutable version을 만든다.
- [x] FastAPI 결과는 Spring이 검증한 뒤 트랜잭션으로 저장한다.
- [x] 공개 API별 MCP tool, Spring 입력 원천, FastAPI 결과와 DB 저장 위치를 매핑했다.
- [x] 공개 API 45개의 `MCP 호출 여부`를 직접 호출 5개와 미호출 40개로 분류했으며 누락이 없다.
- [x] MCP는 Stateless Streamable HTTP `/mcp`와 `result.structuredContent`를 사용한다.
- [x] Supabase 사용자 JWT/토큰/PII를 MCP payload로 전달하지 않는다.
- [ ] Spring MockMvc/OpenAPI contract test는 구현 저장소에서 작성한다.
- [ ] FastAPI JSON Schema golden test는 [jeju_AI 저장소](https://github.com/Timing-Jeju/jeju_AI)에서 작성한다.

## 9. 외부 API 검증

| 공급자 | 가져오는 값 | 가져오지 못하는 값 | 처리 |
| --- | --- | --- | --- |
| TourAPI KorService2 | 장소, 주소, 좌표, 이미지, 개요, 유형별 이용정보 | 추천 체류, 사용자 메모, 가능성 | curated/user/computed로 분리 |
| TAGO | 정류장, 노선, 경유 순서, 실시간 도착 초/잔여 정류장 | 완성 A-B 경로, 모든 정확한 시간표 | TMAP/보조 데이터 + FastAPI |
| KMA 단기예보 | 기온, 강수, 하늘, 습도, 풍속 | 일정 영향/위험도 | FastAPI 계산 |
| TMAP 기본안 | 대중교통/자동차/보행 경로, 시간, 일부 요금 | 앱 정책 위험도/복구 | 정규화 snapshot 후 FastAPI |

외부 응답을 그대로 프론트에 전달하지 않는다. Spring adapter가 내부 schema로 정규화하고 `observedAt`, `expiresAt`, `stale`, `provider`를 붙인다.

## 10. 장애/동시성 시나리오

| 시나리오 | 기대 동작 |
| --- | --- |
| TourAPI 실패 | 최근 7일 cache를 stale 표시하거나 명시적 외부 장애 반환 |
| TAGO 실시간 실패 | 최대 2분 snapshot + `STALE_TRANSIT_DATA`, confidence 하향 |
| KMA 실패 | 직전 발표 1회 fallback + `STALE_WEATHER_DATA` |
| 길찾기 실패 | 허용 범위 내 cache, 도보만 보수 추정; 허위 경로 생성 금지 |
| FastAPI timeout | run `failed`, 재시도 가능 여부와 오류 코드 저장 |
| 동일 계산 중복 제출 | 같은 idempotency key면 기존 run 반환 |
| 두 기기에서 일정 적용 | 먼저 적용한 요청 성공, 뒤 요청 `409 SCHEDULE_VERSION_CONFLICT` |
| live event 재전송 | event idempotency key로 중복 진행 상태 방지 |
| 계정 삭제 | Auth 삭제 요청과 앱 데이터 삭제/익명화 job 상태 추적 |

## 11. 최종 판정

RDB, Spring REST, FastAPI MCP의 경계와 Figma 화면/댓글 기능은 구현 가능한 수준이다. 스키마는 실제 PostgreSQL에서 양/음성 검증을 통과했다. 구현 착수 전 남은 설계 리스크는 `길찾기 공급자 POC`와 `위치정보 보존 정책` 두 가지이며, 나머지는 구현 테스트 항목이다.
