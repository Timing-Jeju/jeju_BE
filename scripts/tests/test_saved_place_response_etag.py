"""찜 목록 버전 복원과 과거 생성 응답 호환성을 검증한다."""
import json
import unittest
from pathlib import Path
from scripts.validate_saved_places_contract import _validate_value

ROOT = Path(__file__).resolve().parents[2]
class SavedPlaceResponseEtagContractTest(unittest.TestCase):
    def test_목록과_수정은_ETag가_필수이며_과거_POST만_union으로_허용한다(self):
        """새 목록에는 ETag를 요구하고 과거 receipt를 재작성하지 않는 계약을 고정한다."""
        contract=json.loads((ROOT/'docs/contracts/domains/saved-places/contract.json').read_text())
        schemas=contract['schemas']
        self.assertIn('etag',schemas['SavedPlace']['required'])
        old=json.loads((ROOT/'fixtures/contracts/saved-places/legacy-create-replay.json').read_text())
        errors=[]
        _validate_value(old,schemas['SavedPlaceCreateResponse'],schemas,'POST',errors)
        self.assertEqual([],errors)
        errors=[]
        _validate_value(old,schemas['SavedPlace'],schemas,'GET',errors)
        self.assertTrue(errors)
        post=next(e for e in contract['endpoints'] if e['method']=='POST')
        self.assertEqual('SavedPlaceCreateResponse',post['successSchema'])

    def test_신규_mutation_응답_body와_HTTP_ETag는_동일하다(self):
        """공개 생성과 수정 fixture는 header와 body에 같은 opaque 버전을 제공한다."""
        fixture=json.loads((ROOT/'fixtures/contracts/saved-places/success.json').read_text())
        for kind in ['create','patch']:
            self.assertEqual(fixture[kind]['headers']['ETag'],fixture[kind]['body'].get('etag'))
