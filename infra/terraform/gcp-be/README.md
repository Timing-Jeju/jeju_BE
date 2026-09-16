# GCP BE 배포 기반 (코드 작성 범위)

이 Terraform 루트는 전용 VPC, 사설 서브넷, 고정 NAT 송신 IP, Artifact Registry,
비밀정보별 읽기 IAM, HTTPS 로드 밸런서와 선택적으로 단일 Ubuntu VM을 구성한다.
기존 서비스 DB 생성·마이그레이션, 이미지·비밀값 업로드, AWS MCP 배포,
DNS 자동 설정은 수행하지 않는다.

## 필수 입력과 기본 구성

필수 입력은 `project_id`, `domain_name`이다. 기본값은 서울 리전,
`asia-northeast3-a` 존, `e2-standard-2`, `runtime_enabled=false`다.
런타임을 비활성화해도 apply 후 네트워크/NAT/LB 비용은 발생한다.
단일 VM 갱신 중에는 서비스가 중단될 수 있으며, 고가용성 배포 구성은 아니다.

## 비밀정보 준비와 접근 권한

기본 구성은 같은 프로젝트에 빈 Secret Manager 비밀 리소스만 만들며 버전은 만들지 않는다.
런타임 활성화 전에 별도 절차로 값을 등록하고 숫자 버전을 지정한다.
기존 비밀 리소스를 사용하려면 `create_secret_containers=false`로 설정하거나 먼저 import한다.
`runtime_env_secret_id`는 환경 설정용 비밀 리소스 이름이다.
Terraform은 런타임 서비스 계정에 해당 ID와 `secret_files`의 ID에 대한 접근 권한만 부여한다.
비밀값은 Terraform 변수, 리소스, 데이터 소스, state에 넣지 않는다.
실제 비밀값을 tfvars, plan, 셸 기록 또는 저장소에 남기지 않는다.

환경 설정용 비밀값은 `export`와 따옴표 없이 Docker env-file 문법으로 작성한다.
시작 시 다음 필수 키가 비어 있지 않은지 검사한다.

