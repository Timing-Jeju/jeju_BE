# #225 계획 anchor provenance 구현 일지

## 첫 구현: 임의 item facts 저장 차단

- 기준 develop: f7fc751. 선행 #221/#222 병합 후 최신 base에서 최종 검증한다.
- 실제 PostgreSQL에서 직접 위치, nested 현재 위치, 파생 지역/nearest place/grid/geohash 및 배열/null JSON이 저장되는 RED 8건을 확인했다. 로그: `/tmp/jeju-225-facts-red.log`.
- `trip_items.facts`에는 승인된 자유 형식 필드가 없으므로 현재 closed shape를 빈 객체 `{}`로 고정했다. 향후 evidence는 승인된 버전별 정규화 projection 계약으로 추가해야 한다. `trip_legs.facts`의 기존 leg derivation marker는 이 변경 대상이 아니다.
- private SECURITY INVOKER trigger가 INSERT/UPDATE를 sanitized 23514로 거부해 실패 row에 원문을 반사하지 않는다. 추가 CHECK가 구조를 고정한다. PUBLIC/anon/authenticated/service_role의 직접 함수 EXECUTE는 회수했다.
- 기존 non-empty facts가 있으면 table lock 안에서 감사하고 migration 전체를 중단한다. 자동 삭제·자동 매핑·현재 위치 판정을 하지 않는다.
- Supabase CLI가 PATH에 없어 저장소 선행 작업과 같은 pinned `npx supabase@2.116.0 migration new`를 사용했다. CLI 생성 timestamp가 기존 예약 suffix보다 앞서므로 append-only 순서에 맞춰 `20260918000013` / init `051`로 이름을 정렬했다.
- manifest SHA256, Compose 3개 및 Unix smoke init/upgrade 목록을 등록했다. PowerShell은 기존 manifest 소비 경로를 유지한다.
- GREEN: `/tmp/jeju-225-facts-green.log` 8건; `/tmp/jeju-225-facts-guards.log` 12건(정상 service writer, UPDATE 거부, ACL 포함) 성공. 원문 marker가 SQLException에 반사되지 않는지 검사했다.
- migration manifest RED/GREEN: `/tmp/jeju-225-manifest-red.log`, `/tmp/jeju-225-manifest-green.log` 13건 성공. 기존 #215 검사는 suffix 마지막이 아니라 해당 역사적 파일명을 직접 참조하도록 정정했다.

## 남은 구현과 검증

- JDBC 및 sealing의 좌표 fallback 제거와 public place/stop/accommodation/transport resolver.
- route snapshot의 origin/destination exact-one 참조, owner/version/item lineage, 공개 좌표 및 request hash provenance.
- legacy route의 증명 가능한 backfill과 불명확/활성 자료 fail-closed 경계. 좌표 일치만으로 provenance를 발명하지 않는다.
- 전체 DB fresh/upgrade/PG16·17/concurrency, schema/ACL fingerprint, OpenAPI, 전체 품질 gate 및 독립 리뷰.
- 첫 facts migration은 후속 변경에서 수정하지 않고 필요한 보강은 새 forward migration으로 추가한다. #225 전체 완료나 PR 준비 상태가 아니다.

## Legacy rollback 보강

- `/tmp/jeju-225-legacy-rollback.log`: PostgreSQL 16·17에서 non-empty legacy facts가 있으면 새 migration이 실패하고 원래 JSON과 전체 schema/RLS/ACL fingerprint가 동일하게 유지되는 것을 검증했다(2건 PASS).
- 기존 자료 marker 및 좌표가 migration 실패 예외에 반사되지 않는지도 확인했다. 테스트의 첫 compile 오류(AssertJ varargs 미지원)는 개별 assertion으로 정정했다.
