#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path


def load_common(root: Path):
    path = root / ".codex" / "hooks" / "hook_common.py"
    spec = importlib.util.spec_from_file_location("hook_common", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser(description="PR 생성 준비 상태 검증")
    parser.add_argument("--base", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    common = load_common(root)
    branch, sha, _, _, errors = common.local_repo_state(root, args.base)
    if errors:
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print(f"PR 생성 조건 통과: {branch}@{sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
