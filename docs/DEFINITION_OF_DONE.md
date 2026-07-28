# Definition of Done

다음 항목이 모두 충족되어야 개발 완료입니다.

- GitHub Issue와 Acceptance Criteria가 존재합니다.
- 최신 `develop`에서 규칙에 맞는 작업 브랜치를 만들었습니다.
- Red 실패, Green 통과, Refactor 후 전체 테스트 증거가 있습니다.
- 단위·슬라이스·통합·Architecture 테스트와 커버리지 검증이 통과했습니다.
- 포맷, 컴파일, `bootJar` 빌드가 통과했습니다.
- Spring 공개 API 변경 시 Swagger UI 통합 테스트와 `openApiDocs` 생성이 통과했습니다.
- 백엔드 저장소 구조 테스트와 Spring 전용 품질 게이트가 통과했습니다.
- FastAPI MCP 계약 변경이 포함되면 `jeju_AI` 저장소의 대응 Issue·PR과 호환 순서를 연결했습니다.
- Docker 이미지 빌드, Compose 실행, Health Check, 리소스 정리가 성공했습니다.
- 비밀정보와 불필요한 파일이 diff에 없습니다.
- 최신 HEAD의 품질 게이트 성공 기록이 있습니다.
- PR 전 Reviewer의 필수 수정사항이 0개이고 최신 HEAD가 APPROVED입니다.
- PR은 일반 작업이면 `develop`, Release면 `main`을 base로 합니다.
- 개발 문서와 한국어 Obsidian 개발 일지가 갱신되었습니다.
