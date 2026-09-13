"""관심 장소 삭제의 버전 선행조건과 충돌 계약을 검사한다."""
from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from scripts import validate_saved_places_contract as validator

ROOT = Path(__file__).resolve().parents[2]


class SavedPlaceDeleteCasTest(unittest.TestCase):
    def test_delete_requires_strong_etag_and_documents_conflict(self) -> None:
        """삭제는 단일 strong ETag와 충돌 오류를 공개 계약으로 요구한다."""
        contract = json.loads((ROOT / validator.CONTRACT_RELATIVE).read_text())
        endpoint = next(e for e in contract['endpoints'] if e['method'] == 'DELETE')
        self.assertEqual('DeleteSavedPlaceHeaders', endpoint['headersSchema'])
        self.assertEqual(['If-Match'], contract['schemas']['DeleteSavedPlaceHeaders']['required'])
        self.assertIn((409, 'SAVED_PLACE_VERSION_CONFLICT'), [(p['status'], p['code']) for p in endpoint['problems']])
        self.assertEqual([204], endpoint['successStatuses'])
        self.assertEqual('none', endpoint['successSchema'])
        runtime = json.loads((ROOT / 'scripts/openapi_frontend_runtime_manifest.json').read_text())
        operation = runtime['operations']['DELETE /api/v1/me/saved-places/{placeId}']
        self.assertIn(409, operation['statuses'])
        self.assertEqual('SAVED_PLACE_VERSION_CONFLICT', operation['problems']['409'][0])

    def test_delete_fixture_rejects_missing_or_invalid_etag(self) -> None:
        """삭제 요청 예제도 누락·약한·다중 ETag를 정상 계약으로 허용하지 않는다."""
        contract = json.loads((ROOT / validator.CONTRACT_RELATIVE).read_text())
        fixture = json.loads((ROOT / validator.FIXTURE_ROOT_RELATIVE / 'request.json').read_text())
        for value in (None, '*', 'W/"v1"', '"v1","v2"', 'unquoted'):
            with self.subTest(value=value):
                request = copy.deepcopy(fixture)
                if value is None:
                    request['delete']['headers'].pop('If-Match', None)
                else:
                    request['delete']['headers']['If-Match'] = value
                errors: list[str] = []
                validator._validate_request_fixture(request, contract['schemas'], errors)
                self.assertTrue(any('delete' in error for error in errors), errors)
