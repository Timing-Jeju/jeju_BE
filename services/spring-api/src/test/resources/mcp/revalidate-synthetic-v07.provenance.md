# MCP pre-hash 경계 테스트 fixture

AI repository `jeju_algo`의 commit `2bb225fc78e65029db5208def2a683944f296e87`에 있는
`docs/examples/v0.7/synthetic/revalidate-normal.input.json`을 그대로 복사했다.
SHA-256: `89d40f659afb0543565b89279ba6244b712ed257f07f3cbde6e816dc141ec562`.
Pydantic에서 생성된0.7 계약의 합성 입력이며 실제 provider 응답이나 사용자 GPS가 아니다.
정상 호출 대조군은 이 fixture의 itinerary를 추출한 evaluate_jeju_day_trip 입력이다.
revalidate의 current_position 제거만으로 비수집 정상 입력이라고 판정하지 않는다.
progress.current_place_id/current_stop_id/current_route_id는 출처가 불명확하므로
nearest_place_id/nearest_stop_id와 함께 hash·SDK·audit 이전 거부 대상으로 검사한다.
이 경계 테스트는 schema guard를 mock한다. 실제 Pydantic 계약 적합성이나
0.8의 계획 item·leg·시간 기반 진행 입력 검증을 대체하지 않는다.
0.8 제품 계약이나 위치 전송 허용 근거로 사용하지 않는다.
