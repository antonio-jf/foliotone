"""FolioTone narration server on Modal: Voxtral 4B TTS behind vLLM-Omni.

Deploy the API the Android app talks to:

    modal deploy tts-server/app.py

Run the listening and speed test again (writes tts-server/samples/voxtral/):

    modal run tts-server/app.py

How a book is narrated:
  1. The app POSTs the book's pages to /v1/jobs (one entry per page: a key and the text).
  2. A GPU container renders each page to MP3 and writes it to the `foliotone-jobs` Volume.
  3. The app polls /v1/jobs/{id}, downloads finished pages, and deletes the job when done.
The web API runs on CPU; the GPU starts only for jobs and stops two minutes after the last one.
"""

import os
import re
import time
from pathlib import Path

import modal

APP_NAME = "foliotone-tts"
MODEL = "mistralai/Voxtral-4B-TTS-2603"
MODEL_TAG = "voxtral-4b-2603"   # part of every page key; change it if the model changes
GPU = os.environ.get("FOLIOTONE_GPU", "L4")
PORT = 8000
CHUNK_CHARS = 400               # Voxtral's published benchmark used ~500-character inputs.
MP3_BITRATE = "48k"
# Joining pieces of a page. Each piece is generated separately, so its loudness and edge silence vary;
# pieces are trimmed, brought to one loudness and joined with the same pause (see _polish).
TARGET_RMS = 0.08               # about -22 dBFS of speech
PAUSE_SECONDS = 0.32            # between pieces (pieces always end at a sentence end)
PAGE_LEAD_SECONDS = 0.08
PAGE_TAIL_SECONDS = 0.30
JOB_CONCURRENCY = 24            # simultaneous requests to the model while rendering a book
MAX_PAGES = 15_000
MAX_PAGE_CHARS = 3_000
JOB_RETENTION_DAYS = 3

# Preset voices shipped with the model (the test run listed these; "default" is not a usable voice).
VOICES = [
    "casual_female", "casual_male", "cheerful_female", "neutral_female", "neutral_male",
    "de_female", "de_male", "es_female", "es_male", "fr_female", "fr_male",
    "it_female", "it_male", "nl_female", "nl_male", "pt_female", "pt_male",
    "ar_male", "hi_female", "hi_male",
]
DEFAULT_VOICES = {"en": "neutral_female", "it": "it_female", "fr": "fr_female",
                  "de": "de_female", "es": "es_female"}

# Modal list prices per second (checked 2026-11-09), used only for estimates.
GPU_PRICE_PER_SECOND = {
    "T4": 0.000164, "L4": 0.000222, "A10": 0.000306, "L40S": 0.000542,
    "A100-40GB": 0.000583, "A100-80GB": 0.000694, "H100": 0.001097,
}

app = modal.App(APP_NAME)

hf_cache = modal.Volume.from_name("foliotone-hf-cache", create_if_missing=True)
vllm_cache = modal.Volume.from_name("foliotone-vllm-cache", create_if_missing=True)
jobs_volume = modal.Volume.from_name("foliotone-jobs", create_if_missing=True)
JOBS = Path("/jobs")

gpu_image = (
    modal.Image.from_registry("nvidia/cuda:12.8.0-devel-ubuntu22.04", add_python="3.12")
    .entrypoint([])
    .apt_install("ffmpeg")
    .uv_pip_install(
        # vLLM-Omni does not declare its vLLM requirement; keep the two on the same release.
        "vllm==0.32.0",
        "vllm-omni==0.32.0",
        "mistral-common[audio]>=1.10.0",
        "httpx",
        "soundfile",
        "numpy",
    )
    # Optional extra `vllm serve` flags, e.g. FOLIOTONE_VLLM_ARGS="--gpu-memory-utilization 0.85"
    .env({"FOLIOTONE_VLLM_ARGS": os.environ.get("FOLIOTONE_VLLM_ARGS", "")})
)

web_image = modal.Image.debian_slim(python_version="3.12").uv_pip_install("fastapi[standard]")
SAMPLES_DIR = Path(__file__).resolve().parent / "samples" / "voxtral" / "voices"
if modal.is_local() and SAMPLES_DIR.is_dir():
    # Voice previews in the app play these instead of starting a GPU.
    web_image = web_image.add_local_dir(SAMPLES_DIR, "/samples")


_ROMAN = re.compile(r"(?=[MDCLXVI])M*(C[MD]|D?C{0,3})(X[CL]|L?X{0,3})(I[XV]|V?I{0,3})")
_WORD = re.compile(r"[^\W\d_]+")


def _shouted(word: str) -> bool:
    return len(word) >= 2 and word.isupper()


