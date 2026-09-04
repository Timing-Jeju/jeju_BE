#!/usr/bin/env python3
from __future__ import annotations

import re
import socket
import sys


LOOPBACK_HOST = "127.0.0.1"
MIN_PORT = 1024
MAX_PORT = 65535


def validate_port(raw_port: str) -> int:
    if not re.fullmatch(r"[0-9]+", raw_port):
        raise ValueError("smoke API port must contain decimal digits only")

    port = int(raw_port)
    if not MIN_PORT <= port <= MAX_PORT:
        raise ValueError(f"smoke API port must be between {MIN_PORT} and {MAX_PORT}")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        try:
            probe.bind((LOOPBACK_HOST, port))
        except OSError as error:
            raise RuntimeError(
                f"smoke API port is already in use on {LOOPBACK_HOST}: {port}"
            ) from error
    return port


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: validate_smoke_api_port.py PORT", file=sys.stderr)
        return 2
    try:
        port = validate_port(argv[1])
    except (ValueError, RuntimeError) as error:
        print(f"[Docker] {error}", file=sys.stderr)
        return 1
    print(port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
