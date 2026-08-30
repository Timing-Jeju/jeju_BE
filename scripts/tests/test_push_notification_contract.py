from __future__ import annotations

import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts/validate_push_notification_contract.py"
CONTRACT = ROOT / "docs/contracts/domains/push-notifications/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"


def load_validator():
    spec = importlib.util.spec_from_file_location("push_contract", VALIDATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PushNotificationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        cls.catalog = json.loads(CATALOG.read_text(encoding="utf-8"))

    def validate(self, contract=None, catalog=None):
        return self.validator.validate_contract(
            copy.deepcopy(contract or self.contract), copy.deepcopy(catalog or self.catalog), ROOT
        )

    def test_repository_contract_and_catalog_projection_are_exact(self):
        self.assertEqual([], self.validate())

    def test_each_wire_security_and_database_field_is_mutation_sensitive(self):
        mutations = (
            lambda c: c["limits"]["deviceId"].update(pattern=".*"),
            lambda c: c["limits"]["registrationToken"].update(maxBytes=4097),
            lambda c: c["limits"]["registrationToken"]["envelope"].update(maxChars=8192),
            lambda c: c["owner"].update(apiPrincipal="request body userId"),
            lambda c: c["database"].update(ownerColumn="device_id"),
            lambda c: c["database"].update(writerRole="authenticated"),
            lambda c: c["database"].update(clientWritePolicyCount=4),
            lambda c: c["database"]["migrations"].pop(),
            lambda c: c["schemas"]["PushDeviceRegistrationRequest"].update(additionalProperties=True),
            lambda c: c["schemas"]["PushDeviceRegistrationRequest"]["properties"]["appVersion"].update(maxLength=51),
            lambda c: c["endpointContracts"][0]["success"].update(schema="WrongResponse"),
            lambda c: c["endpointContracts"][0]["problems"][0].update(status=422),
            lambda c: c["endpointContracts"][0]["problems"][0].update(code="WRONG_CODE"),
            lambda c: c["endpointContracts"][0]["problems"][0].update(condition="wrong condition"),
            lambda c: c["runtimeDrift"].update(implementationReady=False),
            lambda c: c["cryptoFailure"].update(status=500),
            lambda c: c["legalSelection"]["order"].reverse(),
            lambda c: c["legalSelection"].update(snapshotIsolation="READ_COMMITTED"),
            lambda c: c["legalSelection"].update(concurrentCommitVisibility="same invocation"),
            lambda c: c["withdrawalLifecycle"].update(crossOwnerEffect="invalidate all users"),
        )
        for mutate in mutations:
            contract = copy.deepcopy(self.contract)
            mutate(contract)
            with self.subTest(mutate=mutate):
                self.assertTrue(self.validate(contract=contract))

    def test_catalog_is_bidirectional_but_unrelated_endpoint_does_not_affect_projection(self):
        catalog = copy.deepcopy(self.catalog)
        push = next(
            endpoint
            for endpoint in catalog["endpoints"]
            if endpoint["method"] == "PUT" and endpoint["path"].endswith("/{deviceId}")
        )
        push["schemas"]["body"] = "WrongRequest"
        self.assertTrue(self.validate(catalog=catalog))

        for field, value in (("owner", "wrong owner"), ("dbOwner", "wrong db owner")):
            catalog = copy.deepcopy(self.catalog)
            push = next(
                endpoint
                for endpoint in catalog["endpoints"]
                if endpoint["method"] == "PUT" and endpoint["path"].endswith("/{deviceId}")
            )
            push[field] = value
            with self.subTest(field=field):
                self.assertTrue(self.validate(catalog=catalog))

        unrelated = copy.deepcopy(self.catalog)
        next(e for e in unrelated["endpoints"] if e["path"] == "/api/v1/places")["owner"] = "mutated"
        self.assertEqual([], self.validate(catalog=unrelated))

        catalog = copy.deepcopy(self.catalog)
        catalog["commonRules"]["authorization"]["invalidTokenCode"] = "AUTH_TOKEN_INVALID"
        self.assertTrue(self.validate(catalog=catalog))

    def test_all_four_endpoints_and_issue_113_domain_row_are_required(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["endpoints"] = [
            e for e in catalog["endpoints"] if e["path"] != "/api/v1/me/notification-preferences"
        ]
        self.assertTrue(self.validate(catalog=catalog))
        catalog = copy.deepcopy(self.catalog)
        catalog["domainContracts"] = [d for d in catalog["domainContracts"] if d["issue"] != 113]
        self.assertTrue(self.validate(catalog=catalog))


if __name__ == "__main__":
    unittest.main()