def prepare_text(text: str) -> str:
    """Clean book text before narration (the page key still uses the text the app sent).

    - Words written in capitals become normal case ("THE LAST TRAIN" -> "The Last Train"); voices tend
      to shout or spell them. Short lone capitals (BBC, USA) and Roman numerals (XII) are kept.
    - Gutenberg markup: _italics_ and *emphasis* marks are dropped, "--" becomes a dash,
      [1]-style footnote marks and [Illustration] placeholders are removed.
    """
    original = text
    text = re.sub(r"\[(?:\d+|[a-zA-Z]|Illustration[^\]]*)\]", " ", text)
    text = re.sub(r"(?<!\w)_+|_+(?!\w)", "", text)
    text = re.sub(r"\*+", " ", text)
    text = re.sub(r"\s*--+\s*", " — ", text)

    words = list(_WORD.finditer(text))
    out, last = [], 0
    for i, match in enumerate(words):
        word = match.group(0)
        if not _shouted(word) or _ROMAN.fullmatch(word):
            continue
        neighbours = [words[j].group(0) for j in (i - 1, i + 1) if 0 <= j < len(words)]
        if len(word) >= 4 or any(_shouted(n) and not _ROMAN.fullmatch(n) for n in neighbours):
            out.append(text[last:match.start()])
            out.append(word.capitalize())
            last = match.end()
    out.append(text[last:])
    text = " ".join("".join(out).split())
    text = re.sub(r"\s+([,.;:!?])", r"\1", text)
    return text or " ".join(original.split())


def _polish(data, rate: int):
    """Trim edge silence, bring speech to TARGET_RMS, soften the edges. `data` is mono float32."""
    import numpy as np

    frame = max(1, int(rate * 0.02))
    count = len(data) // frame
    if count < 3:
        return data
    rms = np.sqrt(np.mean(data[:count * frame].reshape(count, frame) ** 2, axis=1))
    voiced = np.flatnonzero(rms > max(rms.max() * 0.05, 1e-4))
    if voiced.size == 0:
        return data
    start = max(0, (voiced[0] - 1) * frame)
    end = min(len(data), (voiced[-1] + 2) * frame)
    data = data[start:end].copy()
    level = float(np.sqrt(np.mean(rms[voiced] ** 2)))
    data *= float(np.clip(TARGET_RMS / level, 0.25, 4.0)) if level > 0 else 1.0
    peak = float(np.abs(data).max())
    if peak > 0.95:
        data *= 0.95 / peak
    fade = min(len(data) // 4, int(rate * 0.01))
    if fade:
        ramp = np.linspace(0.0, 1.0, fade, dtype="float32")
        data[:fade] *= ramp
        data[-fade:] *= ramp[::-1]
    return data


def split_text(text: str, limit: int = CHUNK_CHARS) -> list[str]:
    """Split on sentence ends, then commas/spaces, keeping chunks under `limit`."""
    text = " ".join(text.split())
    if len(text) <= limit:
        return [text] if text else []
    sentences = re.split(r"(?<=[.!?…;:»”\"])\s+", text)
    pieces: list[str] = []
    for sentence in sentences:
        while len(sentence) > limit:
            cut = max(sentence.rfind(", ", 0, limit), sentence.rfind(" ", 0, limit))
            cut = cut if cut > limit // 3 else limit
            pieces.append(sentence[:cut + 1].strip())
            sentence = sentence[cut + 1:].strip()
        if sentence:
            pieces.append(sentence)
    chunks: list[str] = []
    current = ""
    for piece in pieces:
        if current and len(current) + 1 + len(piece) > limit:
            chunks.append(current)
            current = piece
        else:
            current = f"{current} {piece}".strip()
    if current:
        chunks.append(current)
    return chunks


def _job_dir(job_id: str) -> Path:
    if not re.fullmatch(r"[a-f0-9]{32}", job_id):
        raise ValueError("invalid job id")
    return JOBS / job_id


def _write_json(path: Path, data: dict) -> None:
    import json

    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(data), encoding="utf-8")
    os.replace(temp, path)


