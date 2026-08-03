from __future__ import annotations

import asyncio
from uuid import uuid4

import httpx
from conftest import TOKEN, FakeEngine, FakeNormalizer

from app.config import Settings
from app.main import create_app
from app.models.base import EngineHealth


class UnreadyEngine:
    async def health(self) -> EngineHealth:
        return EngineHealth(ready=False, name="missing-model")


class BlockingEngine:
    """Holds the first request so the test can prove the second one queues."""

    def __init__(self) -> None:
        self.first_started = asyncio.Event()
        self.release_first = asyncio.Event()
        self.calls = 0

    async def health(self) -> EngineHealth:
        return EngineHealth(ready=True, name="whisperkit:test-model")

    async def transcribe(self, audio_path, options) -> str:
        self.calls += 1
        if self.calls == 1:
            self.first_started.set()
            await self.release_first.wait()
        return "queued local transcript"


async def test_health_is_public_and_separates_engine_readiness(
    client: httpx.AsyncClient, fake_engine: FakeEngine
) -> None:
    response = await client.get("/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "engine_ready": True,
        "engine": "fake-local-model",
        "streaming_supported": False,
    }

    liveness = await client.get("/health/live")
    readiness = await client.get("/health/ready")
    repeated = await client.get("/health")

    assert liveness.status_code == 200
    assert liveness.json()["status"] == "ok"
    assert liveness.json()["uptime_seconds"] >= 0
    assert readiness.status_code == 200
    assert readiness.json()["status"] == "ready"
    assert readiness.json()["engine"] == "fake-local-model"
    assert repeated.status_code == 200
    assert fake_engine.health_calls == 1


async def test_private_endpoints_require_bearer_token(client: httpx.AsyncClient) -> None:
    response = await client.get("/v1/models")
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "unauthorized"
    assert TOKEN not in response.text


async def test_readiness_can_fail_without_failing_liveness(tmp_path) -> None:
    settings = Settings(
        token=TOKEN,
        data_dir=tmp_path,
        whisper_binary=tmp_path / "missing-whisper",
        whisper_model=tmp_path / "missing-model",
    )
    app = create_app(settings, engine=UnreadyEngine(), normalizer=FakeNormalizer())
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as test_client:
        liveness = await test_client.get("/health/live")
        readiness = await test_client.get("/health/ready")

    assert liveness.status_code == 200
    assert readiness.status_code == 503
    assert readiness.json()["status"] == "not_ready"
    assert readiness.json()["engine"] == "missing-model"


async def test_complete_flow_is_idempotent_and_deletes_successful_audio(
    client: httpx.AsyncClient,
    authorization: dict[str, str],
    audio_bytes: bytes,
) -> None:
    session_id = uuid4()
    payload = {
        "client_session_id": str(session_id),
        "language": "auto",
        "style": "raw",
    }
    created = await client.post("/v1/sessions", headers=authorization, json=payload)
    repeated = await client.post("/v1/sessions", headers=authorization, json=payload)
    assert created.status_code == 200
    assert repeated.json()["job_id"] == created.json()["job_id"]

    uploaded = await client.put(
        f"/v1/sessions/{session_id}/audio",
        headers={**authorization, "Content-Type": "audio/wav"},
        content=audio_bytes,
    )
    assert uploaded.status_code == 200
    assert uploaded.json()["state"] == "uploaded"

    finished = await client.post(f"/v1/sessions/{session_id}/finish", headers=authorization)
    finished_again = await client.post(f"/v1/sessions/{session_id}/finish", headers=authorization)
    assert finished.status_code == 200
    assert finished.json()["transcript"] == "hello from the local model"
    assert finished_again.json()["transcript"] == finished.json()["transcript"]
    assert finished_again.json()["job_id"] == finished.json()["job_id"]


