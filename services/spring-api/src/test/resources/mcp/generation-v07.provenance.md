# 생성 응답 계약 fixture 출처

- 원본 저장소: Timing-Jeju/jeju_AI, commit 45f585ae68ca1d5b997b6645d1ed97dadfeb48fa.
- `generation-v07.output-schema.json`: Pydantic에서 생성한
  `docs/contracts/day-trip-recommendations.schema.json`의 복사본이다. 테스트에서 정규화 SHA-256을
  실제 BE MCP manifest의 recommend output hash와 비교한다. 별도 수작업 Schema가 아니다.
- `generation-v07.synthetic-output.json`: 생성된
  `docs/examples/v0.7/synthetic/recommend.output.json`의 합성 예제다. 실제 사용자·TMAP 원본이 아니다.
  테스트에서 ID 필드에 한정해 hotel/required/a/b/meal/rest를 합성 TourAPI fact ID로 매핑한다.
  실제 장소 content ID라는 주장이 아니며 외부 데이터 소스를 호출하지 않는다.
- 공식 SDK는 mock transport로 구동한다. 입력 Schema는 이 조합 테스트용이며 전체 요청 조립/
  MCP 서버 실행/DB 후보 저장/worker 복구/FE 실연결을 검증했다는 의미가 아니다.
