"""Local Qwen3-TTS playground. Run with ./run.sh."""
import asyncio
import io
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
import soundfile as sf
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parent
MODEL_ID = "mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-8bit"
MODEL_PATH = Path(os.environ.get("TTS_MODEL_PATH", str(ROOT / "models" / MODEL_ID.split("/")[-1])))
LANGUAGES = ["English", "Italian", "French", "German", "Spanish"]
VOICES = {
    "Ryan": "Male · English", "Aiden": "Male · English",
    "Vivian": "Female · Chinese", "Serena": "Female · Chinese",
    "Uncle_Fu": "Male · Chinese", "Dylan": "Male · Chinese (Beijing)",
    "Eric": "Male · Chinese (Sichuan)", "Ono_Anna": "Female · Japanese",
    "Sohee": "Female · Korean",
}
executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="qwen")
model = None
state = {"status": "loading", "error": None}
generation_lock = asyncio.Lock()
logger = logging.getLogger("tts-lab")


def load():
    global model
    try:
        from mlx_audio.tts.utils import load_model
        for name in ("config.json", "model.safetensors", "speech_tokenizer/model.safetensors"):
            if not (MODEL_PATH / name).exists():
                raise RuntimeError("Model download incomplete. Run ./setup.sh first.")
        model = load_model(str(MODEL_PATH))
        if model.tokenizer is None or model.speech_tokenizer is None:
            raise RuntimeError("Model tokenizer did not load. Check the server log.")
        state.update(status="ready", error=None)
        logger.info("Qwen3-TTS ready: %s", MODEL_PATH)
    except Exception as exc:
        logger.exception("Model load failed")
        state.update(status="error", error=str(exc))


@asynccontextmanager
async def lifespan(app):
    task = asyncio.get_running_loop().run_in_executor(executor, load)
    yield
    await task
    executor.shutdown(wait=True)


app = FastAPI(title="FolioTone Voice Lab", lifespan=lifespan)


class SpeechRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1000)
    language: str = "English"
    voice: str = "Ryan"
    instruction: str = Field(default="", max_length=300)


@app.get("/", include_in_schema=False)
async def index():
    return FileResponse(ROOT / "index.html", headers={"Cache-Control": "no-store"})


@app.get("/api/status")
async def status():
    return {**state, "busy": generation_lock.locked(), "model": MODEL_ID,
            "languages": LANGUAGES, "voices": VOICES, "max_characters": 1000}


def synthesize(request):
    import mlx.core as mx
    started = time.perf_counter()
    try:
        chunks = []
        sample_rate = 24000
        for result in model.generate_custom_voice(
            text=request.text.strip(), speaker=request.voice,
            language=request.language, instruct=request.instruction.strip() or None,
            max_tokens=2048,
        ):
            if result.token_count >= 2048:
                raise RuntimeError("Generation reached its length limit. Try a shorter passage.")
            chunks.append(np.asarray(result.audio, dtype=np.float32))
            sample_rate = result.sample_rate
        if not chunks:
            raise RuntimeError("The model produced no audio. Try a shorter passage.")
        audio = np.concatenate(chunks)
        if not np.isfinite(audio).all() or audio.size == 0:
            raise RuntimeError("The model produced invalid audio. Please try again.")
        output = io.BytesIO()
        sf.write(output, audio, sample_rate, format="WAV", subtype="PCM_16")
        return output.getvalue(), time.perf_counter() - started, len(audio) / sample_rate
    finally:
        mx.clear_cache()


@app.post("/api/speech", responses={200: {"content": {"audio/wav": {}}}})
async def speech(request: SpeechRequest):
    if not request.text.strip():
        raise HTTPException(422, "Enter some text to read.")
    if request.language not in LANGUAGES or request.voice not in VOICES:
        raise HTTPException(422, "Choose a supported language and voice.")
    if state["status"] != "ready":
        raise HTTPException(503, state["error"] or "The model is still loading.")
    if generation_lock.locked():
        raise HTTPException(409, "The model is busy. Wait for the current generation.")
    async with generation_lock:
        # Shield the worker so a disconnected client cannot release the lock
        # while Metal is still generating audio.
        future = asyncio.get_running_loop().run_in_executor(executor, synthesize, request)
        try:
            data, elapsed, duration = await asyncio.shield(future)
        except asyncio.CancelledError:
            await future
            raise
        except Exception:
            logger.exception("Speech generation failed")
            raise HTTPException(500, "Speech generation failed. Check the server log and try a shorter passage.")
    return Response(data, media_type="audio/wav", headers={
        "Content-Disposition": 'inline; filename="qwen-speech.wav"',
        "Cache-Control": "no-store", "X-Generation-Seconds": f"{elapsed:.2f}",
        "X-Audio-Seconds": f"{duration:.2f}",
    })
