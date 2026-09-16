"""GCP 시작 준비의 실제 파일 저장과 비밀정보 실패 차단을 검증한다."""

import ast
import base64
import contextlib
import io
import json
import pathlib
import re
import tempfile
import types
import unittest
from unittest.mock import Mock


TEMPLATE = pathlib.Path(__file__).resolve().parents[2] / "infra/terraform/gcp-be/templates/startup.sh.tftpl"


class SecretBootstrapTest(unittest.TestCase):
    """외부 통신과 소유권 변경만 대체하고 시작 준비 본문을 실행한다."""

    def setUp(self):
        """실제 임시 디렉터리와 고정 버전 비밀 응답을 준비한다."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        source = re.search(r"timing-jeju-prepare <<'PY'\n(.*?)\nPY", TEMPLATE.read_text(), re.S).group(1)
        tree = ast.parse(source)
        self.handler = tree.body.pop()
        self.namespace = {}
        exec(compile(tree, str(TEMPLATE), "exec"), self.namespace)
        self.config = {
            "project": "test-project",
            "environment": {"secret_id": "be-env", "version": "7"},
            "files": {"signing.pem": {"secret_id": "signing", "version": "3"}},
            "registry": "asia-northeast3-docker.pkg.dev",
            "image": "example/be@sha256:" + "a" * 64,
        }
        self.entries = {
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://db.example/test?sslmode=verify-full",
            "SPRING_DATASOURCE_USERNAME": "test-user",
            "SPRING_DATASOURCE_PASSWORD": "private-password",
            "SUPABASE_JWT_ISSUER": "https://auth.example",
            "SUPABASE_JWKS_URL": "https://auth.example/jwks",
            "APP_CORS_ALLOWED_ORIGINS": "https://app.example",
            "APP_PLACES_CURSOR_SIGNING_KEY": "private-places-key",
            "APP_TRIPS_CURSOR_SIGNING_KEY": "private-trips-key",
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "8",
        }
        config_path = self.root / "etc/timing-jeju/config.json"
        config_path.parent.mkdir(parents=True)
        config_path.write_text(json.dumps(self.config))
        (self.root / "run").mkdir()
        self.chown = Mock()
        self.docker = Mock()
        self.requests = Mock(side_effect=self.response)
        self.namespace.update(
            pathlib=types.SimpleNamespace(Path=lambda value: self.root / str(value).lstrip("/")),
            os=types.SimpleNamespace(chown=self.chown, environ={}),
            subprocess=types.SimpleNamespace(run=self.docker, DEVNULL=-3),
            request_json=self.requests,
        )

    def response(self, url, headers):
        """요청한 고정 버전에 해당하는 모의 비밀을 반환한다."""
        if url.startswith("http://metadata."):
            self.assertEqual(headers, {"Metadata-Flavor": "Google"})
            return {"access_token": "private-token"}
        self.assertEqual(headers, {"Authorization": "Bearer private-token"})
        if url.endswith("/secrets/be-env/versions/7:access"):
            payload = "\n".join(f"{key}={value}" for key, value in self.entries.items()).encode()
        elif url.endswith("/secrets/signing/versions/3:access"):
            payload = b"private-signing-material"
        else:
            self.fail("unpinned or unexpected secret request")
        return {"payload": {"data": base64.b64encode(payload).decode()}}

    def assert_failure_is_private(self):
        """최상위 오류 처리기가 비밀을 숨기고 컨테이너 준비를 중단함을 확인한다."""
        output = io.StringIO()
        with contextlib.redirect_stderr(output), self.assertRaises(SystemExit) as raised:
            exec(compile(ast.Module(body=[self.handler], type_ignores=[]), str(TEMPLATE), "exec"), self.namespace)
        self.assertEqual(raised.exception.code, 1)
        self.assertEqual(output.getvalue(), "BE startup preparation failed; container was not started.\n")
        self.docker.assert_not_called()
        self.assertEqual(list((self.root / "run/timing-jeju/config").iterdir()), [])
        self.assertEqual(list((self.root / "run/timing-jeju/secrets").iterdir()), [])

    def test_success_writes_private_configtree_and_pinned_secrets(self):
        """정상 응답을 실제 파일에 저장하고 권한과 고정 버전 조회를 보장한다."""
        self.namespace["main"]()
        runtime = self.root / "run/timing-jeju"
        for key, value in self.entries.items():
            path = runtime / "config" / key
            self.assertEqual(path.read_text(), value)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.chown.assert_any_call(path, 10001, 10001)
        self.assertEqual((runtime / "config/spring.datasource.hikari.maximum-pool-size").read_text(), "8")
        secret = runtime / "secrets/signing.pem"
        self.assertEqual(secret.read_bytes(), b"private-signing-material")
        self.assertEqual(secret.stat().st_mode & 0o777, 0o600)
        for directory in (runtime, runtime / "config", runtime / "secrets", runtime / "docker"):
            self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
        self.assertEqual(self.requests.call_count, 3)
        self.assertEqual(self.docker.call_count, 2)
        self.assertEqual(self.docker.call_args_list[0].kwargs["input"], b"private-token")
        self.assertNotIn("private-password", repr(self.docker.call_args_list))
        self.assertFalse((runtime / "docker/config.json").exists())

    def test_secret_lookup_failure_blocks_docker_and_hides_provider_body(self):
        """메타데이터 성공 후 실제 비밀 조회 실패가 저장과 도커 실행을 차단한다."""
        def fail_second_secret(url, headers):
            """두 번째 비밀 조회에서 공급자 오류를 재현한다."""
            if "/secrets/signing/" in url:
                raise RuntimeError("private-token private-password provider-body")
            return self.response(url, headers)
        self.requests.side_effect = fail_second_secret
        self.assert_failure_is_private()
        self.assertEqual(self.requests.call_count, 3)

    def test_invalid_base64_blocks_docker_and_file_publication(self):
        """잘못된 비밀 인코딩을 거부하고 부분 파일을 게시하지 않는다."""
        def invalid_secret(url, headers):
            """서명 비밀의 손상된 인코딩을 재현한다."""
            if "/secrets/signing/" in url:
                return {"payload": {"data": "!!!private-secret!!!"}}
            return self.response(url, headers)
        self.requests.side_effect = invalid_secret
        self.assert_failure_is_private()
        self.assertEqual(self.requests.call_count, 3)


if __name__ == "__main__":
    unittest.main()
