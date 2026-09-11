#!/usr/bin/env python3
"""위치 무수집 계약의 정보 흐름 명세를 검사한다. HTTP middleware가 아니다."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = 'docs/contracts/domains/location-noncollection/contract.json'
FIXTURE_PATH = 'fixtures/contracts/location-noncollection/policy.json'
CONTRACT_DIGEST = '0318d14683fc3651b188ee13e46a27b7ab142ccee13a3d5cc09e16559cc6fe3c'
HISTORICAL_DIGEST = 'd98c67bfcd691db4dc2069cc7df085c5bbd7b23ad6525979a912882fb6128aee'
LINKED_DOCUMENTS = (
    'docs/ARCHITECTURE.md',
    'docs/designs/timing-jeju-backend-rdb-api-spec.md',
    'docs/designs/timing-jeju-spring-fastapi-integration-contract.md',
    'docs/DEFINITION_OF_DONE.md',
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def validate_contract(contract):
    # 모든 중첩 정책 필드·값을 고정하여 unknown key와 boolean 변경도 거부한다.
    require(isinstance(contract, dict) and digest(contract) == CONTRACT_DIGEST,
            '위치 무수집 v2의 닫힌 계약 또는 정책 fingerprint가 일치하지 않습니다.')


def validate_flow(contract, surface, origin, fields):
    """신뢰 가능한 코드 감사 provenance를 검사하며 client 선언을 인증 근거로 쓰지 않는다."""
    validate_contract(contract)
    require(isinstance(surface, str) and surface in contract['surfaces'], '알 수 없는 처리 표면입니다.')
    rule = contract['surfaces'][surface]
    require(isinstance(origin, str) and origin in rule['origins'], '위치 출처가 금지되었거나 검증되지 않았습니다.')
    require(isinstance(fields, list) and fields and all(isinstance(field, str) for field in fields), '비어 있지 않은 필드 목록이 필요합니다.')
    require(len(fields) == len(set(fields)), '중복 필드는 허용하지 않습니다.')
    require(set(fields) <= set(rule['fields']), '미지 또는 금지 필드가 있습니다.')
    if rule['exactlyOne']:
        require(len(set(fields) & set(rule['exactlyOne'])) == 1, 'selector는 정확히 하나여야 합니다.')


def validate_fixture(contract, fixture):
    validate_contract(contract)
    require(isinstance(fixture, dict) and set(fixture) == {'cases'}, 'fixture root가 닫혀 있지 않습니다.')
    cases = fixture['cases']
    require(isinstance(cases, list) and cases, 'fixture가 비어 있습니다.')
    seen = set()
    coverage = set()
    for case in cases:
        require(isinstance(case, dict) and set(case) == {'id','surface','origin','fields','allowed'}, 'fixture case가 닫혀 있지 않습니다.')
        require(isinstance(case['id'], str) and case['id'] and case['id'] not in seen, 'fixture ID가 유일하지 않습니다.')
        require(isinstance(case['surface'], str) and case['surface'] in contract['surfaces'], 'fixture 표면이 올바르지 않습니다.')
        require(type(case['allowed']) is bool, 'fixture 예상값은 boolean이어야 합니다.')
        seen.add(case['id'])
        allowed = True
        try:
            validate_flow(contract, case['surface'], case['origin'], case['fields'])
        except ValueError:
            allowed = False
        require(allowed == case['allowed'], '위치 무수집 fixture의 허용·금지 결과가 일치하지 않습니다.')
        coverage.add((case['surface'], allowed))
    expected = {(surface, allowed) for surface in contract['surfaces'] for allowed in (True, False)}
    require(coverage == expected, '모든 표면에 허용·거부 fixture가 필요합니다.')


def validate_progress_examples(contract, source):
    """미구현 v2 요청 예시가 정책과 일치하는지 검사하며 런타임 완료는 주장하지 않는다."""
    for section, surface in [('15.1', 'spare_time'), ('15.3', 'recovery'), ('16.2', 'execution'), ('16.3', 'live')]:
        pattern = r'^### ' + re.escape(section) + r' `POST [^\n]+\n(.*?)(?=^### |^## |\Z)'
        matches = re.findall(pattern, source, re.M | re.S)
        require(len(matches) == 1, '진행 요청 문서 구간은 정확히 하나여야 합니다.')
        examples = re.findall(r'Request:\s*```json\s*(.*?)\s*```', matches[0], re.S)
        require(len(examples) == 1, '진행 요청 예시는 정확히 하나여야 합니다.')
        request = json.loads(examples[0])
        require(isinstance(request, dict), '진행 요청 예시는 객체여야 합니다.')
        expected = set(contract['surfaces'][surface]['fields']) - {'tripId'}
        require(set(request) == expected, '진행 요청 예시는 계획 참조 정책과 일치해야 합니다.')
        require(all(isinstance(value, str) and value for value in request.values()), '예시의 계획 ID·시각·수동 상태는 비어 있지 않은 문자열이어야 합니다.')
        validate_flow(contract, surface, 'planned_reference', list(request))


def validate_repository(root, contract):
    validate_contract(contract)
    historical = json.loads((root / contract['supersedes']['contract']).read_text())
    require(digest(historical) == HISTORICAL_DIGEST, '역사적 v1 계약을 수정하면 안 됩니다.')
    for filename in LINKED_DOCUMENTS:
        require('location-noncollection/contract.md' in (root / filename).read_text(), '핵심 문서의 현행 정책 링크가 없습니다.')
    validate_progress_examples(contract, (root / 'docs/designs/timing-jeju-backend-rdb-api-spec.md').read_text())
    catalog = json.loads((root / 'docs/contracts/rest/catalog.json').read_text())
    require(catalog['commonRules'].get('locationPolicy') == CONTRACT_PATH, 'REST catalog의 현행 위치 정책이 일치하지 않습니다.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--contract', type=Path, default=ROOT / CONTRACT_PATH)
    parser.add_argument('--fixture', type=Path, default=ROOT / FIXTURE_PATH)
    args = parser.parse_args()
    try:
        contract = json.loads(args.contract.read_text())
        fixture = json.loads(args.fixture.read_text())
        validate_contract(contract)
        validate_fixture(contract, fixture)
        validate_repository(ROOT, contract)
    except (ValueError, OSError, KeyError, TypeError, RecursionError):
        print('위치 무수집 v2 검증 실패: 계약·fixture·현행 링크를 확인하세요.', file=sys.stderr)
        return 1
    print('위치 무수집 v2 계약 검증 성공; 런타임 제거 완료를 의미하지 않습니다.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
