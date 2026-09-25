#!/usr/bin/env python3
"""Minimal local stand-in for the CurseForge Upload API, used by curseforge-upload.test.sh.

Usage: mock_curseforge.py <port-file> <log-dir> <versions.json> <types.json> [upload-statuses]
  upload-statuses: comma list of HTTP codes returned by successive POST .../upload-file calls
                   (the last one repeats), default "200".
Writes the chosen port to <port-file>. Every upload is logged to <log-dir>/upload-<n>.json as
{"token", "metadata" (parsed JSON), "metadata_content_type", "filename", "file"}.
GET /api/game/version-types returns an empty body when <types.json> is "-" (like the Bedrock host).
"""
import email.parser
import email.policy
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

port_file, log_dir, versions_path, types_path = sys.argv[1:5]
statuses = [int(s) for s in (sys.argv[5] if len(sys.argv) > 5 else "200").split(",")]
state = {"uploads": 0, "file_id": 1000}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep test output clean
        pass

    def _send(self, code, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.headers.get("X-Api-Token") != "test-token":
            return self._send(403, '{"error":"bad token"}')
        if self.path == "/api/game/versions":
            return self._send(200, open(versions_path, "rb").read())
        if self.path == "/api/game/version-types":
            if types_path == "-":
                return self._send(200, "")
            return self._send(200, open(types_path, "rb").read())
        self._send(404, "{}")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        n = state["uploads"]
        state["uploads"] += 1
        code = statuses[min(n, len(statuses) - 1)]
        msg = email.parser.BytesParser(policy=email.policy.HTTP).parsebytes(
            b"Content-Type: " + self.headers["Content-Type"].encode() + b"\r\n\r\n" + raw)
        entry = {"token": self.headers.get("X-Api-Token"), "path": self.path, "status": code}
        for part in msg.iter_parts():
            name = part.get_param("name", header="content-disposition")
            payload = part.get_payload(decode=True)
            if name == "metadata":
                entry["metadata_content_type"] = part.get_content_type()
                try:
                    entry["metadata"] = json.loads(payload.decode("utf-8"))
                except ValueError as e:
                    entry["metadata_error"] = str(e)
                    entry["metadata_raw"] = payload.decode("utf-8", "replace")
            elif name == "file":
                entry["filename"] = part.get_filename()
                entry["file"] = payload.decode("utf-8", "replace")
        with open(os.path.join(log_dir, "upload-%d.json" % n), "w") as f:
            json.dump(entry, f)
        if code >= 300:
            return self._send(code, '{"errorCode":1018,"errorMessage":"mock failure %d"}' % code)
        if "metadata" not in entry:
            return self._send(400, '{"errorMessage":"invalid metadata"}')
        state["file_id"] += 1
        self._send(200, json.dumps({"id": state["file_id"]}))


server = HTTPServer(("127.0.0.1", 0), Handler)
with open(port_file + ".tmp", "w") as f:
    f.write(str(server.server_address[1]))
os.rename(port_file + ".tmp", port_file)
server.serve_forever()