@app.cls(
    image=gpu_image,
    gpu=GPU,
    volumes={"/root/.cache/huggingface": hf_cache, "/root/.cache/vllm": vllm_cache, "/jobs": jobs_volume},
    timeout=3 * 60 * 60,
    startup_timeout=30 * 60,
    scaledown_window=120,
    max_containers=2,
    retries=modal.Retries(max_retries=2, initial_delay=10.0),
)
class Voxtral:
    @modal.enter()
    def start(self) -> None:
        import shlex
        import subprocess

        import httpx

        started = time.time()
        extra = shlex.split(os.environ.get("FOLIOTONE_VLLM_ARGS", ""))
        command = ["vllm", "serve", MODEL, "--omni", "--host", "127.0.0.1", "--port", str(PORT), *extra]
        print("Starting:", " ".join(command), flush=True)
        self.proc = subprocess.Popen(command)
        base = f"http://127.0.0.1:{PORT}"
        while True:
            if self.proc.poll() is not None:
                raise RuntimeError(f"vLLM-Omni exited with code {self.proc.returncode}; see logs above")
            if time.time() - started > 30 * 60:
                raise RuntimeError("vLLM-Omni did not become ready within 30 minutes")
            try:
                if httpx.get(f"{base}/health", timeout=5).status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            time.sleep(3)
        self.startup_seconds = time.time() - started
        print(f"vLLM-Omni ready after {self.startup_seconds:.0f}s", flush=True)
        hf_cache.commit()
        vllm_cache.commit()

    @modal.exit()
    def stop(self) -> None:
        if getattr(self, "proc", None) and self.proc.poll() is None:
            self.proc.terminate()

    # -- model access ---------------------------------------------------------

    def _voices(self) -> list[str]:
        import glob

        import httpx

        try:
            reply = httpx.get(f"http://127.0.0.1:{PORT}/v1/audio/voices", timeout=30)
            if reply.status_code == 200:
                voices = [str(v) for v in reply.json().get("voices") or [] if str(v) != "default"]
                if voices:
                    return sorted(voices)
        except (httpx.HTTPError, ValueError):
            pass
        pattern = "/root/.cache/huggingface/hub/models--mistralai--Voxtral-4B-TTS-2603/snapshots/*/voice_embedding/*.pt"
        return sorted({Path(p).stem for p in glob.glob(pattern)} - {"default"})

    def _speak(self, text: str, voice: str) -> bytes:
        import httpx

        payload = {"input": text, "model": MODEL, "voice": voice, "response_format": "wav"}
        last_error = "no attempt"
        for attempt in range(3):
            try:
                reply = httpx.post(f"http://127.0.0.1:{PORT}/v1/audio/speech", json=payload, timeout=600)
            except httpx.HTTPError as error:
                last_error = str(error)
            else:
                if reply.status_code == 200:
                    return reply.content
                last_error = f"HTTP {reply.status_code}: {reply.text[:300]}"
                if reply.status_code < 500:
                    break
            time.sleep(2 * (attempt + 1))
        raise RuntimeError(last_error)

    def _render(self, text: str, voice: str, polish: bool = True) -> tuple[bytes, float]:
        """Render one page: split into chunks, join with pauses, encode MP3.

        polish=False is the original behaviour (raw text, raw pieces, 0.12 s gaps), kept for comparisons.
        """
        import io
        import subprocess

        import numpy as np
        import soundfile as sf

        parts = []
        for chunk in split_text(prepare_text(text) if polish else text):
            data, rate = sf.read(io.BytesIO(self._speak(chunk, voice)), dtype="float32")
            data = data.mean(axis=1) if data.ndim > 1 else data
            parts.append((_polish(data, rate) if polish else data, rate))
        if not parts:
            raise RuntimeError("empty page")
        rate = parts[0][1]
        silence = lambda seconds: np.zeros(int(rate * seconds), dtype="float32")
        gap = silence(PAUSE_SECONDS if polish else 0.12)
        joined = np.concatenate([x for data, _ in parts for x in (data, gap)][:-1])
        if polish:
            joined = np.concatenate([silence(PAGE_LEAD_SECONDS), joined, silence(PAGE_TAIL_SECONDS)])
        wav = io.BytesIO()
        sf.write(wav, joined, rate, format="WAV")
        mp3 = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "wav", "-i", "pipe:0",
             "-ac", "1", "-b:a", MP3_BITRATE, "-f", "mp3", "pipe:1"],
            input=wav.getvalue(), capture_output=True, check=True).stdout
        return mp3, len(joined) / rate

    # -- book jobs ------------------------------------------------------------

    @modal.method()
    def render_job(self, job_id: str) -> dict:
        """Render every page of a job that is not on the Volume yet. Safe to re-run (resumes)."""
        import json
        import threading
        from concurrent.futures import ThreadPoolExecutor

        jobs_volume.reload()
        folder = _job_dir(job_id)
        manifest_path = folder / "manifest.json"
        if not manifest_path.is_file():
            return {"state": "missing"}
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        voice = manifest["voice"]
        pages = manifest["pages"]
        audio = folder / "audio"
        audio.mkdir(exist_ok=True)
        done_log = folder / "done.txt"
        already = set(done_log.read_text(encoding="utf-8").split()) if done_log.is_file() else set()
        # Resume: pages rendered by an earlier attempt are already on the Volume.
        for page in pages:
            if page["key"] not in already and (audio / f"{page['key']}.mp3").is_file():
                with done_log.open("a", encoding="utf-8") as log:
                    log.write(page["key"] + "\n")
                already.add(page["key"])
        todo = [p for p in pages if p["key"] not in already]
        lock = threading.Lock()
        failed: dict[str, str] = {}
        started = time.time()
        counters = {"done": len(already), "seconds": 0.0, "chars": 0}

        def status(state: str, error: str | None = None) -> None:
            _write_json(folder / "status.json", {
                "state": state, "total": len(pages), "done": counters["done"], "failed": len(failed),
                "error": error, "started": started, "updated": time.time(),
                "audio_seconds": round(counters["seconds"], 1),
                "chars_per_second": round(counters["chars"] / max(time.time() - started, 1), 1),
            })
            _write_json(folder / "failed.json", failed)

        def cancelled() -> bool:
            return (folder / "cancel").exists() or not manifest_path.exists()

        def work(page: dict) -> None:
            if cancelled():
                return
            try:
                mp3, seconds = self._render(page["text"], voice)
            except Exception as error:  # one bad page must not stop the book
                with lock:
                    failed[page["key"]] = str(error)[:300]
                return
            target = audio / f"{page['key']}.mp3"
            temp = target.with_suffix(".tmp")
            temp.write_bytes(mp3)
            os.replace(temp, target)
            with lock:
                with done_log.open("a", encoding="utf-8") as log:
                    log.write(page["key"] + "\n")
                counters["done"] += 1
                counters["seconds"] += seconds
                counters["chars"] += len(page["text"])

        status("running")
        jobs_volume.commit()
        with ThreadPoolExecutor(max_workers=JOB_CONCURRENCY) as pool:
            futures = [pool.submit(work, page) for page in todo]
            last_commit = time.time()
            while any(not f.done() for f in futures):
                time.sleep(1)
                if time.time() - last_commit >= 8:
                    with lock:
                        status("running")
                    jobs_volume.commit()
                    last_commit = time.time()
                if cancelled():
                    for future in futures:
                        future.cancel()
        if cancelled():
            return {"state": "cancelled"}
        final = "done" if not failed else "failed" if counters["done"] == 0 else "done"
        status(final, f"{len(failed)} pages could not be generated" if failed else None)
        jobs_volume.commit()
        print(f"Job {job_id}: {counters['done']}/{len(pages)} pages, {len(failed)} failed, "
              f"{time.time() - started:.0f}s", flush=True)
        return {"state": final, "done": counters["done"], "failed": len(failed)}

    # -- listening test -------------------------------------------------------

    @modal.method()
    def info(self) -> dict:
        import subprocess
        from importlib import metadata

        def version(*names: str) -> str:
            for name in names:
                try:
                    return metadata.version(name)
                except metadata.PackageNotFoundError:
                    continue
            return "unknown"

        gpu = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"],
                             capture_output=True, text=True).stdout.strip()
        return {
            "model": MODEL,
            "gpu": gpu or GPU,
            "startup_seconds": round(self.startup_seconds, 1),
            "voices": self._voices(),
            "versions": {"vllm": version("vllm"), "vllm-omni": version("vllm-omni", "vllm_omni"),
                         "mistral-common": version("mistral-common", "mistral_common")},
        }

    @modal.method()
    def synthesize(self, items: list[dict], concurrency: int = 16) -> dict:
        """items: [{"id": str, "text": str, "voice": str}] -> MP3 per item plus timings."""
        from concurrent.futures import ThreadPoolExecutor

        def run(item: dict) -> dict:
            entry = {"id": item["id"], "voice": item["voice"], "chars": len(item["text"]),
                     "chunks": len(split_text(item["text"]))}
            try:
                mp3, seconds = self._render(item["text"], item["voice"], item.get("polish", True))
                entry.update(mp3=mp3, audio_seconds=round(seconds, 2),
                             suspicious=seconds < len(item["text"]) / 40)
            except Exception as error:
                entry["error"] = str(error)
            return entry

        began = time.time()
        with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
            results = list(pool.map(run, items))
        return {"wall_seconds": round(time.time() - began, 2), "requests": sum(r["chunks"] for r in results),
                "concurrency": concurrency, "results": results}


