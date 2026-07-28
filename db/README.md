# Timing Jeju 데이터베이스 개발 환경

## 환경별 연결 관계

| 환경 | Auth | PostgreSQL/PostGIS | 사용자 데이터 |
| --- | --- | --- | --- |
| 로컬 Supabase | `http://127.0.0.1:54321/auth/v1` | `127.0.0.1:54322/postgres` | 로컬 Docker 볼륨에만 저장 |
| 일반 PostgreSQL 스모크 테스트 | `db/local-postgres/auth_compat.sql`의 최소 호환 객체 | 격리된 PostGIS 16 컨테이너 | 가짜 fixture만 저장하고 종료 시 삭제 |
| 운영 | 호스팅된 Supabase Auth | 호스팅된 Supabase PostgreSQL/PostGIS | 해당 운영 프로젝트 안에서만 저장 |

로컬과 운영은 서로 다른 Auth·DB 인스턴스이므로 사용자와 데이터가 공유되지 않습니다. 운영 URL, DB 비밀번호, publishable key 등 실제 값은 배포 플랫폼의 비밀 저장소에서 주입하고 저장소나 로그에 남기지 않습니다.

Spring과 클라이언트가 사용하는 환경 변수 이름은 다음과 같습니다.

- `SUPABASE_URL`: 로컬 API URL 또는 운영 프로젝트 URL
- `SUPABASE_PUBLISHABLE_KEY`: 클라이언트용 publishable key
- `SPRING_DATASOURCE_URL`: 환경별 PostgreSQL JDBC URL
- `SPRING_DATASOURCE_USERNAME`: 환경별 DB 사용자
- `SPRING_DATASOURCE_PASSWORD`: 환경별 DB 비밀번호

Spring Security/JWT 검증과 로그인 API는 이 이슈의 범위가 아니므로 아직 위 Supabase 값을 애플리케이션 코드에서 사용하지 않습니다.

## 마이그레이션 소유권

`supabase/migrations`가 public 애플리케이션 스키마의 유일한 버전 관리 기준입니다. Supabase CLI가 로컬과 운영에 같은 파일을 순서대로 적용합니다.

- `supabase/migrations/20260728000000_initial_public_schema.sql`: PostGIS 확장과 public 애플리케이션 객체
- `supabase/seed.sql`: 운영 적용 가능한 빈 시드
- `db/local-postgres/auth_compat.sql`: Supabase가 아닌 일반 PostgreSQL 전용 Auth 호환 계층
- `db/local-postgres/seed_fixtures.sql`: 일반 PostgreSQL Docker 스모크 테스트 전용 가짜 데이터
- `db/queries/smoke_check.sql`: 기존 fixture와 공간 쿼리 확인

운영 마이그레이션은 `auth` 스키마, `auth.users`, `auth.uid()`를 생성·교체·삭제하지 않으며 `auth.users`에 직접 INSERT하지 않습니다. `auth.users` 외래키와 `auth.uid()`를 사용하는 RLS 정책은 Supabase 소유 객체를 참조할 뿐 변경하지 않으므로 유지합니다.

현재 Flyway는 도입하지 않습니다. Flyway 의존성·설정·`db/migration`을 추가하지 않고, 도입 여부는 향후 별도 GitHub Issue에서 검토합니다. 운영에 `db/local-postgres` 파일을 적용하거나 이 파일을 `supabase db push` 대상으로 복사하면 안 됩니다.

정책 검사는 독립적으로 실행할 수 있습니다.

```bash
python3 scripts/deploy_sql_policy.py
python3 -m unittest scripts.tests.test_deploy_sql_policy scripts.tests.test_supabase_layout
```

검사기는 single-quoted string, PostgreSQL E-string, quoted identifier와 Unicode 태그를 포함한 dollar-quoted body를 구분하고 실제 `--`, `/* */` 주석만 제외합니다. 문자열 본문은 동적 SQL일 수 있으므로 보존하며, PostgreSQL `EXECUTE`의 직접 문자열 안에 금지 SQL이 연속된 토큰으로 있으면 보수적으로 정책 위반으로 처리합니다. 단순 안내 문자열에도 같은 금지 SQL 문구를 쓰지 않아야 하며, 금지 객체를 설명할 때는 SQL 파일이 아닌 한국어 문서를 사용합니다.

이 검사는 SQL 실행 의미를 평가하는 범용 파서가 아닙니다. `EXECUTE 'create table auth.' || 'users(...)'` 같은 문자열 연결이나 `EXECUTE`와 `format(...)`으로 런타임에 만들어지는 객체명은 의미 분석 범위 밖입니다. 이런 동적 SQL은 자동 검사 통과만으로 안전하다고 간주하지 않고 코드 리뷰에서 금지 객체 조합 여부를 별도로 확인합니다.

## 로컬 Supabase 시작과 초기화

필수 도구는 Docker Engine, Docker Compose, Supabase CLI `2.110.0`입니다. CLI는 [Supabase CLI v2.110.0 공식 릴리스](https://github.com/supabase/cli/releases/tag/v2.110.0)의 운영체제별 설치 파일을 사용하고 다음 명령으로 버전을 확인합니다.

```bash
supabase --version
```

저장소 루트에서 로컬 Auth와 PostgreSQL/PostGIS를 시작합니다.

```bash
supabase start
supabase db reset
```

`supabase db reset`은 로컬 DB 데이터를 삭제하고 모든 마이그레이션과 `supabase/seed.sql`을 다시 적용하는 로컬 전용 명령입니다. 운영 프로젝트를 연결하거나 운영 DB에 `db push`하지 않습니다.

반복 초기화와 정리까지 한 번에 검증하려면 다음 스크립트를 실행합니다. CLI가 없거나 버전이 다르거나 Docker daemon이 꺼져 있으면 한국어 오류로 즉시 실패합니다. CLI가 표시할 수 있는 로컬 키는 검증 로그에 출력하지 않습니다.

```bash
./scripts/supabase-smoke-test.sh
```

테스트 사용자가 필요하면 애플리케이션의 Supabase Auth 클라이언트 또는 로컬 Auth signup API를 사용해 가짜 사용자를 생성합니다. 비밀번호와 publishable key는 실행 시 환경 변수로만 전달하고 저장소·스크립트·로그에 남기지 않습니다. SQL로 `auth.users`에 직접 INSERT하는 방식은 사용하지 않습니다.

## 일반 PostgreSQL/PostGIS 경로

기존 Spring Docker 검증은 Supabase Auth 컨테이너 대신 로컬 전용 호환 계층을 먼저 적용한 뒤 같은 `supabase/migrations` 기준선을 적용합니다.

```bash
docker compose up -d postgres
./scripts/docker-smoke-test.sh
```

개발용 PostgreSQL은 기존 로컬 5432 포트와 충돌하지 않도록 `localhost:5433`에 노출됩니다. 직접 초기화하려면 로컬 볼륨을 삭제한 뒤 다시 시작합니다.

```bash
docker compose down -v
docker compose up -d postgres
```

`./scripts/docker-smoke-test.sh`와 `./scripts/supabase-smoke-test.sh`는 성공·실패와 관계없이 자신이 만든 컨테이너와 임시 DB 자원을 정리합니다.
