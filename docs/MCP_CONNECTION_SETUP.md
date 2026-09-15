# BE → private MCP 연결 설정 (#267)

이 문서는 배포 담당자에게 전달하는 **연결 준비 계약**이다. AWS 리소스 생성, DNS·방화벽
변경, BE 배포, 실제 키 발급·주입은 실행하지 않는다. FE/UI, 공개 REST/MCP JSON 계약과
일정 생성 업무 로직은 변경하지 않는다. 코드 준비와 실제 운영 연결 성공은 별개다.

## 기존 연결과 추가된 설정

일반 MCP와 일정 생성 전용 MCP 클라이언트는 같은 MCP 전용 JDK HTTP 클라이언트와
서비스 JWT 필터를 사용한다. 주소 allowlist, HTTPS, RS256, tools/list 계약 검사와
생성 최소 165초 제한은 유지한다. 사용자 JWT나 외부 공급자 API 키는 전달하지 않는다.
클라이언트는 redirect를 따라가지 않으며 연결 수립 제한은 10초다.

`MCP_TLS_TRUST_CERTIFICATE_FILE`을 비우면 JVM 기본 신뢰를 사용한다. 설정하면
해당 PEM 파일의 인증서만 MCP의 신뢰 저장소에 사용한다. 다른 외부 HTTP 클라이언트와
JVM 전역 SSLContext는 변경하지 않는다. 일반·생성 클라이언트 모두 같은 설정을 사용한다.
chain/hostname 검증을 끄는 옵션이나 HTTP fallback은 없다.

PEM은 컨테이너 내부 절대 경로의 읽기 가능한 파일이어야 하며 최대 64 KiB다.
여러 인증서가 들어 있는 PEM bundle을 허용한다. 파일 누락·빈 내용·형식 오류·만료는
MCP 활성화 시 구성 오류로 실패한다. 오류에 경로·인증서 내용·원천 예외를 노출하지 않는다.
PEM은 시작 시 읽으므로 TLS 인증서 교체 후에는 BE를 재시작한다. JWT descriptor는
기존 reload 동작을 유지한다. TLS 인증서와 JWT 서명 키는 서로 다른 용도다.

## 외부에서 제공할 값

아래는 실제 비밀을 포함하지 않는 예시다. 저장소 기본 `MCP_ENABLED=false`는 유지하며,
담당자가 사전 조건을 충족한 뒤 실행 환경에서만 활성화한다.

```dotenv
MCP_ENABLED=true
# origin만 지정한다. /mcp는 transport가 추가하므로 붙이지 않는다.
MCP_BASE_URL=https://timing-jeju-ai:8443
MCP_ALLOWED_HOST=timing-jeju-ai
# MCP verifier와 합의한 값. 운영자 검증용 개인키를 재사용하지 않는다.
MCP_JWT_ISSUER=timing-jeju-spring
MCP_JWT_AUDIENCE=timing-jeju-mcp
MCP_JWT_SIGNING_KEY_DESCRIPTOR_FILE=/run/secrets/mcp/signing-descriptor.json
MCP_TLS_TRUST_CERTIFICATE_FILE=/run/secrets/mcp/trust.pem
MCP_JWT_LIFETIME=2m
MCP_REQUEST_TIMEOUT=35s
MCP_GENERATION_REQUEST_TIMEOUT=165s
```

`timing-jeju-ai`는 연결 구조를 설명하는 예시 host다. 이름을 설정하는 것만으로 DNS나
네트워크가 생기지 않는다. 배포 담당자가 BE에서 해석 가능한 private DNS/서비스명을
선정하고 MCP 서버 인증서 SAN, `MCP_BASE_URL`, `MCP_ALLOWED_HOST`를 일치시킨다.
기존 기본 8000 포트를 사용하는 환경에서는 base URL을 그대로 사용할 수 있다.
운영자 검증용 issuer/audience와 이 예시 값이 다르면 MCP verifier의 허용 값을
BE 전용으로 합의해야 하며, 예시를 복사했다고 인증이 준비된 것은 아니다.