# ---------------------------------------------------------------------------
# Web API (CPU only). Protected by Modal proxy auth: send Modal-Key and Modal-Secret headers.
# ---------------------------------------------------------------------------

@app.function(image=web_image, volumes={"/jobs": jobs_volume}, scaledown_window=300, timeout=600)
@modal.concurrent(max_inputs=50)
@modal.asgi_app(requires_proxy_auth=True)
def api():
    import json
    import shutil
    import uuid

    from fastapi import FastAPI, HTTPException, Request
    from fastapi.responses import Response

    web = FastAPI(title="FolioTone narration", docs_url=None, redoc_url=None)
    last_reload = {"at": 0.0}

    def refresh(force: bool = False) -> None:
        if force or time.time() - last_reload["at"] > 2:
            try:
                jobs_volume.reload()
            except Exception as error:  # e.g. a file is open by a concurrent request; try next time
                print("Volume reload skipped:", error, flush=True)
            last_reload["at"] = time.time()

    def folder_for(job_id: str) -> Path:
        try:
            return _job_dir(job_id)
        except ValueError:
            raise HTTPException(404, "Unknown job")

    @web.get("/v1/health")
    def health():
        samples = Path("/samples")
        return {"ok": True, "model": MODEL, "model_tag": MODEL_TAG, "voices": VOICES,
                "defaults": DEFAULT_VOICES, "samples": samples.is_dir(),
                "max_pages": MAX_PAGES, "max_page_chars": MAX_PAGE_CHARS}

    @web.get("/v1/voices/{voice}/sample")
    def sample(voice: str, language: str = "en"):
        if voice not in VOICES or language not in DEFAULT_VOICES:
            raise HTTPException(404, "Unknown voice or language")
        path = Path("/samples") / language / f"{voice}.mp3"
        if not path.is_file():
            raise HTTPException(404, "No sample for this voice")
        return Response(path.read_bytes(), media_type="audio/mpeg",
                        headers={"Cache-Control": "public, max-age=86400"})

    @web.post("/v1/jobs")
    async def create_job(request: Request):
        try:
            body = json.loads(await request.body())
        except ValueError:
            raise HTTPException(400, "Body must be JSON")
        voice = body.get("voice")
        pages = body.get("pages")
        if voice not in VOICES:
            raise HTTPException(422, "Unknown voice")
        if not isinstance(pages, list) or not 0 < len(pages) <= MAX_PAGES:
            raise HTTPException(422, f"Send between 1 and {MAX_PAGES} pages")
        seen: set[str] = set()
        clean = []
        for page in pages:
            key, text = (page or {}).get("key"), (page or {}).get("text")
            if not isinstance(key, str) or not re.fullmatch(r"[a-f0-9]{64}", key):
                raise HTTPException(422, "Each page needs a 64-character hex key")
            if not isinstance(text, str) or not text.strip() or len(text) > MAX_PAGE_CHARS:
                raise HTTPException(422, f"Each page needs 1 to {MAX_PAGE_CHARS} characters of text")
            if key not in seen:
                seen.add(key)
                clean.append({"key": key, "text": text})
        job_id = uuid.uuid4().hex
        folder = JOBS / job_id
        folder.mkdir(parents=True)
        _write_json(folder / "manifest.json", {
            "id": job_id, "created": time.time(), "voice": voice, "model": MODEL_TAG,
            "book": str(body.get("book", ""))[:64], "pages": clean})
        jobs_volume.commit()
        call = Voxtral().render_job.spawn(job_id)
        (folder / "call_id").write_text(call.object_id, encoding="utf-8")
        jobs_volume.commit()
        return {"id": job_id, "total": len(clean), "characters": sum(len(p["text"]) for p in clean)}

    @web.get("/v1/jobs/{job_id}")
    def job_status(job_id: str, after: int = 0):
        folder = folder_for(job_id)
        refresh()
        if not (folder / "manifest.json").is_file():
            refresh(force=True)
            if not (folder / "manifest.json").is_file():
                raise HTTPException(404, "Unknown job")
        status_path = folder / "status.json"
        status = json.loads(status_path.read_text(encoding="utf-8")) if status_path.is_file() else {}
        done_log = folder / "done.txt"
        keys = done_log.read_text(encoding="utf-8").split() if done_log.is_file() else []
        after = max(0, after)
        batch = keys[after:after + 2000]
        if not status:
            manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
            status = {"state": "queued", "total": len(manifest["pages"])}
        failed_path = folder / "failed.json"
        failed = json.loads(failed_path.read_text(encoding="utf-8")) if failed_path.is_file() else {}
        return {"id": job_id, "state": status.get("state", "queued"), "total": status.get("total", 0),
                "done": len(keys), "failed": len(failed), "failed_keys": list(failed)[:2000],
                "error": status.get("error"), "chars_per_second": status.get("chars_per_second"),
                "done_keys": batch, "next": after + len(batch)}

    @web.get("/v1/jobs/{job_id}/audio/{key}")
    def job_audio(job_id: str, key: str):
        folder = folder_for(job_id)
        if not re.fullmatch(r"[a-f0-9]{64}", key):
            raise HTTPException(404, "Unknown page")
        path = folder / "audio" / f"{key}.mp3"
        if not path.is_file():
            refresh(force=True)
        if not path.is_file():
            raise HTTPException(404, "Page not ready")
        return Response(path.read_bytes(), media_type="audio/mpeg")

    @web.delete("/v1/jobs/{job_id}")
    def delete_job(job_id: str):
        folder = folder_for(job_id)
        refresh(force=True)
        if not folder.exists():
            return {"deleted": False}
        call_file = folder / "call_id"
        (folder / "cancel").write_text("1", encoding="utf-8")
        jobs_volume.commit()
        if call_file.is_file():
            try:
                modal.FunctionCall.from_id(call_file.read_text(encoding="utf-8").strip()).cancel()
            except Exception as error:
                print("Cancel failed:", error, flush=True)
        shutil.rmtree(folder, ignore_errors=True)
        jobs_volume.commit()
        return {"deleted": True}

    return web


