# 추천 체류시간 정책 import

추천 체류시간 정책의 canonical writer는 Spring 운영용 versioned CSV command 하나입니다. 공개 CRUD endpoint, FastAPI writer와 ML 생성은 제공하지 않습니다.

## CSV 계약

UTF-8 CSV header는 정확히 `scope,category,placeId,minutes`입니다. quoting, multiline, 빈 행, control character, formula/macro prefix와 추가 열을 허용하지 않습니다. 따라서 사용자 이메일, API token, secret과 외부 raw payload를 담을 위치가 없습니다. 파일은 1 MiB/10,000행 이하이고 설정한 import root 안의 symlink가 아닌 절대 `.csv` 파일이어야 합니다.

```csv
scope,category,placeId,minutes
category_default,tourist_attraction,,75
place_override,,20000000-0000-0000-0000-000000000002,70
```

category는 live `tour_places.category`의 canonical code여야 합니다. place override는 존재하며 stale/tombstoned가 아닌 장소만 허용합니다. minutes는 5~1440, version은 소문자 영숫자로 시작하는 최대 64자의 `[a-z0-9._-]` 식별자입니다. `effectiveAt == 현재 시각`은 허용하고 미래는 거부합니다.

payload hash는 version, effectiveAt과 NFKC 정규화한 policy 의미를 scope/target/minutes 순으로 정렬해 SHA-256으로 계산합니다. 따라서 CSV 행 순서와 줄바꿈에는 의존하지 않습니다.

## 실행

먼저 `STAY_POLICY_IMPORT_DRY_RUN=true`로 전체 payload와 DB target을 검증합니다. 실제 import 때만 false로 바꾸고 현재 active version을 `STAY_POLICY_IMPORT_EXPECTED_ACTIVE_VERSION`에 명시합니다. 최초 version은 이 값을 비웁니다.

```text
STAY_POLICY_IMPORT_ENABLED=true
STAY_POLICY_IMPORT_ROOT=/absolute/ops/stay-policy
STAY_POLICY_IMPORT_FILE=/absolute/ops/stay-policy/policy.csv
STAY_POLICY_IMPORT_VERSION=stay-2026-summer-v1
STAY_POLICY_IMPORT_EFFECTIVE_AT=2026-08-23T09:00:00Z
STAY_POLICY_IMPORT_EXPECTED_ACTIVE_VERSION=
STAY_POLICY_IMPORT_DRY_RUN=true
```

실행 로그에는 version, payload hash, 행 수와 dry-run 여부만 남고 파일 경로·내용은 남기지 않습니다. 전체 validation 성공 후 한 transaction에서만 active version을 교체합니다. stale expected version, 같은 version의 다른 hash, 중간 insert 실패는 이전 active를 그대로 유지합니다.
