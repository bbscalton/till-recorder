import json
import time
from datetime import datetime

from fastapi.testclient import TestClient

from server import build, day_bounds


def client_and_token(tmp_path):
    app = build(tmp_path)
    token = json.loads((tmp_path / "config.json").read_text(encoding="utf-8"))["token"]
    return TestClient(app), token


def test_day_bounds_cover_about_one_day():
    start, end = day_bounds("2026-09-27")
    assert isinstance(start, int)
    assert 23 * 3600 * 1000 <= end - start <= 25 * 3600 * 1000
    assert datetime.fromtimestamp(start / 1000).hour == 0


def test_upload_requires_the_shop_token(tmp_path):
    client, _token = client_and_token(tmp_path)
    response = client.post(
        "/api/segments",
        data={
            "device_id": "register01",
            "device_name": "Front",
            "started_at_ms": "1759000000000",
            "ended_at_ms": "1759000300000",
        },
        files={"file": ("s.mp4", b"x" * 2048, "video/mp4")},
    )
    assert response.status_code == 401


def test_viewer_can_replay_a_saved_clip(tmp_path):
    client, token = client_and_token(tmp_path)
    started = int(time.time() * 1000) - 3_600_000
    ended = started + 300_000
    saved = client.post(
        "/api/segments",
        headers={"Authorization": f"Bearer {token}"},
        data={
            "device_id": "register01",
            "device_name": "Front register",
            "started_at_ms": str(started),
            "ended_at_ms": str(ended),
        },
        files={"file": ("s.mp4", b"ftyp" + b"\x00" * 2048, "video/mp4")},
    )
    assert saved.status_code == 200
    segment_id = saved.json()["id"]

    again = client.post(
        "/api/segments",
        headers={"Authorization": f"Bearer {token}"},
        data={
            "device_id": "register01",
            "device_name": "Front register",
            "started_at_ms": str(started),
            "ended_at_ms": str(ended),
        },
        files={"file": ("s.mp4", b"ftyp" + b"\x00" * 2048, "video/mp4")},
    )
    assert again.status_code == 200
    assert again.json()["duplicate"] is True

    assert client.get("/api/devices").status_code == 401
    assert client.post("/api/login", json={"token": "nope"}).status_code == 401
    assert client.post("/api/login", json={"token": token}).status_code == 200

    devices = client.get("/api/devices").json()["devices"]
    assert devices[0]["name"] == "Front register"

    day = datetime.fromtimestamp(started / 1000).strftime("%Y-%m-%d")
    listing = client.get("/api/segments", params={"device_id": "register01", "date": day}).json()
    assert listing["segments"][0]["id"] == segment_id
    assert listing["total_bytes"] > 1000

    media = client.get(f"/api/media/{segment_id}", headers={"Range": "bytes=0-3"})
    assert media.status_code == 206
    assert media.content == b"ftyp"

    blocked = client.get("/api/segments", params={"device_id": "../secret", "date": day})
    assert blocked.status_code == 400


def test_check_token_and_heartbeat(tmp_path):
    client, token = client_and_token(tmp_path)
    assert client.get("/api/check-token").status_code == 401
    assert client.get("/api/check-token", headers={"Authorization": f"Bearer {token}"}).status_code == 200
    beat = client.post(
        "/api/heartbeat",
        headers={"Authorization": f"Bearer {token}"},
        json={"device_id": "register01", "device_name": "Front register", "recording": True},
    )
    assert beat.status_code == 200
    assert client.post("/api/login", json={"token": token}).status_code == 200
    devices = client.get("/api/devices").json()["devices"]
    assert devices[0]["recording"] is True