@app.function(image=web_image, volumes={"/jobs": jobs_volume}, schedule=modal.Cron("23 9 * * *"))
def cleanup_jobs():
    """Delete jobs older than JOB_RETENTION_DAYS (the app normally deletes them itself)."""
    import json
    import shutil

    jobs_volume.reload()
    limit = time.time() - JOB_RETENTION_DAYS * 86400
    removed = 0
    for folder in JOBS.iterdir() if JOBS.exists() else []:
        manifest = folder / "manifest.json"
        try:
            created = json.loads(manifest.read_text(encoding="utf-8"))["created"] if manifest.is_file() \
                else folder.stat().st_mtime
        except (ValueError, KeyError, OSError):
            created = 0
        if created < limit:
            shutil.rmtree(folder, ignore_errors=True)
            removed += 1
    jobs_volume.commit()
    print(f"Removed {removed} old jobs")


# ---------------------------------------------------------------------------
# Listening and speed test (runs on your Mac, calls the GPU container)
# ---------------------------------------------------------------------------

LANGUAGES = {"en": "English", "it": "Italian", "fr": "French", "de": "German", "es": "Spanish"}

# Original test passage (written for this test, not from a book), same scene in each language.
PASSAGES = {
    "en": "The rain had stopped by the time Clara reached the old station, but the platform still shone like a sheet of dark glass. She checked the clock above the ticket office: twenty past nine. The last train to Ashford was already eleven minutes late. \"Are you waiting for the nine o'clock?\" asked a man in a grey coat, folding his newspaper. \"I'm afraid it isn't coming tonight.\" Clara looked at him for a long moment. She had crossed three counties, sold her grandmother's ring, and argued with every conductor between here and the coast. \"Then I'll wait for the one that is,\" she said, and sat down on the bench, her suitcase pressed against her knees, while somewhere far away a whistle answered the wind.",
    "it": "La pioggia era cessata quando Clara arrivò alla vecchia stazione, ma il binario brillava ancora come una lastra di vetro scuro. Guardò l'orologio sopra la biglietteria: le nove e venti. L'ultimo treno per Ancona aveva già undici minuti di ritardo. «Aspetta quello delle nove?» chiese un uomo con un cappotto grigio, ripiegando il giornale. «Temo che stasera non arriverà.» Clara lo fissò a lungo. Aveva attraversato tre regioni, venduto l'anello della nonna e discusso con ogni controllore da qui fino al mare. «Allora aspetterò quello che arriva» rispose, e si sedette sulla panchina, con la valigia stretta contro le ginocchia, mentre da qualche parte, lontano, un fischio rispondeva al vento.",
    "fr": "La pluie avait cessé lorsque Clara arriva à la vieille gare, mais le quai brillait encore comme une plaque de verre sombre. Elle regarda l'horloge au-dessus du guichet : neuf heures vingt. Le dernier train pour Rouen avait déjà onze minutes de retard. « Vous attendez celui de neuf heures ? » demanda un homme en manteau gris, en repliant son journal. « J'ai bien peur qu'il ne vienne pas ce soir. » Clara le dévisagea longuement. Elle avait traversé trois départements, vendu la bague de sa grand-mère et discuté avec chaque contrôleur d'ici jusqu'à la mer. « Alors j'attendrai celui qui viendra », dit-elle, et elle s'assit sur le banc, sa valise serrée contre ses genoux, tandis qu'au loin un sifflet répondait au vent.",
    "de": "Der Regen hatte aufgehört, als Clara den alten Bahnhof erreichte, doch der Bahnsteig glänzte noch immer wie eine Scheibe aus dunklem Glas. Sie sah auf die Uhr über dem Fahrkartenschalter: zwanzig nach neun. Der letzte Zug nach Lübeck hatte bereits elf Minuten Verspätung. „Warten Sie auf den Neun-Uhr-Zug?“, fragte ein Mann im grauen Mantel und faltete seine Zeitung zusammen. „Ich fürchte, der kommt heute nicht mehr.“ Clara sah ihn lange an. Sie hatte drei Länder durchquert, den Ring ihrer Großmutter verkauft und mit jedem Schaffner zwischen hier und der Küste gestritten. „Dann warte ich auf den, der kommt“, sagte sie und setzte sich auf die Bank, den Koffer fest an die Knie gedrückt, während irgendwo in der Ferne ein Pfiff dem Wind antwortete.",
    "es": "La lluvia había cesado cuando Clara llegó a la vieja estación, pero el andén todavía brillaba como una lámina de vidrio oscuro. Miró el reloj sobre la taquilla: las nueve y veinte. El último tren a Zaragoza ya llevaba once minutos de retraso. —¿Espera el de las nueve? —preguntó un hombre con abrigo gris, doblando el periódico—. Me temo que esta noche no vendrá. Clara lo miró durante un largo rato. Había cruzado tres provincias, vendido el anillo de su abuela y discutido con cada revisor de aquí hasta la costa. —Entonces esperaré al que sí venga —dijo, y se sentó en el banco, con la maleta apretada contra las rodillas, mientras en algún lugar lejano un silbido le respondía al viento.",
}

