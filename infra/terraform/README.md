# GCP BE 배포 기반 (#273)

이 디렉터리는 **배포 코드**이며 배포 완료 증거가 아니다. 이번 작업에서 클라우드 리소스 생성,
실제 credential 접근, DB migration 실행, BE/FE 배포를 하지 않는다.

## 구성과 경계

```text
FE ─ HTTPS API 도메인 ─ GCP HTTPS Load Balancer ─ private BE VM 1대
                                                 ├ 기존 PostgreSQL/Supabase (TLS)
                                                 └ GCP HA VPN ─ AWS VPN ─ AWS MCP (TLS + service JWT)
```

- `state-bootstrap/`: 비공개·버전관리 state bucket. 기존 bucket이 있으면 생략한다.
- `gcp-be/`: BE 네트워크, NAT, 이미지/비밀 저장소와 IAM, 선택적 VM 및 HTTPS 진입.
- `gcp-aws-vpn/`: **GCP 측** HA VPN, Cloud Router, BGP 및 AWS peer 정의.
  AWS 리소스는 이 root가 생성하거나 기존 Terraform state에서 가져오지 않는다.
- DB와 Supabase Auth는 기존 것을 사용한다. Cloud SQL/DB 복제/데이터 이관은 범위 밖이다.
- GCP 프로젝트 생성·결제 연결, 도메인 소유권/DNS, 이미지 빌드·푸시, 비밀값 등록,
  AWS VPN/route/security group/호스트 포트 설정은 운영자가 별도 수행한다.
- BE VM은 한 대다. 프로세스 재시작은 가능하지만 무중단 배포·zone 장애 내성을 제공하지 않는다.
  VPN 다중 터널이 있다고 BE 자체가 HA인 것은 아니다.
- 배포 이미지 교체는 작업 중인 worker를 중단할 수 있다. 배포 전 생성 접수 중단/진행 작업
  drain과 lease 복구를 확인해야 하며, Terraform이 자동으로 application drain하지 않는다.

## 실행 전 확정할 값

| 항목 | 결정할 값 |
|---|---|
| GCP | 프로젝트 ID, 서울 region/zone, 배포 계정, state bucket |
| 네트워크 | AWS와 Docker·사내망에 겹치지 않는 BE subnet, 서로 다른 BGP ASN |
| FE 진입 | API FQDN, DNS 관리 주체, 정확한 FE CORS origin |
| 이미지 | Artifact Registry의 승인된 BE image digest (`@sha256:...`) |
| DB/Auth | 기존 DB TLS URL/연결 수 예산, Supabase issuer/JWKS/audience |
| MCP | AWS **VPC private IP**와 서비스 포트, 인증서 SAN과 일치하는 hostname, issuer/audience |
| 원본/공항 | BE canonical 공항 UUID 및 migration/import 완료 여부 |

IP/UUID/키를 환경에서 임의로 추측해 채우지 않는다. 예시값은 실서비스 설정이 아니다.
DB 연결은 SSL 인증서 검증을 포함하고, worker가 쓰는 transaction/locking 기능과 맞는
direct/session 연결을 선택한다. transaction pooler를 검증 없이 적용하지 않는다.

## Terraform 및 state

Terraform 1.11 이상 및 각 root의 고정 provider lockfile을 사용한다.
VPN은 write-only 공유키 지원을 위해 Google provider 7.14 계열을 사용한다.
백엔드별 state prefix를 분리하며 GCS bucket은 비공개·버전관리·접근 최소권한으로 준비한다.
`state-bootstrap/terraform.tfvars.example`을 참고해 별도로 bucket을 준비한다. 이 root 자체는
local state이므로 안전하게 보관하거나 별도 기존 backend로 이관한다. backend bucket은 같은 root에서
처음 만들면서 그 backend로 동시에 초기화할 수 없다.

`*.tfvars`, `backend.hcl`, state, plan, 비밀파일은 Git에 넣지 않는다.
CLI 명령에 실제 키를 직접 적지 말고 승인된 secret 주입 환경에서 사용한다.
`TF_LOG` 및 shell `set -x`는 비밀값 취급 단계에서 사용하지 않는다.

backend 초기화 예시(운영자만 실행, bucket 준비 이후):

```sh
terraform -chdir=infra/terraform/gcp-be init -backend-config=backend.hcl
terraform -chdir=infra/terraform/gcp-aws-vpn init -backend-config=backend.hcl
```

각 root의 `backend.hcl.example`을 별도 `backend.hcl`로 복사해 실제 bucket을 지정한다.
BE prefix와 VPN prefix는 다르게 유지한다. bootstrap 자체는 `state-bootstrap/`에서
별도 `terraform init`을 수행하는 local-backend root이며, 그 state를 분실하지 않는다.

클라우드 접근 없는 검증:

