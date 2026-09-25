#!/usr/bin/env python3
"""Minimal authoritative-ish upstream DNS server for the WAN namespace.

Stands in for the ISP's resolver. Answers every A query with the address of the
"internet" host so the gateway's dnsmasq has something real to forward to and
the client's DNS path can be exercised end to end without touching the public
internet (this sandbox has no route to one).
"""
import argparse
import socket
import struct
import sys


def parse_question(data):
    """Return (qname, qtype, offset_after_question)."""
    labels = []
    i = 12
    while i < len(data):
        length = data[i]
        if length == 0:
            i += 1
            break
        labels.append(data[i + 1:i + 1 + length])
        i += 1 + length
    qtype, = struct.unpack("!H", data[i:i + 2])
    return b".".join(labels).decode("ascii", "replace"), qtype, i + 4


def build_reply(query, answer_ip):
    tid = query[:2]
    flags = query[2:4]
    qname, qtype, end = parse_question(query)

    # QR=1, AA=1, copy RD, RA=1
    rd = 1 if (flags[0] & 0x01) else 0
    rflags = struct.pack("!BB", 0x84 | rd, 0x80 | rd)

    header = tid + rflags + struct.pack("!HHHH", 1, 0, 0, 0)
    question = query[12:end]

    if qtype == 1:  # A
        rdata = socket.inet_aton(answer_ip)
        answer = (b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, len(rdata)) + rdata)
        ancount = 1
    else:
        answer = b""
        ancount = 0

    header = tid + rflags + struct.pack("!HHHH", 1, ancount, 0, 0)
    return header + question + answer


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bind", default="203.0.113.1")
    ap.add_argument("--port", type=int, default=53)
    ap.add_argument("--answer", default="203.0.113.1")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((args.bind, args.port))
    sys.stderr.write(f"upstream-dns listening on {args.bind}:{args.port} -> answers A {args.answer}\n")
    sys.stderr.flush()

    while True:
        try:
            data, addr = sock.recvfrom(512)
            if len(data) < 12:
                continue
            sock.sendto(build_reply(data, args.answer), addr)
        except Exception as exc:  # keep the harness alive
            sys.stderr.write(f"upstream-dns error: {exc}\n")
            sys.stderr.flush()


if __name__ == "__main__":
    main()