BOOK_CHARS = 500_000


def _short_sample(text: str) -> str:
    """First two sentences, for quick per-voice samples."""
    sentences = re.split(r"(?<=[.!?])\s+", text)
    return " ".join(sentences[:2])


def _write_index(out: Path, info: dict, voice_rows: dict, pages: dict, metrics: dict) -> None:
    import html

    langs = list(LANGUAGES)
    head = "".join(f"<th>{LANGUAGES[l]}</th>" for l in langs)
    rows = []
    for voice, cells in voice_rows.items():
        tds = "".join(
            f"<td><audio controls preload=none src='{html.escape(cells[l])}'></audio></td>" if cells.get(l)
            else "<td>failed</td>" for l in langs)
        rows.append(f"<tr><th>{html.escape(voice)}</th>{tds}</tr>")
    page_cells = "".join(
        f"<tr><th>{LANGUAGES[l]}</th><td><audio controls preload=none src='{html.escape(p)}'></audio></td></tr>"
        for l, p in pages.items())
    stats = "".join(f"<tr><th>{html.escape(k)}</th><td>{html.escape(str(v))}</td></tr>" for k, v in metrics.items())
    out.write_text(f"""<!doctype html><meta charset=utf-8><title>FolioTone · Voxtral test</title>
<style>body{{font:15px system-ui;margin:24px;max-width:1400px}}table{{border-collapse:collapse;margin:12px 0 28px}}
td,th{{border:1px solid #ccc;padding:6px 8px;text-align:left;vertical-align:middle}}audio{{width:220px;height:32px}}</style>
<h1>Voxtral 4B TTS listening test</h1>
<p>{html.escape(info.get('gpu', ''))} · vLLM {html.escape(info['versions'].get('vllm', '?'))} ·
vLLM-Omni {html.escape(info['versions'].get('vllm-omni', '?'))}</p>
<h2>Full page per language</h2><table>{page_cells}</table>
<h2>Every voice in every language (first two sentences)</h2>
<table><tr><th>Voice</th>{head}</tr>{''.join(rows)}</table>
<h2>Measurements</h2><table>{stats}</table>
""", encoding="utf-8")


