# #224 위치 비수집 schema v2 전환 검증

상태: 구현·검증 중. 이 문서는 운영 적용 승인이나 전체 품질 gate 완료 기록이 아니다.
현재 로컬 Java는 v2만 읽으며 DB migration020을 019 다음 init058로 등록했다. 로컬 PG16/17 대상 검증 결과는 아래에 구분한다.
따라서 이 worktree의 애플리케이션을 기존 DB에 배포하지 않는다.

## 호환성

| 구성 | 입력 계약 | 적용 조건 |
| --- | --- | --- |
| develop73cb2b9 및 #241 | v1 저장 구조, 017/018 신규 위치 차단 | #241의 최신 전체 검증은 재실행 필요 |
| #224 Java 변경 | v2, 위치 열·cleanup API 없음 | DB020 및 동일 SHA 전체 gate 필요 |
| AI release0.7.0 | 과거 MCP schema | 위치 필드를 허용하는 schema가 있어 BE 사전 차단만으로 0.8 완료를 주장하지 않음 |
| MCP0.8 및 Spring facts | 후속 release manifest | Pydantic 생성 schema·checksum·실제 wire 교차 검증 필요 |

## 전환 전 검증 조건

1. #241의019/init057이 최신 develop에 병합된 뒤 020/init058의 선행 SHA와 manifest checksum을 다시 확인한다. 현재 로컬 사전 반영을 선행 PR 병합으로 간주하지 않는다.
2. 신규 생성·평가 접수와 MCP 기능을 비활성으로 두고 기존 worker를 drain한다. DB의 running run이 남아 있으면 전환을 중단한다. 만료 lease를 임의 삭제하거나 성공으로 바꾸지 않는다.
3. 동일 transaction에서 017이 잠근 사용자·입력·부모·일정·결과·audit 표를 잠근다. 018 marker와 잔여량0을 다시 확인한다.
4. 모든 기존 input의 v1 closed schema·원래 hash·owner/trip/base/parent 계보를 먼저 입증한다. 입력이 없거나 다른 hash가 있으면 자동 복구하지 않는다.
5. 독립 revision request hash, MCP wire hash, 멱등 receipt는 command hash로 추정해 재작성하지 않는다. 018의 미분류 잔여가 있으면 사전 감사가 필요하다.

## 원자적 변경의 검증 항목

- 증명된 무위치 v1 input만 위치 없는 v2 문서로 명시 변환한다. compute parent의 input_hash는 같은 transaction에서 함께 갱신하고, 생성 parent에 존재하지 않는 input_hash를 가정하지 않는다.
- v2 lineage trigger를 유지하고 deferred 검증을 완료한 뒤 열을 제거한다. migration을 위해 잠시 비활성화한 정확한 immutable trigger는 commit 전에 다시 활성화한다.
- compute 위치7열, event 위치열, live 현재 위치/장소열과 정확한 dependent index·constraint·함수만 제거한다. CASCADE로 알 수 없는 dependency를 지우지 않는다.
- JSON 위치 유입 차단, event append-only, worker fencing, owner RLS와 ACL은 유지한다. 공개 장소·정류장·기상 격자와 출처가 확인된 계획 anchor, 여행 종료 시각은 제거하지 않는다.
- v1 함수 signature를 남겨 우회로를 만들지 않는다. 새 hash 함수는 v2 closed schema만 수락하고 raw 인수를 오류 메시지에 포함하지 않는다.
- 중간 오류·추가 dependency·동시 쓰기·미분류 활성 데이터에서 전체 transaction이 rollback되며 원래 catalog·값·hash가 유지되는지 PG16/17에서 검증한다.
- event 테이블을 읽거나 해당 테이블의 trigger인 함수는 검토한 signature·본문 SHA256만 허용한다. 공개 장소와 event를 JOIN하는 미분류 함수도 별도 감사 전에는 전환을 중단한다. event 의존성 없는 공개 장소 location 함수는 보존한다. 이는 일반 SQL 분석기로 임의 동적 SQL의 안전을 증명한다는 뜻이 아니다.

## 실제 인수 기록

- 로컬 PG16/17: 선행 schema에서020으로 전환하는 정상2개 및 미분류 의존성·활성 상태 등의 차단20개가 통과했다. 입력/부모 hash와 공개 장소 보존, 제거된 열·함수, ACL, 실패 rollback의 값·catalog fingerprint를 확인했다.
- 이후 실제 seed와 schema/negative SQL을 정상 사례에 추가해 삭제된 event.location 참조2개 RED를 재현했다. 로컬 fixture 전용 pg_temp 버전 분기로 수정한 뒤 PG16/17 정상2개가 통과했다(2분8초). 운영 runtime에 v1 fallback은 없다.
- 최신 공통 품질 검사는 원자 cutover generator·실행기·직접 push 차단 정책과 Python865개 중862 PASS/3 SKIP를 포함해 성공했다.
- DB020 SHA-256은 `69df6c6f019efb1e3544ae36ec4b0332a8c299cca059a8931cebd04dcc3d163a`로 manifest와 일치한다. MCP wire hash 열 제거와 revision request hash의 closed command hash 일치를 포함한 schema class30개가 PG16/17에서 통과했다.
- canonical7, 두 세션 lineage6, command snapshot22, 위치 guard50, revision5, schema v2 30의 총120개 확장 회귀가 18분45초에 failures/errors/skips0으로 통과했다.
- 017·018 Supabase 생성 SQL은 ledger 열/PK·정확한 선행 이력·server major별 schema/RLS/ACL fingerprint를 같은 transaction에서 검사한다. PG16/17 합성 ledger2개와 실제 Supabase CLI2.110.0 격리 PG17 적용이 통과했고, 적용 후 dry-run은 019·020만 제시했다.
- 아직 완료되지 않은 항목: 선행 PR241 병합 뒤 clean committed SHA의 전체 품질 gate, 공식 독립 승인, 원격 staging 적용, MCP0.8 교차 저장소 검증, native/staging/provider E2E.

완료 시 FE/BE/AI SHA, manifest checksum, DB fingerprint와 source snapshot을 함께 기록한다.
TMAP 필드별 저장 허용 근거가 확인되기 전에는 후보 저장·적용 출시를 허용하지 않는다.

## 복구

020 이후에는 위치 저장 API가 있는 v1 애플리케이션으로 되돌리지 않는다.
신규 계산을 비활성으로 유지하고 호환되는 v2 애플리케이션으로 복구한다.
이미 저장된 일정 조회는 계속 제공하되, 이를 새 생성·평가 기능의 정상 동작 근거로 삼지 않는다.