- `SPRING_DATASOURCE_URL`: PostgreSQL `sslmode=verify-full` 사용
- `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `SUPABASE_JWT_ISSUER`, `SUPABASE_JWKS_URL`
- `APP_CORS_ALLOWED_ORIGINS`
- `APP_PLACES_CURSOR_SIGNING_KEY`, `APP_TRIPS_CURSOR_SIGNING_KEY`

값은 Docker 환경변수 메타데이터가 아닌 Spring configtree 파일로 전달한다.
`application.yml`에 명시된 대문자 플레이스홀더 이름을 그대로 유지하며,
임의의 Spring 환경변수 이름으로 설정을 자동 덮어쓰는 방식은 지원하지 않는다.
프레임워크 설정은 환경변수처럼 자동 변환되지 않으며, 다음 두 키만 명시적으로 매핑한다.

- `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`
- `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`

운영 프로필 `production`, 정상 종료 단계 제한 시간 `210s`, Swagger/API 문서 비활성화는
비밀값이 아닌 고정 컨테이너 인수다. 비밀값에 임의의 셸/JVM 환경변수 키를 추가해도
적용된다고 가정하지 않는다. 로컬 JWT 프로필은 사용하지 않는다.
실행 중인 워커 작업을 위해 정상 종료 단계 제한 시간은 최소 `210s`를 유지한다.
Swagger/API 문서는 의도적으로 공개하기로 결정한 경우가 아니면 비활성화한다.

DB 계정은 마이그레이션용 슈퍼유저가 아니라 애플리케이션 권한을 가진 기존 계정을 사용한다.
드라이버가 요구하면 DB 신뢰 루트 인증서를 비밀 파일로 제공하고 JDBC URL에 마운트 경로를 지정한다.
Hikari 커넥션 풀 제한은 기존 운영 DB 용량에 맞춰 설정한다.
추가 기능의 비밀정보와 설정은 `application.yml`을 따른다.
기본 비활성화 기능은 런타임 환경에서 명시적으로 활성화하기 전까지 비활성 상태를 유지한다.

## 비밀 파일과 AWS MCP 연결

`secret_files` 형식은 `{ "filename": { secret_id = "existing-secret", version = "1" } }`이다.
파일은 `/run/secrets/filename`에 마운트되며 권한은 `0600`, UID/GID는 `10001`이다.
MCP 키 설명자 JSON은 호스트 경로가 아니라 해당 **컨테이너 내부** 키 경로를 참조해야 한다.
`MCP_JWT_SIGNING_KEY_DESCRIPTOR_FILE`, `MCP_TLS_TRUST_CERTIFICATE_FILE`,
issuer, audience, 기본 URL, 허용 호스트를 기존 AWS 서버와 일치시킨다.
`private_host_mappings`로 기존 MCP TLS 호스트명과 고정된 사설 RFC1918 IPv4를
Docker add-host에 전달할 수 있다. 사설 DNS가 있으면 이를 우선 사용한다.
호스트명은 TLS 인증서 SAN과 일치해야 하며 TLS 검증을 비활성화하지 않는다.
일정 생성 제한 시간은 최소 165초를 유지한다.
승인된 공항 UUID는 기존 BE 데이터의 ID이며 MCP canonical 문자열이 아니다.
네트워크 연결만으로 일정 생성 기능이 활성화되지는 않는다.

## 런타임 활성화와 시작 동작

VPN/사설 DNS, 비밀정보, 마이그레이션, 출력된 Artifact Registry 저장소의
digest 고정 이미지가 준비된 후에만 런타임을 활성화한다.
시작 준비 스크립트는 메타데이터 인증정보로 비밀값을 조회하며 실패하면 시작을 차단한다.
비밀값과 레지스트리 인증정보는 휘발성 경로인 `/run` 아래에만 보관한다.
레지스트리 인증정보는 이미지 내려받기 후 제거한다.
컨테이너에서 메타데이터 토큰 엔드포인트로 접근하는 것은 차단한다.
애플리케이션은 비특권 사용자와 읽기 전용 파일시스템으로 실행하며 Docker 요청/본문 로그를 남기지 않는다.
아래의 HTTPS 가용성 알림은 명시적으로 활성화할 수 있지만,
애플리케이션 지연 시간이나 워커 지표를 내보내는 구성은 포함하지 않는다.
SSH는 선택적으로 활성화하는 IAP 전용이며 운영자의 IAP 및 OS Login IAM 권한은 별도로 부여한다.

시작 시 Ubuntu 패키지를 설치하므로 첫 부팅에는 NAT/apt/Google API 접근이 필요하다.
OS 이미지 계열과 apt 저장소는 불변이 아니므로 이후 배포에서는 검증된 이미지를 고정하거나
보안 강화 이미지를 미리 빌드한다. 운영 MCP 통신에는 SSH 터널을 사용하지 않는다.
DB 자동 마이그레이션이나 DB 삭제 리소스는 포함하지 않는다.

## 클라우드 인증정보 없이 검증

```sh
terraform init -backend=false
terraform fmt -check
terraform validate
terraform test
```

모의(mock) plan 테스트는 리소스 구성과 허용하지 않는 이미지 입력의 차단을 검증한다.
실제 GCP 권한, OS 시작 동작, 인증서, VPN 연결, 애플리케이션 실행을 검증하는 것은 아니다.
원격 state 초기화와 배포 순서는 [상위 README](../README.md)를 따른다.

## 재시작과 장애 대응 정책

`PartOf=docker.service`로 Docker 재시작을 BE에 전파하고,
설치된 `docker.service.wants` 의존성으로 Docker가 다시 시작될 때 BE도 시작한다.
정상 종료 단계 제한 시간은 210초를 유지한다.
배포 전 대상 Ubuntu VM에서 중지/시작/재시작을 검증한다.
정적 검사와 단위 테스트는 실제 systemd 동작 검증을 대신하지 않는다.

DNS/TLS 준비 후 `monitoring_enabled=true`로 설정하고,
검증된 기존 `notification_channels` 리소스 이름을 지정한다.
HTTPS 상태를 매분 확인하며 실패가 5분간 지속되면 장애 알림을 발생시킨다.
애플리케이션 요청·응답 본문을 알림에 보내지 않는다.
운영 전 통제된 장애를 발생시켜 실제 알림 수신을 확인한다.
DNS 준비 전 불필요한 알림을 방지하기 위해 기본 구성에서는 비활성화한다.

상태 확인 실패는 무조건적인 재시작 반복이 아니라 **알림 후 운영자 복구**로 처리한다.
DB/MCP 장애에서는 JVM이 살아 있어도 의존 서비스가 사용 불가할 수 있으며,
일정 생성 워커의 반복 재시작은 실행 중인 작업을 중단시킬 수 있다.
알림 발생 시 다음 순서로 대응한다.

1. 재시작 전에 DNS/인증서와 LB 상태를 확인한다.
2. 승인된 IAP 접근으로 `systemctl is-active timing-jeju-be`와 Docker 상태 확인 결과만 조회한다.
   환경변수, 전체 inspect 결과 또는 비밀 파일을 출력하지 않는다.
3. DB/MCP 가용성과 일정 생성 워커 lease를 확인한다.
   유지보수 중에는 지원되는 절차로 신규 생성 접수를 중단하고 실행 중인 작업의 종료를 기다린다.
4. 의존 서비스 복구 후에도 앱이 응답하지 않으면 통제된 상태에서
   `systemctl restart timing-jeju-be`를 한 번 실행하고 상태 확인 및 대기 작업 복구를 검증한다.
5. 복구되지 않으면 검토된 Terraform plan으로 승인된 이전 이미지/비밀 버전을 복원한다.
   재시작을 반복하거나 DB 데이터를 자동 롤백하지 않는다.

계획된 Docker 유지보수에서 중지 후 시작하면 BE도 함께 다시 시작된다.
Docker 시작 후에도 BE를 의도적으로 중지 상태로 유지하려면 먼저 BE 유닛을 비활성화하고,
유지보수 종료 후 다시 활성화한다. VM/존 교체는 이 단일 VM 설계의 범위에 포함되지 않는다.