JWT의 subject는 `backend-worker`, scope는 `jeju:mcp:invoke`로 고정되어 있다.
descriptor의 형식은 다음과 같고 파일 내용·실키를 Git에 추가하지 않는다.

```json
{
  "kid": "backend-key-id",
  "privateKeyFile": "/run/secrets/mcp/service-signing.pem"
}
```

키는 PKCS#8 RSA 개인키다. MCP JWKS에 동일 `kid`의 대응 공개키가 등록되어야 한다.
PEM 신뢰 파일은 담당자가 별도 신뢰 경로로 검증한 CA 또는 서버 인증서다.
검증을 끈 다운로드 결과를 자동으로 신뢰하지 않는다. 파일은 컨테이너 실행 UID가 읽을 수
있게 최소 권한으로 관리하고 read-only로 mount한다. 로그·PR·채팅에 내용이나 토큰을 넣지 않는다.

## Compose 환경 전달

canonical `compose.yml`의 `api.environment`는 MCP 설정을 전달한다. `.env`는
Compose 치환 입력일 뿐 모든 값이 자동으로 컨테이너에 전달되는 것은 아니다.
`docker-compose.yml` 역사 예제가 아니라 명시적으로 `-f compose.yml`을 사용한다.
실제 secret mount는 담당자의 별도 로컬 override에서 정의한다. 다음은 실행하지 않은 예시다.

```yaml
services:
  api:
    volumes:
      - type: bind
        source: /absolute/operator-managed/mcp-secrets
        target: /run/secrets/mcp
        read_only: true
        bind:
          create_host_path: false
```

마운트할 디렉터리에는 BE용 descriptor·서명 개인키·신뢰 인증서만 둔다. MCP의 DB 비밀,
외부 API 키, 운영자 JWT 개인키는 공유하지 않는다. 실제 `.env`, override와 secret 디렉터리는
Git 밖에서 관리한다. 환경값이 포함된 `docker compose config` 전체 출력도 공개하지 않는다.

## 배포 담당자 확인 순서

1. BE → MCP private 네트워크 경로, DNS, 포트 접근과 인증서 SAN을 준비한다.
   인터넷에 MCP/DB 포트를 개방하거나 `allowed-host` 검사를 완화하는 작업은 이 PR에 없다.
2. BE 전용 JWT 키/JWKS kid, issuer/audience와 secret mount를 맞춘다.
3. TLS 검증을 유지한 상태로 `/health`, `/ready`와 인증 없는 `/mcp`의 401을 확인한다.
   HTTP health 성공만으로 데이터 준비나 BE 계약 호환을 판정하지 않는다.
4. BE에서 initialize → tools/list 계약 검사 → search 호출을 검증한다.
   생성 호출은 최소 165초 요청 제한과 기존 worker 실행 한도를 유지한다.
5. 데이터와 승인된 공항 canonical ID를 확인한 후 별도 `SCHEDULE_GENERATION_ENABLED`를
   활성화하고 FE 생성 → 후보 3개 → 선택 적용을 운영 smoke로 검증한다.
   이 PR은 공항 ID를 지정하거나 기능 플래그를 자동 활성화하지 않는다.

TLS 성공, JWT 성공, JSON 계약 일치, 데이터 준비, 업무 흐름 성공은 각각 확인해야 한다.
이번 테스트는 로컬 TLS fixture와 기존 계약 회귀이며 배포된 AWS MCP와의 종단 검증은 아니다.

## 구현 근거

Spring의 [JdkClientHttpConnector API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/client/reactive/JdkClientHttpConnector.html)를
통해 MCP의 WebClient에 전용 HTTP 클라이언트를 주입한다. 전역 truststore 변경 대신
클라이언트 단위 신뢰 범위를 사용한다.
