# Proposal: self-hosted narration for FolioTone

Status: **for review, nothing implemented yet**. Prepared 2026-11-09. Prices and benchmarks were checked on that date and are listed under [Sources](#sources).

## 1. Summary

- **What:** add a third narration source, *FolioTone server*, next to Device voices and ElevenLabs. A serverless GPU generates a whole book's audio in one batch job. The phone downloads one audio file per page and plays it through the same path that ElevenLabs offline audio uses today.
- **Where:** Modal serverless GPUs. Each job runs on an L4 GPU and the service shuts down when idle, so there is no charge between books. The server API stays independent of the host, so the same app setting can later point to your Mac over Tailscale.
- **Which model:** a short bake-off between **Voxtral 4B TTS** (Mistral, open weights) and **Qwen3-TTS 1.7B** (Alibaba, Apache 2.0). Voxtral is the expected winner. It scores clearly higher in blind listening tests and has preset voices for Italian, French, German and Spanish. Its license is non-commercial, which fits a private app.
- **Cost:** about **$0.50–2.20 for a 500,000-character novel**, compared with about **$50** on ElevenLabs Multilingual v2. For two or three readers this should usually stay inside Modal's **$30 monthly free credit**.
- **App work:** a new server client, a server option in the offline download service, a playback branch in `ReaderService`, and a settings screen. Speed changes happen on the phone, so changing the speed no longer means generating the audio again.

## 2. Assumptions

| Item | Value used |
| --- | --- |
| Users | 2–3 people, private and non-commercial use |
| Typical book | 500,000 characters (about 90,000 words) |
| Audio length | About 900 characters per minute, so a typical book is about **9.3 hours** of audio |
| Normal usage | 6 books a month across all users. A heavy month is 15 books. |
| Languages | English, Italian, French, German and Spanish |
| App pages | About 950-character chunks from `Epub.paginate()`, up to 12,000 pages per book |
| Listening pattern | Download first, then listen. Generating audio live during playback is optional later work (phase 3). |

## 3. Model choice

| | **Voxtral 4B TTS** (`mistralai/Voxtral-4B-TTS-2603`) | **Qwen3-TTS 1.7B CustomVoice** | Fish Audio S2 Pro | Kokoro 82M |
| --- | --- | --- | --- | --- |
| License | CC BY-NC 4.0: private use is fine; commercial use needs an agreement with Mistral | Apache 2.0, including commercial use | Fish Audio Research License: non-commercial only | Apache 2.0 |
| Covers all 5 languages | Yes (9 languages) | Yes (10 languages) | Yes (80+). Italian is not in its top quality tiers. | No German in the official release; one community German voice |
| Voices native to Italian, French, German and Spanish | **Yes**: 20 preset voices across its languages | **No**: the 9 speakers are native in Chinese, English, Japanese or Korean, so European languages are spoken by non-native voices and may have an accent | Voice cloning only | Few voices |
| Blind-test score (Artificial Analysis Elo) | about 1,056–1,077 | about 917–931 | about 1,123 | about 1,056 |
| Size and GPU memory | 4B parameters, fits on a 16 GB GPU | 1.7B parameters, a few GB | About 5B parameters, about 24 GB at bf16 | Tiny; runs on a CPU or the phone |
| Serving software | vLLM-Omni (official), SGLang-Omni; 4-bit MLX build for the Mac | vLLM-Omni, faster-qwen3-tts; MLX already used in your Voice Lab | SGLang | Many |

For reference, ElevenLabs Multilingual v2 scores about 1,102 on the same scoreboard. Voxtral comes close to it; Qwen3-TTS is noticeably lower. The scoreboard is built mostly from short English clips, so long passages in Italian, French, German and Spanish still have to be judged by ear. That is the purpose of phase 0.

**Recommendation:**

1. Run both Voxtral and Qwen3-TTS on the same five pages in each of the five languages and listen to the results.
2. Choose Voxtral unless it sounds clearly worse in practice.
3. Keep Qwen3-TTS as the fallback if the license ever matters, for example if the app is published.
4. Leave Kokoro for a possible future on-device offline mode.
5. Skip Fish S2 Pro for now because of its license and size.

## 4. Hosting options

### 4.1 Modal (recommended)

**GPU prices (per second):**

| GPU | Per second | Per hour |
| --- | --- | --- |
| L4 | $0.000222 | $0.80 |
| A10 | $0.000306 | $1.10 |
| L40S | $0.000542 | $1.95 |
| H100 | $0.001097 | $3.95 |

**What the free Starter plan includes:**
- $30 of free compute each month.
- Up to 3 workspace seats.
- Up to 10 GPUs running at the same time.
- 1 TiB of storage and 1 TiB of outbound data transfer free each month.

**Other relevant features:**
- **Budgets:** a monthly usage limit and a spending limit can be set for the workspace. Set the spending limit to $0 or a small amount so nothing beyond the free credit is ever charged by surprise.
- **Access control:** proxy auth tokens protect the web endpoints. Unauthorized requests are rejected before any container starts, so they cost nothing.
- **Startup time:** containers scale to zero. The idle window before shutdown can be set from 2 seconds to 20 minutes. Memory snapshots, including GPU snapshots, cut startup time. Modal reports GPU snapshots taking a small model from about two minutes to about ten seconds.

**Avoid two price multipliers:** choosing a specific region costs 1.15–1.75× and non-preemptible containers cost 3×. Batch jobs don't need either, as long as the job can resume after an interruption. The design below allows that.

### 4.2 RunPod Serverless (alternative)

- About $0.69 per hour for a 24 GB GPU class (L4, A5000 or 3090) on scale-to-zero "flex" workers.
- FlashBoot makes restarts fast.
- Billing covers startup and idle time.
- There is no monthly free credit, and setup is based on Docker images.

RunPod is slightly cheaper per hour than Modal. Without the $30 credit it costs more at your usage level. It's a reasonable switch if usage grows.

### 4.3 Hosted APIs (no server to run)

| Service | Model | Price per 1M characters | Per book | 6 books a month | Notes |
| --- | --- | --- | --- | --- | --- |
| ElevenLabs (current) | Multilingual v2 | $100 | $50 | $300 | API rate; subscription credits work out differently |
| fal | Qwen3-TTS 1.7B | $90 | $45 | $270 | Same model as self-hosting, but at nearly ElevenLabs prices |
| Google Cloud | Chirp 3 HD | $30 | $15 | $60 | First 1M characters free each month |
| DeepInfra | Qwen3-TTS | $20 | $10 | $60 | |
| Mistral API | Voxtral TTS | $16 | $8 | $48 | Same model as the Voxtral recommendation, with no server to run |
| Alibaba Model Studio | qwen3-tts-flash | $10 | $5 | $30 | Accepts at most 600 characters per request, so each page must be split |
| DeepInfra | Kokoro 82M | about $0.62–0.80 | about $0.40 | about $2 | Cheapest option, but weak in German |

The Mistral API is a useful zero-maintenance fallback: the same Voxtral voices for about $8 a book.

### 4.4 Your Mac (later)

- The server API is the same, so the app only needs a different URL and token.
- Voxtral has a 4-bit MLX build, and your Voice Lab already runs Qwen3-TTS on MLX.
- Tailscale can give the Mac a private HTTPS address reachable from the phones.
- It costs nothing but only works while the Mac is awake, and it will be slower than a datacenter GPU.

## 5. Inference speed

All of these are published figures from other people's hardware. None has been measured on our setup yet.

| Setup | Published result |
| --- | --- |
| Qwen3-TTS 1.7B, faster-qwen3-tts (CUDA graphs), one request at a time | 4.85× real time on an RTX 4090; 3.98× on an H100 |
| Qwen3-TTS 1.7B, vLLM-Omni, H200, 10 requests at once | 47 seconds of audio per second |
| Qwen3-TTS (Rust batched server), single L4, 16 requests at once | 16.6× real time, using about 5 GB of GPU memory |
| Qwen3-TTS 1.7B, Baseten-tuned vLLM-Omni, H100, 25 streams | 594 characters per second, about $3.04 per 1M characters at H100 list price |
| Voxtral 4B (model card) | One request: 70 ms to first audio, about 9.7× real time. 32 requests at once: about 1,430 characters per second per GPU (GPU model not stated). |

**Estimates for one book (9.3 hours of audio):**

| GPU and speed | GPU time | Cost incl. 20% overhead | Elapsed time on one GPU |
| --- | --- | --- | --- |
| L4 at 4× real time (pessimistic) | 2.3 h | $2.22 | about 2 h 20 min |
| L4 at 10× real time (likely) | 0.93 h | $0.89 | about 56 min |
| L4 at 16× real time (optimistic) | 0.58 h | $0.56 | about 35 min |
| H100 at 25–40× real time | 0.23–0.37 h | $1.10–1.76 | about 15–22 min |
| Voxtral on an H100-class GPU, using the model-card throughput | about 6 min | about $0.46 | about 6 min |

The 20% overhead covers startup, CPU, memory and audio encoding.

To finish a book faster without spending more, split it by section across several L4s. The Starter plan allows up to 10 GPUs at once, and four L4s would finish a typical book in roughly 10–35 minutes for the same total cost. Each GPU's startup costs about $0.03 extra.

## 6. Monthly cost

| Option | 6 books a month | 15 books a month |
| --- | --- | --- |
| **Modal, self-hosted (L4)** | **about $3–13: covered by the $30 credit** | about $8–33: mostly covered |
| Mistral Voxtral API | $48 | $120 |
| Google Chirp 3 HD | $60 | $195 |
| ElevenLabs Multilingual v2 | $300 | $750 |

There is no charge while idle. Storage stays inside Modal's free 1 TiB, and generated audio is deleted after the phones download it.

## 7. Architecture

```
Phone (FolioTone)                         Modal workspace
─────────────────                         ───────────────────────────────────────────
Manage offline audio → Download
  POST /jobs {book, lang, voice, pages[]} ─▶ CPU web endpoint (FastAPI, proxy auth)
                                              │  writes job manifest to a Volume
                                              └─▶ spawn GPU worker(s) on L4
                                                   • start vLLM-Omni with the chosen model
                                                     (model weights cached on a Volume)
                                                   • send pages to the model 16–32 at a time
                                                   • encode MP3 at 48 kbps, one file per page
                                                   • skip pages already done (so a
                                                     restarted worker can resume)
  GET /jobs/{id}  (poll every 15–30 s) ◀───── progress: done / total / errors
  GET /jobs/{id}/pages/{n} ◀──────────────── download each finished page
  DELETE /jobs/{id} ─────────────────────▶ delete job; a daily cleanup job removes
                                              anything older than 7 days
```

**Endpoints:**

| Method and path | Purpose |
| --- | --- |
| `GET /health` | Server version, loaded model, languages and voices; used by "Test connection" in settings |
| `POST /jobs` | Start a book job: book hash, language, voice, model, and a list of pages (section, page, text). A novel is about 0.5–1 MB of JSON. |
| `GET /jobs/{id}` | Job status and progress |
| `GET /jobs/{id}/pages/{n}` | Download one page of audio. A bundled download of many pages can be added if per-page requests turn out to be slow. |
| `DELETE /jobs/{id}` | Delete the job's files |
| `POST /preview` | Short sample used when choosing a voice. It starts a GPU, so it can take tens of seconds when nothing is running. |

**Design decisions:**

- **The phone doesn't need to stay awake.** Generation happens on the server. If Android stops the download service, which it does to background data transfers after a time limit, the job keeps running. When the app reopens it continues downloading using the saved job ID.
- **Speed is applied on the phone.** Audio is generated at normal speed and played faster or slower with `MediaPlayer.setPlaybackParams()`, available on all supported Android versions (8.0+). Today the ElevenLabs cache key includes the speed, so every speed change means paying to generate the book again. This design avoids that.
- **File naming:** each page's audio is named by a SHA-256 hash of model, voice, language and text, the same approach `ElevenLabs.audioName()` uses. If the book is split into pages differently later, audio for unchanged pages is still found.
- **Consistent voice:** every page uses the same preset voice and a fixed seed. The server may split a page into sentences if the model handles shorter input better, then join the audio back into one file per page.
- **Security:** each person gets their own revocable access token, stored encrypted on the phone like the ElevenLabs key. Workspace budgets cap spending. Book text goes only to your own Modal workspace; ElevenLabs receives it today.

## 8. Changes to the Android app

| File | Change |
| --- | --- |
| `BookStore.kt` | The `narrationSource` setter currently turns any value other than `elevenlabs` into `device`. It must accept `server`. New preferences: `server_url`, `server_model`, `server_voice_<lang>`, `server_job_<bookId>`. |
| `ElevenLabs.kt`, split into a new `SecretStore.kt` | Turn `ElevenLabsKey` into a general Keystore-encrypted secret store so the server token gets the same protection (excluded from backup, AES-GCM). |
| New `NarrationServer.kt` | HTTPS client for the endpoints above: submit job, poll, download page with a size limit, list voices, preview, delete. Clear error messages, following the pattern in `ElevenLabs.checkResponse()`. |
| `OfflineDownloadService.kt` | Choose behavior by narration source. The server version submits or resumes a job, polls, downloads finished pages, and reports progress in the existing notification. The existing pause and Android-time-limit handling still applies. |
| `ReaderService.kt` | `speak()` gets a `server` branch that plays the saved page file through a generalized `playCloudFile()`, renamed `playAudioFile()`. In phase 1 a missing page pauses with "Download this book's audio first." The check `store.narrationSource != "elevenlabs"` around line 470 must be generalized. Speed is set with `PlaybackParams`. No lookahead is needed because the files are on the phone. The token checks, audio-focus order and `haltAudio()` behavior stay exactly as they are. |
| `MainActivity.kt` | Listening settings gets a third source and a server card: URL, token, Test connection, model, voice per language with preview, storage used, and Remove. "Manage offline audio" appears for the server source too (`showReader()` around line 1563, `readerOptions()` around line 1838). |
| `SectionTimeline.kt` (optional) | Use real MP3 durations when they are available instead of the 150 words-per-minute estimate. |
| `AndroidManifest.xml` | No change. Internet permission is already declared, and both Modal and Tailscale use HTTPS. |
| New `tts-server/` folder | Modal app (`app.py`), pinned dependencies, deploy and budget instructions. It sits next to `tts-lab/` and doesn't depend on it. |
| Docs | Update `README.md` and `docs/DEVELOPMENT_HANDOFF.md`. |

**Storage on the phone:** about 200 MB per novel at 48 kbps mono MP3, or about 270 MB at 64 kbps. MP3 is used because Android plays it on every supported version. Opus would be about half the size but only plays reliably in its usual container on newer Android versions.

## 9. Plan

| Phase | Work | Rough effort | Cost |
| --- | --- | --- | --- |
| 0. Bake-off | Deploy both models on Modal. Generate one chapter in each of the five languages. Measure characters per second, real cost and startup time. You listen and choose a model and voices. | About 0.5–1 day | Less than $2 (covered by credit) |
| 1. Download and play | Server job API, budget, the app client, the offline download service changes, the `ReaderService` branch, basic settings | About 3–4 days | — |
| 2. Polish | Voice previews, storage management, cleanup job, fanning a book out across several GPUs, docs | About 1–2 days | — |
| 3. Optional | Generate-ahead live mode: pressing Play on a book with no audio starts a job for the current section and playback begins once the first pages arrive. Mac/Tailscale as a target. | About 2 days | — |

## 10. Risks and open questions

**Risks:**
- **License:** Voxtral's non-commercial license fits a private app shared by two or three people. If FolioTone is ever published or monetized, switch to Qwen3-TTS (Apache 2.0) or the paid Mistral API. *I'm not a lawyer; read the license text before relying on this.*
- **Long-passage quality:** the scoreboard uses short clips. Changes in tone between pages, mispronounced names, and Qwen's non-native accents in European languages can only be judged in phase 0.
- **Speed control:** `PlaybackParams` keeps pitch steady, but speech sped far beyond 1.3× can sound artificial. This needs a quick test.
- **GPU availability and interruptions:** L4s are usually available. Jobs resume page by page if interrupted.
- **Startup delay:** acceptable for batch jobs. It's why live mode is deferred.
- **Book content:** the text is processed in your own Modal workspace. Generating audio from books you own, for private listening, follows the same pattern as the ElevenLabs feature today; check local rules if you're unsure.

**Questions for you:**
1. Is Voxtral's non-commercial license acceptable given private use, or should only Apache-licensed models be considered?
2. Is "download the book first, then listen" acceptable for version 1, with live generation coming later?
3. Should there be one shared Modal workspace with a separate token per person (recommended), or separate workspaces?
4. Is about 200 MB per downloaded novel acceptable, or should a lower bitrate be used?

## Sources

- Modal pricing (GPU rates, Starter plan, multipliers): https://modal.com/pricing
- Modal budgets and spend limits: https://modal.com/docs/guide/budgets
- Modal startup time and idle window: https://modal.com/docs/guide/cold-start
- Modal GPU snapshots (Mistral 3 example, about 2 min to about 10 s): https://modal.com/blog/mistral-3
- Modal proxy auth tokens: https://modal.com/blog/modal-product-updates-jan-2025
- RunPod serverless pricing and billing: https://docs.runpod.io/serverless/pricing, https://runpod.io/serverless-gpu
- Qwen3-TTS model card (languages, license, speakers): https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice
- Qwen3-TTS speaker native languages: https://www.mintlify.com/QwenLM/Qwen3-TTS/concepts/languages
- faster-qwen3-tts benchmarks: https://pypi.org/project/faster-qwen3-tts/0.1.2/
- vLLM-Omni Qwen3-TTS performance: https://docs.vllm.ai/projects/vllm-omni/en/stable/design/qwen3_omni_tts_performance_optimization/
- Batched Qwen3-TTS on L4: https://huggingface.co/alfonsodlg/concurrent-faster-qwen3-server
- Baseten Qwen3-TTS cost study: https://www.baseten.co/blog/cost-efficient-high-performance-qwen3-tts/
- Voxtral 4B TTS model card: https://huggingface.co/mistralai/Voxtral-4B-TTS-2603
- Voxtral license and API price: https://ai-tldr.dev/models/voxtral-tts/
- Voxtral throughput table: https://app.alphaneural.io/assets/Eco9689/VoiceForge
- Voxtral on Mac (MLX): https://insiderllm.com/guides/mistral-voxtral-tts-local-voice-ai/
- Fish Audio S2 (languages, license, performance): https://fish.audio/s2/
- Artificial Analysis open-weights leaderboard: https://artificialanalysis.ai/text-to-speech/leaderboard/provider-voice
- Scores for ElevenLabs and Qwen3 TTS on the same board: https://benchmarklist.com/arenas/artificial_analysis_provider_voice/
- ElevenLabs API pricing: https://developer.puter.com/tutorials/elevenlabs-api-pricing/
- fal Qwen3-TTS pricing: https://fal.ai/models/fal-ai/qwen-3-tts/text-to-speech/1.7b
- DeepInfra Qwen3-TTS: https://deepinfra.com/Qwen/Qwen3-TTS/api
- DeepInfra Kokoro: https://deepinfra.com/hexgrad/Kokoro-82M/api
- Alibaba Model Studio Qwen3-TTS-Flash: https://www.alibabacloud.com/help/en/model-studio/qwen-tts
- Google Cloud Text-to-Speech pricing: https://cloud.google.com/text-to-speech/pricing
- Kokoro on Android (supported languages): https://soniqo.audio/guides/kokoro/android
