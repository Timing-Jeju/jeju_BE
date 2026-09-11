"""구현 준비 상태에 따른 OpenAPI 검증 경계를 검사한다."""
import unittest
from unittest import mock
from pathlib import Path
from scripts.validate_openapi_frontend_readiness import Validator

ROOT = Path(__file__).resolve().parents[2]


class OpenApiProjectionReadinessTest(unittest.TestCase):
    def test_not_ready도_endpoint_검증을_호출하고_canonical_비교만_분리한다(self):
        """미구현 도메인도 status와 Problem 검증 경로에서 빠지지 않는다."""
        validator = Validator({}, 33, ROOT)
        with mock.patch.object(validator, 'validate_contract_endpoint') as endpoint:
            validator.validate_contract_authority()
        calls = {call.args[0]: call for call in endpoint.call_args_list}
        self.assertFalse(calls[('GET', '/api/v1/places')].kwargs['canonical_ready'])
        self.assertFalse(calls[('GET', '/api/v1/weather/forecast')].kwargs['canonical_ready'])
        self.assertIn(('GET', '/api/v1/trips/{tripId}/schedule'), calls)

    def test_readiness_누락은_명시적인_구성오류다(self):
        """catalog 상태가 없을 때 조용히 skip하지 않는다."""
        validator = Validator({}, 9, ROOT)
        read = validator.read_authority_json
        def resource(path):
            value = read(path)
            if path == 'docs/contracts/rest/catalog.json':
                value.pop('domainContracts')
            return value
        with mock.patch.object(validator, 'read_authority_json', side_effect=resource):
            validator.validate_contract_authority()
        self.assertTrue(any('readiness' in error for error in validator.errors))

    def test_not_ready에서도_status_누락은_실패한다(self):
        """canonical schema 비교를 중지해도 runtime 성공 응답 누락을 잡는다."""
        key = ('GET', '/api/v1/weather/forecast')
        validator = Validator({'paths': {key[1]: {'get': {'responses': {}}}}}, 9, ROOT)
        catalog = validator.read_authority_json('docs/contracts/rest/catalog.json')
        contract = validator.read_authority_json('docs/contracts/domains/weather-forecast/contract.json')
        manifest = validator.read_authority_json('scripts/openapi_frontend_runtime_manifest.json')
        validator.runtime_manifest = manifest['operations']
        validator.runtime_problem_definitions = manifest['runtimeProblemDefinitions']
        endpoint = next(row for row in contract['endpoints'] if (row['method'], row['path']) == key)
        catalog_endpoint = next(row for row in catalog['endpoints'] if (row['method'], row['path']) == key)
        with mock.patch.object(validator, 'validate_contract_parameters') as parameters, mock.patch.object(validator, 'validate_contract_body') as body, mock.patch.object(validator, 'validate_contract_success') as success:
            validator.validate_contract_endpoint(key, catalog_endpoint, endpoint, contract['schemas'],
                validator.domain_problem_pairs(contract, endpoint, key), canonical_ready=False)
        parameters.assert_not_called()
        body.assert_not_called()
        success.assert_not_called()
        self.assertTrue(any('status projection' in error for error in validator.errors))

    def test_중복_비정상_근거는_Java와_같이_거부한다(self):
        """상태 문자열과 근거가 잘못된 경우 양쪽 도구가 fail-closed한다."""
        for status, evidence in [('ready', None), ('ready', {}), ('not-ready', {}), ('unknown', None), (True, None)]:
            validator = Validator({}, 9, ROOT)
            row = {'domain': 'weather', 'readiness': {'implementation': {'status': status, 'evidence': evidence}}}
            self.assertIsNone(validator.projection_readiness({'domainContracts': [row]}))
        row = {'domain': 'weather', 'readiness': {'implementation': {'status': 'not-ready', 'evidence': None}}}
        validator = Validator({}, 9, ROOT)
        self.assertIsNone(validator.projection_readiness({'domainContracts': [row, row]}))

    def test_미래_오류와_status가_바뀌어도_not_ready는_runtime을_검증한다(self):
        """미구현 selector의 새 오류 코드는 현행 날씨 응답 검증에 투영하지 않는다."""
        key = ('GET', '/api/v1/weather/forecast')
        validator = Validator({}, 9, ROOT)
        manifest = validator.read_authority_json('scripts/openapi_frontend_runtime_manifest.json')
        runtime = manifest['operations']['GET /api/v1/weather/forecast']
        responses = {'200': {}}
        for status, (code, kind) in runtime['problems'].items():
            responses[status] = {'content': {'application/problem+json': {'example': {'code': code, 'type': kind}}}}
        validator.document = {'paths': {key[1]: {'get': {'responses': responses}}}}
        validator.runtime_manifest = manifest['operations']
        validator.runtime_problem_definitions = manifest['runtimeProblemDefinitions']
        validator.validate_contract_endpoint(key, {'responses': {'success': [202], 'errors': [409]}},
            {'errorMatrix': {'409': ['INVALID_WEATHER_SELECTOR']}}, {},
            {('INVALID_WEATHER_SELECTOR', None)}, canonical_ready=False)
        self.assertEqual([], validator.errors)
        responses['400']['content']['application/problem+json']['example']['code'] = 'UNAPPROVED_CODE'
        validator.validate_contract_endpoint(key, {'responses': {}}, {}, {}, set(), canonical_ready=False)
        self.assertTrue(any('runtime representative' in error for error in validator.errors))

    def test_weather_runtime_manifest가_공개_선택자_오류를_고정한다(self):
        """실제 selector 구현의 오류 코드와 참조 없음 상태를 검증한다."""
        validator = Validator({}, 9, ROOT)
        manifest = validator.read_authority_json('scripts/openapi_frontend_runtime_manifest.json')
        weather = manifest['operations']['GET /api/v1/weather/forecast']
        self.assertIn(404, weather['statuses'])
        self.assertEqual('INVALID_WEATHER_SELECTOR', weather['problems']['400'][0])
        self.assertEqual('WEATHER_REFERENCE_NOT_FOUND', weather['problems']['404'][0])
