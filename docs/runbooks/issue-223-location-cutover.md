# 위치 비수집 DB 전환 — #223 검증 중

이 문서는 미출시 017·018 atomic group의 로컬·격리 staging 실행 조건을 기록한다. 운영 적용 승인이나 전체 위치 비수집 완료 증거가 아니다. #224의 ingress 사전 검증과 구버전 runtime 제거가 추가로 필요하다.

## 적용 조건

- #222 공개 장소·계획 anchor 기반 날씨 계약과 #225 planned anchor 출처 migration 013–016을 먼저 적용한다.
- 위치를 쓸 수 있는 구버전 프로세스와 신규 계산 접수를 중지한다. worker가 처리 중인 위치 계보가 있으면 종료·취소 상태를 먼저 확인한다.
- `MCP_ENABLED=false`를 유지한다. #224에서 실제 wire arguments를 hash 생성·전송 전에 검사하고 호환 계약을 검증하기 전에는 MCP 호출을 활성화하지 않는다. command input의 비위치 판정은 독립적인 MCP wire hash의 비위치 증명이 아니다.
- migration은 관련 사용자 테이블 23개를 잠그고 출처를 감사한다. 잠금을 얻지 못하면 임의로 감사·잠금 단계를 생략하지 않는다.
- 출처를 입증할 수 없는 hash, 일반 JSON, 활성·적용 일정, 외부 계보가 발견되면 전체 transaction이 실패한다. 해당 데이터는 자동 삭제하지 않는다.
- `api_idempotency_records`는 run 계보가 없으며 응답 bytea와 request hash만으로 비위치 입력을 입증할 수 없다. 하나라도 남아 있으면 감사가 실패한다. 정상 보존 정책에 따른 만료 처리나 별도의 승인된 출처 판단이 필요하다. 이 migration은 receipt를 임의 삭제하거나 재작성하지 않는다.

- 위치 부모와의 계보가 입증되어 함께 정리된 로그를 제외하고, 남은 `mcp_compute_call_logs`는 전부 미분류 잔여물이다. `location_supplied=false`, command hash 일치, tool/status/schema checksum만으로 optional GPS가 wire에 없었다고 판정할 수 없다. 원문을 복원하거나 임의 삭제하지 않고 감사를 중단한다.

## 결과 확인

소유자 전용 연결에서 다음을 실행한다. 앱/API 역할에는 실행 권한을 주지 않는다.

```sql
select * from timing_jeju_planner_private.user_location_residue_counts();
select timing_jeju_planner_private.user_location_guard_purge_revision();
```

017·018은 검증한 atomic group으로 한 번만 적용한다. 이미 적용된 DB의 재확인은 위 읽기 전용 verifier를 반복 실행한다. `CREATE FUNCTION`을 포함한 migration 본문을 수동 재실행하거나 이력에서 지워 재적용하지 않는다.

모든 count가 0이고 revision이 `20260918000018`일 때만 DB 단계의 성공으로 기록한다. 이 marker는 fleet 버전, 요청 hash 수집 여부, 로그·백업의 삭제 완료를 보증하지 않는다. 값을 포함한 사용자 payload를 감사 로그에 남기지 않는다.

## 보존과 실패 경계

공개 관광지·정류장·노선·날씨 원천 좌표와 출처가 입증된 계획 anchor는 사용자 현재 위치 정리 대상이 아니다. 위치 계보로 생성된 미적용 후보와 후손만 정리하고, 평가 대상 원본 일정은 유지한다. 적용 흔적이나 계보 모호성이 있으면 중단한다.

route snapshot의 version/item 참조 FK 세 개는 삭제 transaction 안에서만 지연하고 즉시 재검증해 기존 OID·정의·trigger 상태로 복원한다. 중간 실패 시 데이터·스키마·권한 변경 모두 rollback한다. 기존 migration 파일을 수정하거나 FK를 삭제해 진행하지 않는다.

실패 후에는 원인을 확인하고 호환 버전으로 재시도한다. 성공한 위치 비수집 전환을 GPS 수집 구버전으로 되돌리지 않는다. 신규 계산을 비활성화하고 기존 일정 조회를 유지하는 복구 경로를 사용한다.

## 남은 인수 증거

017 전체 PG16/17 fresh·upgrade·동시성·fingerprint, 같은 SHA 전체 quality gate, 독립 리뷰, #224 runtime 검증 및 실제 staging readback은 각각 별도로 기록한다. 합성 테스트 통과를 실제 provider 또는 네이티브 앱 통합 완료로 표현하지 않는다.

## 018 추가 감사와 실행 경계 — 검증 중

정상 command input이 있어도 독립적인 `schedule_revision_runs.request_hash`의 비위치를 증명할 수 없다. 남은 revision run은 `unclassified_schedule_revision_request_hashes`로 집계하며 임의 삭제·재해시·command hash 대입을 하지 않는다.

017 자체 COMMIT 때문에 017과 018을 별도 실행하면 018 실패 시 017을 되돌릴 수 없다. 기본 Docker·Java canonical 경로는 두 원문의 checksum을 검증해 생성한 단일 transaction 파일을 사용한다. 생성 검증은 `python3 scripts/location_cutover_group.py --check`다. 017 이전 DB의 그룹 실패는 017 변경까지 rollback해야 한다. 이미 017을 단독 적용한 로컬 DB는 별도 감사 대상이며 이전 상태까지 rollback했다고 기록하지 않는다.

