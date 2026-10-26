#!/usr/bin/env python3
"""Minecraft server-list status ping (protocol 767, MC 1.21.1).

Usage: mcping.py HOST PORT [HANDSHAKE_HOST]
Prints the status JSON on stdout; exits 1 if it lacks "players".
"""
import json
import socket
import struct
import sys


def varint(n: int) -> bytes:
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def read_varint(f) -> int:
    n = shift = 0
    while True:
        b = f.read(1)
        if not b:
            raise EOFError("connection closed")
        n |= (b[0] & 0x7F) << shift
        if not b[0] & 0x80:
            return n
        shift += 7
        if shift > 35:
            raise ValueError("varint too long")


def main() -> int:
    host, port = sys.argv[1], int(sys.argv[2])
    hs_host = sys.argv[3] if len(sys.argv) > 3 else host
    with socket.create_connection((host, port), timeout=10) as s:
        s.settimeout(10)
        h = hs_host.encode()
        body = b"\x00" + varint(767) + varint(len(h)) + h + struct.pack(">H", port) + varint(1)
        s.sendall(varint(len(body)) + body + b"\x01\x00")
        f = s.makefile("rb")
        read_varint(f)  # packet length
        if read_varint(f) != 0:
            raise ValueError("unexpected packet id")
        n = read_varint(f)
        data = f.read(n)
        if len(data) != n:
            raise EOFError("short JSON")
    text = data.decode("utf-8")
    js = json.loads(text)
    print(text)
    return 0 if "players" in js else 1


if __name__ == "__main__":
    sys.exit(main())