```sh
terraform -chdir=infra/terraform/gcp-be init -backend=false
terraform -chdir=infra/terraform/gcp-be fmt -check -recursive
terraform -chdir=infra/terraform/gcp-be validate
terraform -chdir=infra/terraform/gcp-be test
terraform -chdir=infra/terraform/gcp-aws-vpn init -backend=false
terraform -chdir=infra/terraform/gcp-aws-vpn fmt -check -recursive
terraform -chdir=infra/terraform/gcp-aws-vpn validate
terraform -chdir=infra/terraform/gcp-aws-vpn test
```

`terraform test`는 mock provider의 plan만 실행한다. 실제 GCP quota/IAM/DNS/TLS/VPN 및
runtime bootstrap 성공을 보증하지 않는다. `init -backend=false`는 provider 내려받기만 하며
리소스 생성 명령은 아니다. **실제 apply 명령은 이번 작업에서 실행하지 않는다.**

## 운영자 배포 순서

1. 별도 state bucket과 최소권한 배포 자격 증명을 준비한다. 각 root에 고유 prefix로 backend를 설정한다.
   bootstrap은 Storage API가 활성화된 기존 프로젝트를 전제로 하며 결제나 프로젝트를 만들지 않는다.
2. BE root의 VM 실행을 비활성 상태로 두고 네트워크·저장소·IAM 기반을 검토/생성한다.
3. BE 이미지를 CI에서 빌드해 Artifact Registry에 올리고 digest를 고정한다. 이미지의 플랫폼은 VM과 맞춘다.
4. Secret Manager에 실제 env와 파일 secret version을 별도 등록한다. Terraform은 secret 값 자체를 읽지 않는다.
5. 아래 절차로 GCP와 AWS VPN 및 MCP private endpoint를 연결한다.
6. DB migration/공항 import를 별도 승인된 배포 작업으로 실행하고 계약·공항 UUID를 확인한다.
7. VM 실행을 활성화한 plan을 검토하고 배포한다. DNS A record를 LB IP에 연결하고 인증서 ACTIVE를 기다린다.
   이후 이미지/secret 버전 교체는 VM 교체 없이 메타데이터 update 후 서비스 재시작으로 한다(`gcp-be/README.md`).
8. health, 실제 로그인, FE API, 후보 세 개 생성·적용, worker 재시작/lease, 버스 근거를 검증한 뒤 공개한다.

BE 기능 플래그는 실제 비밀 env의 명시적 설정이다. `MCP_ENABLED` 및
`SCHEDULE_GENERATION_ENABLED`는 VPN/TLS/JWT/DB 공항 데이터 확인 전 활성화하지 않는다.
단순 health 성공은 MCP와 데이터 준비 완료를 뜻하지 않는다.

## AWS MCP 연결 인계

현재 MCP 주소 `timing-jeju-ai`는 Docker 내부 이름이다. AWS Docker bridge IP를 GCP에 라우팅하면 안 된다.
다음 작업이 없으면 Terraform의 GCP VPN이 존재해도 MCP 요청은 실패한다.

1. GCP VPN root는 먼저 `tunnels = {}`로 HA gateway 두 interface IP와 Cloud Router만 준비한다.
2. AWS 담당자는 **기존 MCP VPC**에 적합한 VGW 또는 TGW를 선택하고 GCP interface별 customer
   gateway 두 개, 동적 BGP Site-to-Site VPN 연결 두 개를 준비한다. 각 연결에는 터널 두 개가 있다.
   기존 AWS state/블로그 VPC/다른 gateway를 임의로 가져오거나 변경하지 않는다.
3. AWS가 발급한 총 네 개 외부 IP, 각 BGP /30의 양쪽 IP, ASN과 PSK를 GCP VPN root에 전달한다.
   key `0`,`1`은 GCP interface0, `2`,`3`은 interface1이다. BGP /30은 AWS가 제공한 값을 그대로 쓴다.
4. PSK는 ephemeral `tunnel_psks`로 주입되어 `shared_secret_wo`에만 전달된다. plan/state에
   secret을 남기지 않는다. 회전 시 대응 `secret_version`도 증가시켜 양쪽을 맞춘다.
   AWS 측 도구가 생성하는 VPN config/PSK/state는 별도 비밀 취급 대상이다.
5. AWS route table에 BE subnet 반환 경로, security group에 **BE subnet → MCP TCP 포트만** 허용한다.
   GCP는 BE subnet만 BGP 광고한다. 기본 route나 전체 사내망을 광고하지 않는다.
6. MCP 컨테이너를 AWS 호스트의 VPC private IP에만 바인딩하거나 private proxy를 둔다.
   TLS 종료 위치를 바꾼다면 인증서 검증과 MCP Host allowlist 계약을 다시 검토한다.
   `0.0.0.0` 공개 바인딩/보안그룹 전 세계 허용/인증 해제는 하지 않는다.