일반 `supabase migration up`/`db push`로 원문 파일을 각각 실행하는 경로는 이 원자성 계약을 충족하지 않는다. `[db.migrations].enabled=false`는 `migration up`을 차단하지 않으므로 안전 경계로 주장하지 않는다. 공식 진입점 `scripts/supabase-release.sh`는 고정 CLI 2.116.0에 016 이하만 복사한 임시 workdir을 제공한 뒤, 생성 SQL `db/local-postgres/location_cutover_supabase.sql`로 017·018과 018 이후 canonical suffix를 ledger와 함께 한 transaction에서 적용한다. 실패하면 017 이후 DDL·data·ledger 전체가 rollback된다. 운영 적용은 작업 범위 밖이다.

남은 검증: 그룹 정상 종료·감사 실패·연결 종료·잠금 timeout의 PG16/17 데이터/schema/ACL/trigger 복구, canonical fresh/upgrade fingerprint, 일반 적용 경로 우회 방지, 동일 SHA 전체 품질 게이트와 독립 리뷰.


Supabase용 산출물은 `python3 scripts/location_cutover_group.py --supabase --check`로 검사한다. 이력을 따로 `migration repair`로 등록하지 않는다. 공식 문서는 `supabase_migrations.schema_migrations`가 적용 여부의 기준이며 repair 자체는 SQL을 적용하지 않는다고 설명한다: https://supabase.com/docs/guides/deployment/database-migrations . 생성 SQL은 기존 이력 테이블이 있어야 하며 016까지의 정확한 선행 집합과 marker 부재를 검사한다. 이미 017 이후 이력이 있거나 다른 선행 집합이면 수정·삭제 없이 거부한다. ledger의 `statements`는 CLI 2.116.0 fetch가 `join(';\n') + ';\n'`으로 원문을 복원하도록 각 immutable source의 마지막 separator 전까지를 보존한다.


적용 담당자는 marker/이력만으로 predecessor가 맞다고 판단하지 않는다. migration repair로 이력만 바뀔 수 있으므로 016까지의 실제 schema·RLS·ACL fingerprint를 같은 release의 검증 결과와 대조해야 한다. 그룹 실행 동안 일반 `db push`와 다른 migration runner를 중지해야 한다. ledger table 잠금만으로 개별 017 SQL을 병행 실행하는 경로까지 안전해지는 것은 아니다. 실제 CLI ledger 스키마 호환·predecessor 검증·단독 실행 조건이 확인되지 않으면 staging 적용하지 않는다.


로컬 검증 증거: revision verifier/그룹 rollback 4건, canonical fresh/upgrade fingerprint 2건, 합성 ledger PG16/17 2건, 이력 INSERT 실패/중간 statement timeout/연결 종료 6건, 위치 revision 정상 정리 2건은 각각 해당 개발 일지의 로그에 기록했다. 일부 시나리오는 같은 테스트의 확장 재실행이며 서로 다른 전체 테스트 개수로 합산하지 않는다. 잠금 timeout 추가와 동일 SHA 전체 품질 게이트는 별도 확인한다.

최종 잠금 timeout을 포함한 interruption6건도 PG16/17에서 통과했다. 동일 SHA 전체 품질 게이트와 실제 staging 적용 조건은 여전히 별도 gate다.

## 020 post-merge fail-closed 보정

병합된 017·018은 immutable migration으로 유지한다. #242의 `20260918000019`/Docker `057` 예약 다음인 `20260918000020_location_provenance_fail_closed.sql`/`058`이 후속 보정을 소유한다. #242 파일은 이 변경에서 수정하지 않는다.

020은 legacy audit에서 `current_position`, `currentPosition`, `position`, `point`, 단·복수 coordinate alias를 정규화해 인식한다. 정상 `childAges` 같은 숫자 배열을 위치로 오인하지 않도록 legacy residue 검출은 의미 기반 alias 검사로 한정한다. 신규 쓰기는 별도의 surface별 closed field/type 계약을 사용해 3원소 좌표, 숫자 문자열, 중첩 unknown field를 차단하고 허용된 benign array만 받는다. legacy alias가 한 건이라도 있으면 020의 함수·trigger·revision 변경 전체가 rollback된다.

현재 DB에는 독립 `schedule_revision_runs.request_hash`와 `mcp_compute_call_logs.mcp_input_hash`가 위치를 포함하지 않았음을 증명하는 typed provenance 계약이 없다. 020 이후 Compose owner/JDBC 일반 writer와 `service_role`을 포함한 모든 runtime role은 두 테이블의 INSERT와 해당 hash identity UPDATE를 값 비반사 SQLSTATE 23514로 거부한다. 일반 command lineage는 허용 근거가 아니다. migration/legacy fixture의 owner-only DDL 준비와 runtime write 정책은 테스트에서 분리한다. #224가 closed typed wire/request provenance를 별도 forward migration으로 제공하기 전까지 이 fail-closed 경계를 유지한다.

`supabase/config.toml`의 `[db.migrations].enabled=false`와 `scripts/supabase-smoke-test.sh`의 exit 64는 reset/push 실수를 줄이는 보조층일 뿐 `migration up` 차단 수단이 아니다. 공식 release script만 CLI bootstrap을 수행하며 017 이후 파일을 CLI workdir에 복사하지 않는다. 현재 생성 SQL은 017·018·020을 한 transaction에서 실행하고 CLI 호환 `(version,name,statements)` ledger 세 행을 기록한다. 이후 019가 manifest에 들어오면 timestamp 순으로 같은 transaction에 자동 포함한다. 실제 CLI list/fetch와 원문 SHA 검증이 성공해야 release가 성공한다.

실제 Supabase 적용은 계속 금지한다. #242가 병합되어 019/057이 확정되고 PM이 승인한 뒤, 016 predecessor fingerprint 확인 → 017+018 group → 019 → 020 순서를 하나의 검증된 release 절차로 다시 확인해야 한다. provider/staging/live DB에는 이 개발 작업에서 적용하지 않는다.