async def test_second_transcription_waits_for_the_single_local_engine_slot(
    tmp_path,
    audio_bytes: bytes,
) -> None:
    settings = Settings(
        token=TOKEN,
        data_dir=tmp_path,
        whisper_binary=tmp_path / "whisper-cli",
        whisper_model=tmp_path / "model.bin",
        maximum_upload_bytes=20_000,
        transcription_queue_wait_seconds=1,
    )
    engine = BlockingEngine()
    app = create_app(settings, engine=engine, normalizer=FakeNormalizer())
    headers = {"Authorization": f"Bearer {TOKEN}"}

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as test_client:
        session_ids = [uuid4(), uuid4()]
        for session_id in session_ids:
            created = await test_client.post(
                "/v1/sessions",
                headers=headers,
                json={"client_session_id": str(session_id)},
            )
            assert created.status_code == 200
            uploaded = await test_client.put(
                f"/v1/sessions/{session_id}/audio",
                headers={**headers, "Content-Type": "audio/wav"},
                content=audio_bytes,
            )
            assert uploaded.status_code == 200

        first = asyncio.create_task(
            test_client.post(f"/v1/sessions/{session_ids[0]}/finish", headers=headers)
        )
        await asyncio.wait_for(engine.first_started.wait(), timeout=1)
        second = asyncio.create_task(
            test_client.post(f"/v1/sessions/{session_ids[1]}/finish", headers=headers)
        )
        await asyncio.sleep(0.1)
        assert not second.done()

        engine.release_first.set()
        first_response, second_response = await asyncio.gather(first, second)

    assert first_response.status_code == 200
    assert second_response.status_code == 200
    assert engine.calls == 2


async def test_session_accepts_writing_styles_and_rejects_unknown_values(
    client: httpx.AsyncClient,
    authorization: dict[str, str],
) -> None:
    for style in ("formal", "casual", "very_casual", "excited"):
        response = await client.post(
            "/v1/sessions",
            headers=authorization,
            json={"client_session_id": str(uuid4()), "style": style},
        )
        assert response.status_code == 200
        assert response.json()["style"] == style

    invalid = await client.post(
        "/v1/sessions",
        headers=authorization,
        json={"client_session_id": str(uuid4()), "style": "pirate"},
    )
    assert invalid.status_code == 422


async def test_writing_style_is_applied_to_the_local_transcript(
    client: httpx.AsyncClient,
    authorization: dict[str, str],
    audio_bytes: bytes,
) -> None:
    session_id = uuid4()
    await client.post(
        "/v1/sessions",
        headers=authorization,
        json={"client_session_id": str(session_id), "style": "formal"},
    )
    await client.put(
        f"/v1/sessions/{session_id}/audio",
        headers={**authorization, "Content-Type": "audio/wav"},
        content=audio_bytes,
    )

    finished = await client.post(f"/v1/sessions/{session_id}/finish", headers=authorization)

    assert finished.status_code == 200
    assert finished.json()["transcript"] == "Hello from the local model."


async def test_upload_rejects_unsupported_empty_and_oversized_audio(
    client: httpx.AsyncClient,
    authorization: dict[str, str],
) -> None:
    async def create() -> str:
        session_id = str(uuid4())
        await client.post(
            "/v1/sessions",
            headers=authorization,
            json={"client_session_id": session_id},
        )
        return session_id

    unsupported = await client.put(
        f"/v1/sessions/{await create()}/audio",
        headers={**authorization, "Content-Type": "text/plain"},
        content=b"x" * 200,
    )
    empty = await client.put(
        f"/v1/sessions/{await create()}/audio",
        headers={**authorization, "Content-Type": "audio/wav"},
        content=b"x",
    )
    oversized = await client.put(
        f"/v1/sessions/{await create()}/audio",
        headers={**authorization, "Content-Type": "audio/wav"},
        content=b"x" * 20_001,
    )
    assert unsupported.status_code == 415
    assert empty.status_code == 422
    assert oversized.status_code == 413


async def test_delete_is_idempotent(
    client: httpx.AsyncClient,
    authorization: dict[str, str],
) -> None:
    session_id = uuid4()
    await client.post(
        "/v1/sessions",
        headers=authorization,
        json={"client_session_id": str(session_id)},
    )
    first = await client.delete(f"/v1/sessions/{session_id}", headers=authorization)
    second = await client.delete(f"/v1/sessions/{session_id}", headers=authorization)
    assert first.status_code == 200
    assert first.json() == {"deleted": True}
    assert second.status_code == 404
    assert second.json() == {"deleted": False}