7. BE 측 hostname을 해당 private IP로 해석하게 하고 인증서 SAN, MCP_ALLOWED_HOST,
   MCP_BASE_URL을 맞춘다. TLS trust 파일과 service JWT descriptor/key를 runtime에 마운트한다.
   사용자 JWT를 MCP로 전달하거나 테스트 운영자 키를 제품 키로 재사용하지 않는다.
8. 네 BGP session, AWS/GCP route, TCP, TLS, 무인증401, initialize/tools hash, 실제 생성 순으로 확인한다.

Google 공식 AWS HA VPN 구성은 두 interface와 총 네 tunnel을 사용한다.
[공식 구성 문서](https://docs.cloud.google.com/network-connectivity/docs/vpn/tutorials/create-ha-vpn-connections-google-cloud-aws)
[write-only VPN secret 계약](https://registry.terraform.io/providers/hashicorp/google/7.14.0/docs/resources/compute_vpn_tunnel)

## 비용·복구·검증 공백

- VM/디스크, LB, NAT, IPv4, HA VPN, AWS VPN/VGW 또는 TGW, egress, secret/registry/storage 비용이 발생한다.
  무료 구성이 아니다. 확정 프로젝트/traffic/토폴로지로 양사 계산기 견적을 만든 뒤 apply한다.
- 이미지 교체 시 서비스 재시작 동안 중단이 있다. 이전 image digest와 pinned secret version을 보관하고
  in-place update plan과 재시작 절차로 rollback한다. DB down migration은 자동 실행하지 않는다.
- VM/네트워크 해제는 DB 삭제나 비용 전체 종료를 의미하지 않는다. state bucket·이미지·비밀·AWS
  VPN 등 잔존 리소스를 따로 확인한다. `terraform destroy`를 일괄 복구 수단으로 사용하지 않는다.
- 애플리케이션 원문 로그는 수집하지 않는다. 운영 metric/상태/latency 전용 수집은 별도 검토 대상이다.
- 실제 cloud plan/apply, 서버 bootstrap, AWS route와 MCP 노출, public DNS/managed TLS,
  Supabase 접속 및 운영 FE→BE→MCP 검증은 이번 코드 작성의 완료 범위에 포함되지 않는다.

## 로컬 확인 기록 (2026-09-17)

- 세 root `terraform validate` 통과, Google provider 7.14.1 lockfile 보존.
- mock plan: BE 8개, VPN 4개, state bootstrap 1개 통과.
- Python 시작 스크립트/비밀 경계 회귀 11개 및 bash/Python 내장 구문 검사 통과.
- PR 리뷰 반영: 컨테이너 DNS(53) 허용, 배포 참조값의 in-place 메타데이터 이관, 종료 대기 시간 여유 확보.
- 독립 사전 검토의 API 활성화 의존성/기존 secret 문서 혼동 지적을 반영했다.
  최종 전체 코드의 정식 승인 기록은 아니며 실배포 검증으로 확대하지 않는다.
- 실제 cloud plan/apply, commit/push/PR 생성은 하지 않았다.

### 추가 완전성 검토

사설 subnet·region/zone 일치·VM 최소 용량·FQDN·Secret ID를 입력 단계에서 검증한다.
HTTP redirect URL map도 Compute API 활성화에 명시적으로 의존한다. 공인 subnet 입력이
기존에는 거부되지 않는 RED를 확인한 뒤 거부하도록 보완했다.

다음은 누락을 숨긴 자동화가 아니라 명시적인 운영 경계다:

- AWS 측 VPN/VGW 또는 TGW/route/SG 및 Docker 사설 endpoint: 인계 조건만 있으며 AWS Terraform은 없다.
- 도메인 DNS 레코드: 기존 DNS 운영자가 LB 출력 IP로 등록한다.
- HTTPS health 알림은 DNS/TLS 준비 후 검증된 알림 채널과 함께 활성화한다.
  Docker 재시작 의존성을 보완했으며, 살아 있는 프로세스의 health 장애는 알림과 운영자 복구로 처리한다.
  상세 절차는 gcp-be/README.md를 따른다. 실제 알림 수신과 systemd 복구는 대상 VM에서 검증해야 한다.
  애플리케이션 metrics, OS 취약점 패치 주기, VM 장애 자동 교체는 추가 운영 설계가 필요하다.
- Terraform 실행 계정·image publisher·IAP operator 권한: 기존 조직 계정을 전제로 별도 부여한다.
- 실제 VM bootstrap/configtree, TLS 인증서 발급, DB 연결, VPN 통신은 mock test만으로 검증되지 않는다.

따라서 이 코드는 GCP 배포 기반 초안의 검증 결과이며, 운영 배포 승인 또는 전체 종단 자동화 완료를 뜻하지 않는다.
