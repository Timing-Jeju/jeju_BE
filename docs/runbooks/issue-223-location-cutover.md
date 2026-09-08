# 위치 비수집 DB 전환 — #223 검증 중

이 문서는 미출시 017 migration의 로컬·격리 staging 실행 조건을 기록한다. 운영 적용 승인이나 전체 위치 비수집 완료 증거가 아니다. #224의 ingress 사전 검증과 구버전 runtime 제거가 추가로 필요하다.

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

017은 migration 이력으로 한 번만 적용한다. 이미 적용된 DB의 재확인은 위 읽기 전용 verifier를 반복 실행한다. `CREATE FUNCTION`을 포함한 migration 본문을 수동 재실행하거나 이력에서 지워 재적용하지 않는다.

모든 count가 0이고 revision이 `20260918000017`일 때만 DB 단계의 성공으로 기록한다. 이 marker는 fleet 버전, 요청 hash 수집 여부, 로그·백업의 삭제 완료를 보증하지 않는다. 값을 포함한 사용자 payload를 감사 로그에 남기지 않는다.

## 보존과 실패 경계

공개 관광지·정류장·노선·날씨 원천 좌표와 출처가 입증된 계획 anchor는 사용자 현재 위치 정리 대상이 아니다. 위치 계보로 생성된 미적용 후보와 후손만 정리하고, 평가 대상 원본 일정은 유지한다. 적용 흔적이나 계보 모호성이 있으면 중단한다.

route snapshot의 version/item 참조 FK 세 개는 삭제 transaction 안에서만 지연하고 즉시 재검증해 기존 OID·정의·trigger 상태로 복원한다. 중간 실패 시 데이터·스키마·권한 변경 모두 rollback한다. 기존 migration 파일을 수정하거나 FK를 삭제해 진행하지 않는다.

실패 후에는 원인을 확인하고 호환 버전으로 재시도한다. 성공한 위치 비수집 전환을 GPS 수집 구버전으로 되돌리지 않는다. 신규 계산을 비활성화하고 기존 일정 조회를 유지하는 복구 경로를 사용한다.

## 남은 인수 증거

017 전체 PG16/17 fresh·upgrade·동시성·fingerprint, 같은 SHA 전체 quality gate, 독립 리뷰, #224 runtime 검증 및 실제 staging readback은 각각 별도로 기록한다. 합성 테스트 통과를 실제 provider 또는 네이티브 앱 통합 완료로 표현하지 않는다.
