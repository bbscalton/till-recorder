"""Shop-computer store for Till Recorder clips."""

import json
import logging
import os
import re
import secrets
import socket
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path

from fastapi import Cookie, FastAPI, File, Form, Header, HTTPException, Response, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

STATIC = Path(__file__).resolve().parent / "static"
DEFAULT_DATA = Path(__file__).resolve().parent / "data"
HOST = "0.0.0.0"
PORT = int(os.environ.get("TILL_PORT", "8787"))
SESSION_TTL = 7 * 24 * 3600
MAX_BYTES = 250 * 1024 * 1024
DEVICE_ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
SEGMENT_ID = re.compile(r"^[a-f0-9]{32}$")
HEARTBEAT_FRESH_MS = 90_000

log = logging.getLogger("till")


class LoginBody(BaseModel):
    token: str


class HeartbeatBody(BaseModel):
    device_id: str
    device_name: str
    recording: bool = True


def data_dir() -> Path:
    return Path(os.environ.get("TILL_DATA", DEFAULT_DATA))


def token_ok(got: str, expected: str) -> bool:
    got_b, expected_b = got.encode(), expected.encode()
    if len(got_b) != len(expected_b):
        return False
    return secrets.compare_digest(got_b, expected_b)


def clean_name(name: str) -> str:
    cleaned = "".join(ch for ch in name if ch >= " " and ch != "\x7f").strip()
    if not cleaned:
        raise HTTPException(status_code=400, detail="Register name is required")
    return cleaned[:80]


def day_bounds(day_text: str) -> tuple[int, int]:
    try:
        day = datetime.strptime(day_text, "%Y-%m-%d")
    except ValueError as error:
        raise HTTPException(status_code=400, detail="Use a date like 2026-09-27") from error
    start = datetime.combine(day.date(), datetime.min.time()).astimezone()
    end = start + timedelta(days=1)
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