# Capitalised chapter headings for the before/after comparison (`modal run app.py --compare`).
HEADINGS = {"en": "CHAPTER III. THE LAST TRAIN.", "it": "CAPITOLO III. L'ULTIMO TRENO.",
            "fr": "CHAPITRE III. LE DERNIER TRAIN.", "de": "KAPITEL III. DER LETZTE ZUG.",
            "es": "CAPÍTULO III. EL ÚLTIMO TREN."}


def _compare(voice: str, language: str, text_file: str) -> None:
    """Render the same page with the original and the polished pipeline, side by side."""
    import html

    language = language if language in LANGUAGES else "en"
    voice = voice or DEFAULT_VOICES.get(language, "neutral_female")
    if text_file:
        text = Path(text_file).expanduser().read_text(encoding="utf-8").strip()[:MAX_PAGE_CHARS]
    else:
        text = f"{HEADINGS[language]} {PASSAGES[language]}"
    out = Path(__file__).resolve().parent / "samples" / "compare"
    out.mkdir(parents=True, exist_ok=True)
    name = f"{language}-{voice}"

    print(f"Rendering one page twice with '{voice}' ({len(split_text(text))} pieces before, "
          f"{len(split_text(prepare_text(text)))} after). First start can take several minutes…")
    run = Voxtral().synthesize.remote([
        {"id": "before", "text": text, "voice": voice, "polish": False},
        {"id": "after", "text": text, "voice": voice, "polish": True}], 2)
    cells = []
    for result in run["results"]:
        if "mp3" not in result:
            print(f"{result['id']} failed: {result.get('error')}")
            continue
        file = f"{name}-{result['id']}.mp3"
        (out / file).write_bytes(result["mp3"])
        shown = prepare_text(text) if result["id"] == "after" else text
        cells.append(f"<section><h2>{result['id'].title()} ({result['audio_seconds']} s)</h2>"
                     f"<audio controls src='{file}'></audio><p>{html.escape(shown)}</p></section>")
    page = out / f"{name}.html"
    page.write_text(
        "<!doctype html><meta charset=utf-8><title>FolioTone narration: before / after</title>"
        "<style>body{font:16px system-ui;margin:24px;max-width:900px}audio{width:100%}"
        "p{color:#444;line-height:1.5}</style>"
        f"<h1>{html.escape(name)}: before / after</h1>"
        "<p>Before: original text, pieces joined as generated. After: capitals and Gutenberg markup cleaned, "
        "each piece trimmed and levelled, same pause between pieces.</p>" + "".join(cells), encoding="utf-8")
    print(f"Open {page} to listen.")


