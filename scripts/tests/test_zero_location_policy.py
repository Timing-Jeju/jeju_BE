"""위치 무수집 v2의 승인·금지 경계와 역사 보존을 검증한다."""
import copy
import importlib.util
import json
import re
from pathlib import Path
import unittest
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / 'docs/contracts/domains/location-noncollection/contract.json'
VALIDATOR = ROOT / 'scripts/validate_zero_location_policy.py'


class ZeroLocationPolicyTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(VALIDATOR.is_file(), '위치 무수집 정책 검증기가 필요합니다')
        spec = importlib.util.spec_from_file_location('zero_location_policy', VALIDATOR)
        self.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.validator)
        self.contract = json.loads(CONTRACT.read_text())

    def test_legacy_policy_cannot_be_the_active_contract(self):
        """위치 저장을 허용하던 v1은 현행 정책으로 통과할 수 없다."""
        old = json.loads((ROOT / 'docs/contracts/domains/location-retention/contract.json').read_text())
        with self.assertRaises(ValueError):
            self.validator.validate_contract(old)
        self.validator.validate_contract(self.contract)

    def test_explicit_reference_and_gps_derived_reference_are_distinct(self):
        """동일 장소 ID라도 명시 선택과 GPS 최근접 자동 선택을 구별한다."""
        self.validator.validate_flow(self.contract, 'weather', 'explicit_selection', ['placeId'])
        with self.assertRaises(ValueError):
            self.validator.validate_flow(self.contract, 'weather', 'gps_derived', ['placeId'])
        with self.assertRaises(ValueError):
            self.validator.validate_flow(self.contract, 'weather', 'unknown', ['placeId'])

    def test_unknown_nested_fields_and_aliases_are_rejected(self):
        """명시 선택이라도 숨긴 위치 필드나 위치 별칭은 통과하지 않는다."""
        for field in ['lat', 'currentLocation', 'facts.location', 'metadata.regionCode', 'currentPlaceId', 'GRID_100M', 'geohash', 'fingerprint', 'locationSupplied']:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validator.validate_flow(self.contract, 'execution', 'planned_reference', ['tripItemId', field])

    def test_public_projection_is_allowed_but_cannot_launder_user_location(self):
        """공식 공개 좌표와 사용자 위치를 같은 컬럼 이름으로 섞지 않는다."""
        self.validator.validate_flow(self.contract, 'public_facts', 'approved_public_source', ['placeId', 'latitude', 'longitude'])
        for origin in ['gps_derived', 'explicit_selection', 'unknown']:
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                self.validator.validate_flow(self.contract, 'public_facts', origin, ['latitude', 'longitude'])

    def test_derived_hash_and_observability_do_not_preserve_location(self):
        """위치에서 파생한 hash와 지역은 로그·metric·trace에도 허용하지 않는다."""
        for surface in ['command_hash', 'mcp_hash', 'cache', 'observability']:
            with self.subTest(surface=surface), self.assertRaises(ValueError):
                self.validator.validate_flow(self.contract, surface, 'gps_derived', ['hash'])
        with self.assertRaises(ValueError):
            self.validator.validate_flow(self.contract, 'observability', 'planned_reference', ['placeId'])

    def test_contract_does_not_weaken_storage_or_purge_order(self):
        """보관 허용과 파생 hash 보존 및 purge 순서 변경을 거부한다."""
        for mutate in [
            lambda c: c['server'].update(receiveUserLocation=True),
            lambda c: c['server'].update(storeDerivedLocation=True),
            lambda c: c['migration']['steps'].reverse(),
            lambda c: c['migration'].update(onAmbiguousActiveLineage='delete'),
            lambda c: c['notification']['eligibility'].append('location_consent'),
            lambda c: c['server'].update(unknownPolicy=True),
        ]:
            changed = copy.deepcopy(self.contract)
            mutate(changed)
            with self.assertRaises(ValueError):
                self.validator.validate_contract(changed)

    def test_weather_selectors_are_exactly_one_and_location_free(self):
        """날씨는 명시 지역·장소·계획 항목 중 하나만 선택한다."""
        for fields in [[], ['placeId', 'regionCode'], ['latitude', 'longitude'], ['placeId', 'extra']]:
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                self.validator.validate_flow(self.contract, 'weather', 'explicit_selection', fields)
        self.validator.validate_flow(self.contract, 'weather', 'planned_reference', ['tripItemId', 'dateTime'])

    def test_fixtures_cover_all_surfaces_and_fail_closed(self):
        """모든 표면의 허용·거부 fixture를 검사하고 빈 검사 목록을 거부한다."""
        fixture = json.loads((ROOT / 'fixtures/contracts/location-noncollection/policy.json').read_text())
        self.validator.validate_fixture(self.contract, fixture)
        with self.assertRaises(ValueError):
            self.validator.validate_fixture(self.contract, {'cases': []})
        invalid = copy.deepcopy(fixture)
        invalid['cases'][0]['fields'].append('unknown')
        with self.assertRaises(ValueError):
            self.validator.validate_fixture(self.contract, invalid)

    def test_canonical_links_and_legacy_fingerprints_are_preserved(self):
        """핵심 문서와 REST catalog가 v2를 가리키고 v1 JSON은 바뀌지 않는다."""
        self.validator.validate_repository(ROOT, self.contract)

    def test_cli_rejects_malformed_input_without_echoing_values(self):
        """잘못된 계약은 원문이나 traceback 없이 안전하게 실패한다."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'contract.json'
            path.write_text('{"private_marker": "not-a-contract"}')
            result = subprocess.run([sys.executable, str(VALIDATOR), '--contract', str(path)], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertNotIn('private_marker', result.stderr)
            self.assertNotIn('Traceback', result.stderr)

    def test_both_quality_gates_execute_the_current_policy(self):
        """두 운영체제 품질 게이트가 역사 검사와 현행 검사를 함께 수행한다."""
        for filename in ['scripts/quality-gate.sh', 'scripts/quality-gate.ps1']:
            with self.subTest(filename=filename):
                source = (ROOT / filename).read_text()
                self.assertIn('scripts/validate_zero_location_policy.py', source)
                self.assertIn('scripts/validate_location_retention_contract.py', source)

    def test_places_saved_filter_does_not_require_location(self):
        """찜한 장소 필터는 위치 수집 없이 허용한다."""
        self.validator.validate_flow(self.contract, 'places', 'explicit_selection', ['query', 'savedOnly'])

    def test_canonical_places_request_is_location_free(self):
        """공개 장소 요청과 cursor에 사용자 위치 필드가 없음을 검증한다."""
        places = json.loads((ROOT / 'docs/contracts/domains/places/contract.json').read_text())
        expected = {'query', 'category', 'regionCode', 'cursor', 'size', 'savedOnly'}
        self.assertEqual(expected, set(places['schemas']['PlacesListRequest']['properties']))
        self.assertEqual(expected, set(places['endpoints'][0]['query']))
        self.assertFalse({'lat', 'lng', 'radiusMeters'} & set(places['endpoints'][0]['pagination']['cursorScope']))
        self.assertNotIn('distanceMeters', places['schemas']['PlaceListItem']['properties'])
        self.assertEqual({'lat', 'lng'}, set(places['schemas']['Location']['properties']))

    def test_canonical_weather_has_exactly_one_planned_selector(self):
        """날씨 공개 계약이 GPS 대신 정확히 하나의 계획 selector를 받는다."""
        weather = json.loads((ROOT / 'docs/contracts/domains/weather-forecast/contract.json').read_text())
        query = weather['schemas']['WeatherForecastQuery']
        self.assertEqual({'regionCode', 'placeId', 'tripItemId', 'dateTime'}, set(query['properties']))
        self.assertEqual([{'required': [key]} for key in ['regionCode', 'placeId', 'tripItemId']], query['oneOf'])
        self.assertEqual(['dateTime'], query['required'])
        self.assertEqual(['INVALID_WEATHER_SELECTOR'], weather['endpoints'][0]['errorMatrix']['400'])
        self.assertEqual('not-ready', weather['readiness']['implementation']['status'])

    def test_fcm_eligibility_does_not_require_location_consent(self):
        """FCM은 활성 기기·OS 허용·서버 선택만으로 발송 자격을 판단한다."""
        fcm = json.loads((ROOT / 'docs/contracts/domains/fcm-departure-notification/contract.json').read_text())
        self.assertEqual(['activeDevice', 'osNotificationPermissionGranted', 'serverDepartureNotificationEnabled'], fcm['consentPolicy']['requiredSignals'])
        self.assertNotIn('locationConsentEvaluation', fcm['consentPolicy'])
        self.assertNotIn('LOCATION_CONSENT_INVALID', fcm['jobPolicy']['cancelReasons'])

    def test_places_cursor_cannot_reuse_gps_legacy_scope(self):
        """위치 파생 legacy cursor를 새 계약의 입력으로 재사용하지 않는다."""
        places = json.loads((ROOT / 'docs/contracts/domains/places/contract.json').read_text())
        pagination = places['endpoints'][0]['pagination']
        self.assertEqual('places-location-free/v2', pagination['cursorFormatVersion'])
        self.assertTrue(places['schemas']['PlacesListRequest']['properties']['cursor']['pattern'].startswith('^plc2'))
        self.assertEqual('discard before network; start without cursor', pagination['legacyClientAction'])

    def test_active_progress_request_examples_do_not_accept_location(self):
        """실행·빈 시간·복구·라이브 예시가 위치 없는 정책 필드만 사용한다."""
        source = (ROOT / 'docs/designs/timing-jeju-backend-rdb-api-spec.md').read_text()
        self.validator.validate_progress_examples(self.contract, source)

    def test_progress_example_unknown_nested_and_legacy_fields_fail(self):
        """문서 요청에 GPS 필드나 계획 ID 안의 중첩 위치를 다시 넣으면 실패한다."""
        source = (ROOT / 'docs/designs/timing-jeju-backend-rdb-api-spec.md').read_text()
        mutations = [
            source.replace('"tripItemId":', '"currentLocation": {}, "tripItemId":'),
            re.sub(r'"tripItemId": "[^"]+"', '"tripItemId": {"lat": 0}', source),
        ]
        for changed in mutations:
            with self.subTest(changed=changed is mutations[0]), self.assertRaises(ValueError):
                self.validator.validate_progress_examples(self.contract, changed)


if __name__ == '__main__':
    unittest.main()