def lan_ips() -> list[str]:
    found: list[str] = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        found.append(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                found.append(ip)
    except OSError:
        pass
    unique: list[str] = []
    for ip in found:
        if ip not in unique:
            unique.append(ip)
    return unique


def load_config(path: Path) -> dict:
    if path.exists():
        config = json.loads(path.read_text(encoding="utf-8"))
    else:
        config = {"token": secrets.token_urlsafe(24)}
    if not isinstance(config.get("token"), str) or not config["token"]:
        config["token"] = secrets.token_urlsafe(24)
    try:
        config["keep_days"] = int(config.get("keep_days", 30))
    except (TypeError, ValueError):
        config["keep_days"] = 30
    path.write_text(json.dumps({"token": config["token"], "keep_days": config["keep_days"]}, indent=2), encoding="utf-8")
    return config


def connect(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            last_seen_ms INTEGER NOT NULL,
            recording INTEGER NOT NULL DEFAULT 0
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS segments (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            started_at_ms INTEGER NOT NULL,
            ended_at_ms INTEGER NOT NULL,
            path TEXT NOT NULL,
            bytes INTEGER NOT NULL,
            UNIQUE(device_id, started_at_ms)
        )
        """
    )
    conn.commit()
    return conn


def build(folder: Path | None = None) -> FastAPI:
    root = Path(folder) if folder is not None else data_dir()
    root.mkdir(parents=True, exist_ok=True)
    recordings = root / "recordings"
    recordings.mkdir(exist_ok=True)
    config = load_config(root / "config.json")
    conn = connect(root / "index.sqlite")
    db_lock = threading.Lock()
    sessions: dict[str, float] = {}

    def prune() -> None:
        keep_days = int(config.get("keep_days", 30))
        if keep_days <= 0:
            return
        cutoff = int((time.time() - keep_days * 86400) * 1000)
        with db_lock:
            rows = list(conn.execute("SELECT id, path FROM segments WHERE started_at_ms < ?", (cutoff,)))
            for row in rows:
                path = (recordings / row["path"]).resolve()
                if recordings.resolve() in path.parents:
                    path.unlink(missing_ok=True)
                conn.execute("DELETE FROM segments WHERE id = ?", (row["id"],))
            conn.commit()
        for dirpath, dirnames, filenames in os.walk(recordings, topdown=False):
            current = Path(dirpath)
            if current != recordings and not dirnames and not filenames:
                current.rmdir()

    prune()

    app = FastAPI(title="Till Recorder", docs_url=None, redoc_url=None, openapi_url=None)

    def bearer(authorization: str | None = Header(default=None)) -> str:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(status_code=401, detail="Missing token")
        return authorization[7:].strip()

    def require_device(authorization: str | None = Header(default=None)) -> None:
        if not token_ok(bearer(authorization), config["token"]):
            raise HTTPException(status_code=401, detail="Bad token")

    def require_session(till_session: str | None) -> str:
        now = time.time()
        issued = sessions.get(till_session or "")
        if not till_session or issued is None or now - issued > SESSION_TTL:
            sessions.pop(till_session or "", None)
            raise HTTPException(status_code=401, detail="Sign in")
        sessions[till_session] = now
        return till_session

    def stored_path(segment_id: str) -> Path:
        if not SEGMENT_ID.fullmatch(segment_id):
            raise HTTPException(status_code=404, detail="Clip not found")
        with db_lock:
            row = conn.execute("SELECT path FROM segments WHERE id = ?", (segment_id,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Clip not found")
        path = (recordings / row["path"]).resolve()
        if recordings.resolve() not in path.parents or not path.is_file():
            raise HTTPException(status_code=404, detail="Clip not found")
        return path

    @app.get("/api/check-token")
    def check_token(authorization: str | None = Header(default=None)) -> dict:
        require_device(authorization)
        return {"ok": True}

    @app.post("/api/login")
    def login(body: LoginBody, response: Response) -> dict:
        if not token_ok(body.token.strip(), config["token"]):
            raise HTTPException(status_code=401, detail="That token does not match this shop computer.")
        now = time.time()
        expired = [key for key, issued in sessions.items() if now - issued > SESSION_TTL]
        for key in expired:
            del sessions[key]
        sid = secrets.token_urlsafe(32)
        sessions[sid] = now
        response.set_cookie(
            "till_session",
            sid,
            max_age=SESSION_TTL,
            httponly=True,
            samesite="lax",
            path="/",
        )
        return {"ok": True}

    @app.post("/api/logout")
    def logout(response: Response, till_session: str | None = Cookie(default=None)) -> dict:
        if till_session:
            sessions.pop(till_session, None)
        response.delete_cookie("till_session", path="/")
        return {"ok": True}

    @app.get("/api/me")
    def current_viewer(till_session: str | None = Cookie(default=None)) -> dict:
        require_session(till_session)
        return {"ok": True, "keep_days": int(config.get("keep_days", 30))}

    @app.post("/api/heartbeat")
    def heartbeat(body: HeartbeatBody, authorization: str | None = Header(default=None)) -> dict:
        require_device(authorization)
        if not DEVICE_ID.fullmatch(body.device_id):
            raise HTTPException(status_code=400, detail="Device id is not valid")
        name = clean_name(body.device_name)
        now_ms = int(time.time() * 1000)
        with db_lock:
            conn.execute(
                """
                INSERT INTO devices (id, name, last_seen_ms, recording)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    last_seen_ms = excluded.last_seen_ms,
                    recording = excluded.recording
                """,
                (body.device_id, name, now_ms, 1 if body.recording else 0),
            )
            conn.commit()
        return {"ok": True}

    @app.post("/api/segments")
    async def upload_segment(
        device_id: str = Form(...),
        device_name: str = Form(...),
        started_at_ms: int = Form(...),
        ended_at_ms: int = Form(...),
        file: UploadFile = File(...),
        authorization: str | None = Header(default=None),
    ) -> dict:
        require_device(authorization)
        if not DEVICE_ID.fullmatch(device_id):
            raise HTTPException(status_code=400, detail="Device id is not valid")
        name = clean_name(device_name)
        now_ms = int(time.time() * 1000)
        if started_at_ms < 1_577_836_800_000 or started_at_ms > now_ms + 86_400_000:
            raise HTTPException(status_code=400, detail="Clip time is not valid")
        duration = ended_at_ms - started_at_ms
        if duration < 1000 or duration > 30 * 60 * 1000:
            raise HTTPException(status_code=400, detail="Clip length is not valid")

        with db_lock:
            existing = conn.execute(
                "SELECT id FROM segments WHERE device_id = ? AND started_at_ms = ?",
                (device_id, started_at_ms),
            ).fetchone()
        if existing:
            return {"ok": True, "duplicate": True, "id": existing["id"]}

        segment_id = uuid.uuid4().hex
        day_name = datetime.fromtimestamp(started_at_ms / 1000).astimezone().strftime("%Y-%m-%d")
        relative = Path(device_id) / day_name / f"{segment_id}.mp4"
        dest = recordings / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        written = 0
        try:
            with dest.open("wb") as out:
                while True:
                    chunk = await file.read(1024 * 1024)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > MAX_BYTES:
                        raise HTTPException(status_code=413, detail="Clip is too large")
                    out.write(chunk)
        except HTTPException:
            dest.unlink(missing_ok=True)
            raise
        finally:
            await file.close()
        if written < 1024:
            dest.unlink(missing_ok=True)
            raise HTTPException(status_code=400, detail="Clip is empty")

        try:
            with db_lock:
                conn.execute(
                    """
                    INSERT INTO devices (id, name, last_seen_ms, recording)
                    VALUES (?, ?, ?, 0)
                    ON CONFLICT(id) DO UPDATE SET
                        name = excluded.name,
                        last_seen_ms = max(devices.last_seen_ms, excluded.last_seen_ms)
                    """,
                    (device_id, name, now_ms),
                )
                conn.execute(
                    """
                    INSERT INTO segments (id, device_id, started_at_ms, ended_at_ms, path, bytes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (segment_id, device_id, started_at_ms, ended_at_ms, relative.as_posix(), written),
                )
                conn.commit()
        except sqlite3.IntegrityError:
            dest.unlink(missing_ok=True)
            return {"ok": True, "duplicate": True}
        prune()
        log.info("Saved %s %s (%s bytes)", name, segment_id, written)
        return {"ok": True, "id": segment_id}

    @app.get("/api/devices")
    def devices(session: str | None = Cookie(default=None, alias="till_session")) -> dict:
        require_session(session)
        now_ms = int(time.time() * 1000)
        with db_lock:
            rows = list(conn.execute("SELECT id, name, last_seen_ms, recording FROM devices ORDER BY name COLLATE NOCASE"))
        return {
            "devices": [
                {
                    "id": row["id"],
                    "name": row["name"],
                    "last_seen_ms": row["last_seen_ms"],
                    "recording": bool(row["recording"]) and now_ms - row["last_seen_ms"] < HEARTBEAT_FRESH_MS,
                }
                for row in rows
            ]
        }

    @app.get("/api/segments")
    def segments(
        device_id: str,
        date: str,
        session: str | None = Cookie(default=None, alias="till_session"),
    ) -> dict:
        require_session(session)
        if not DEVICE_ID.fullmatch(device_id):
            raise HTTPException(status_code=400, detail="Device id is not valid")
        start, end = day_bounds(date)
        with db_lock:
            rows = list(
                conn.execute(
                    """
                    SELECT id, started_at_ms, ended_at_ms, bytes
                    FROM segments
                    WHERE device_id = ? AND started_at_ms >= ? AND started_at_ms < ?
                    ORDER BY started_at_ms
                    """,
                    (device_id, start, end),
                )
            )
        return {
            "device_id": device_id,
            "date": date,
            "day_start_ms": start,
            "day_end_ms": end,
            "total_bytes": sum(row["bytes"] for row in rows),
            "segments": [
                {
                    "id": row["id"],
                    "started_at_ms": row["started_at_ms"],
                    "ended_at_ms": row["ended_at_ms"],
                    "bytes": row["bytes"],
                }
                for row in rows
            ],
        }

    @app.delete("/api/segments")
    def delete_day(
        device_id: str,
        date: str,
        session: str | None = Cookie(default=None, alias="till_session"),
    ) -> dict:
        require_session(session)
        if not DEVICE_ID.fullmatch(device_id):
            raise HTTPException(status_code=400, detail="Device id is not valid")
        start, end = day_bounds(date)
        with db_lock:
            rows = list(
                conn.execute(
                    "SELECT id, path FROM segments WHERE device_id = ? AND started_at_ms >= ? AND started_at_ms < ?",
                    (device_id, start, end),
                )
            )
            for row in rows:
                path = (recordings / row["path"]).resolve()
                if recordings.resolve() in path.parents:
                    path.unlink(missing_ok=True)
                conn.execute("DELETE FROM segments WHERE id = ?", (row["id"],))
            conn.commit()
        return {"ok": True, "deleted": len(rows)}

    @app.get("/api/media/{segment_id}")
    def media(
        segment_id: str,
        till_session: str | None = Cookie(default=None),
    ) -> FileResponse:
        require_session(till_session)
        path = stored_path(segment_id)
        return FileResponse(
            path,
            media_type="video/mp4",
            headers={"Accept-Ranges": "bytes", "Cache-Control": "private, max-age=86400"},
        )

    @app.get("/")
    def index() -> FileResponse:
        return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})

    @app.get("/favicon.ico")
    def favicon() -> Response:
        return Response(status_code=204)

    app.mount("/static", StaticFiles(directory=STATIC), name="static")
    return app


app = build()


def main() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    config = json.loads((data_dir() / "config.json").read_text(encoding="utf-8"))
    ips = lan_ips() or ["this-computer"]
    tablet_urls = "\n".join(f"  http://{ip}:{PORT}" for ip in ips)
    print(
        "\n".join(
            [
                "",
                "Till Recorder is ready.",
                "",
                f"On this computer, open: http://127.0.0.1:{PORT}",
                "On each register tablet, use:",
                tablet_urls,
                "",
                "Token (paste into each tablet):",
                f"  {config['token']}",
                "",
                f"Recordings stay on this computer for {config.get('keep_days', 30)} days.",
                f"Folder: {data_dir()}",
                "",
            ]
        )
    )
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")


if __name__ == "__main__":
    main()