@app.local_entrypoint()
def main(page_voice: str = "", concurrency: int = 16, copies: int = 6, texts: str = "",
         compare: bool = False, voice: str = "", language: str = "en", text_file: str = ""):
    """Listening and speed test.

    --compare      instead: render one page before/after the text and audio clean-up
                   (--voice, --language en|it|fr|de|es, --text-file page.txt to use a page of your own)

    --page-voice   voice for the full pages (default: casual_male)
    --concurrency  simultaneous requests to the model (default 16)
    --copies       pages per language in the speed test (default 6, i.e. 30 pages)
    --texts        optional folder with en.txt, it.txt, fr.txt, de.txt, es.txt to use your own pages
    """
    import json

    if compare:
        _compare(voice, language, text_file)
        return
    out = Path(__file__).resolve().parent / "samples" / "voxtral"
    (out / "voices").mkdir(parents=True, exist_ok=True)
    (out / "pages").mkdir(parents=True, exist_ok=True)

    passages = dict(PASSAGES)
    if texts:
        for lang in LANGUAGES:
            path = Path(texts).expanduser() / f"{lang}.txt"
            if path.is_file():
                passages[lang] = path.read_text(encoding="utf-8").strip()[:950]

    server = Voxtral()
    print(f"Starting {MODEL} on {GPU} (first run downloads ~8 GB of weights; allow several minutes)…")
    began = time.time()
    info = server.info.remote()
    first_reply = time.time() - began
    voices = info["voices"] or VOICES
    print(f"Ready after {first_reply:.0f}s (server start {info['startup_seconds']}s). {len(voices)} voices: {', '.join(voices)}")

    items = [{"id": f"{voice}|{lang}", "text": _short_sample(passages[lang]), "voice": voice}
             for voice in voices for lang in LANGUAGES]
    print(f"Rendering {len(items)} voice samples…")
    voice_run = server.synthesize.remote(items, concurrency)
    voice_rows: dict = {voice: {} for voice in voices}
    failures = []
    for result in voice_run["results"]:
        voice, lang = result["id"].split("|")
        if "mp3" in result:
            rel = f"voices/{lang}/{voice}.mp3"
            (out / rel).parent.mkdir(parents=True, exist_ok=True)
            (out / rel).write_bytes(result["mp3"])
            voice_rows[voice][lang] = rel
        else:
            failures.append(f"{result['id']}: {result.get('error')}")

    chosen = page_voice or ("casual_male" if "casual_male" in voices else voices[0])
    page_items = [{"id": f"{lang}|{n}", "text": passages[lang], "voice": chosen}
                  for n in range(max(1, copies)) for lang in LANGUAGES]
    print(f"Speed test: {len(page_items)} full pages with voice '{chosen}', concurrency {concurrency}…")
    page_run = server.synthesize.remote(page_items, concurrency)
    pages = {}
    ok = [r for r in page_run["results"] if "mp3" in r]
    for result in page_run["results"]:
        lang, n = result["id"].split("|")
        if "mp3" not in result:
            failures.append(f"page {result['id']}: {result.get('error')}")
        elif n == "0":
            (out / "pages" / f"{lang}.mp3").write_bytes(result["mp3"])
            pages[lang] = f"pages/{lang}.mp3"

    chars = sum(r["chars"] for r in ok)
    audio_seconds = sum(r["audio_seconds"] for r in ok)
    wall = page_run["wall_seconds"]
    price = GPU_PRICE_PER_SECOND.get(GPU.upper(), GPU_PRICE_PER_SECOND["L4"])
    chars_per_second = chars / wall if wall else 0
    book_seconds = BOOK_CHARS / chars_per_second if chars_per_second else float("nan")
    metrics = {
        "GPU": info["gpu"],
        "First reply (container + model start)": f"{first_reply:.0f} s",
        "Speed test pages": f"{len(ok)} of {len(page_items)} succeeded",
        "Characters per second": f"{chars_per_second:.0f}",
        "Audio seconds per wall second (× real time)": f"{audio_seconds / wall:.1f}" if wall else "n/a",
        "Characters per audio minute": f"{chars / (audio_seconds / 60):.0f}" if audio_seconds else "n/a",
        f"Estimated GPU time for a {BOOK_CHARS:,}-character book": f"{book_seconds / 60:.0f} min",
        "Estimated GPU cost per book (no overhead)": f"${book_seconds * price:.2f}",
        "Pages flagged as possibly truncated": sum(1 for r in ok if r.get("suspicious")),
        "Versions": json.dumps(info["versions"]),
    }
    _write_index(out / "index.html", info, voice_rows, pages, metrics)
    report = {"info": info, "metrics": metrics, "failures": failures,
              "voice_run": {k: v for k, v in voice_run.items() if k != "results"},
              "page_run": {k: v for k, v in page_run.items() if k != "results"},
              "page_results": [{k: v for k, v in r.items() if k != "mp3"} for r in page_run["results"]]}
    (out / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n".join(f"  {k}: {v}" for k, v in metrics.items()))
    if failures:
        print(f"{len(failures)} failures, first: {failures[0]}")
    print(f"\nOpen {out / 'index.html'} to listen.")
