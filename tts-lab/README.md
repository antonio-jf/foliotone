# FolioTone Voice Lab

A local browser playground for Qwen3-TTS on Apple Silicon. Uses the 1.7B CustomVoice model converted to MLX with 8-bit weights, with nine preset speakers, five book languages, delivery instructions, playback, and WAV download. This playground runs separately from the Android app.

## Start

```sh
cd ~/Development/reader/tts-lab
./run.sh
```

Open <http://127.0.0.1:8765>. The page reports when the model is ready. Paste up to 1,000 characters, choose the text's language and a speaker, then generate. Audio appears when generation completes. The first request can take longer while Metal compiles its kernels. Press Ctrl+C in the server terminal to stop it. Set `PORT=8766 ./run.sh` to use a different port.

Generation runs on this Mac's GPU. The server binds to loopback only; no account or API key is required. After setup, the launcher enables offline mode. Text and generated audio remain in memory unless you download the WAV. Requests run one at a time; competing requests receive HTTP 409. The browser offers English, Italian, French, German, and Spanish samples. Speaker native languages are shown because accents and quality can vary across languages.

## Install or recreate

Requires Apple Silicon, Python 3.12 and `uv`. Dependencies and model files are stored in this directory. Use the following proxy settings on the network where they are needed:

```sh
export HTTP_PROXY=http://www-proxy.us.oracle.com:80
export HTTPS_PROXY=http://www-proxy.us.oracle.com:80
export NO_PROXY=artifacthub-pypi.oci.oraclecorp.com,artifacthub-phx.oci.oraclecorp.com,localhost,127.0.0.1
./setup.sh
```

The setup script installs the tested dependency versions from `requirements.lock.txt` and downloads the pinned model snapshot. Downloads can be resumed by rerunning setup. No proxy is needed for local inference.

- Model: [mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-8bit](https://huggingface.co/mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-8bit)
- Snapshot: `41d3337e8b7f2843a75841595fc14e4b9a7a4b96`
- Runtime: [MLX Audio](https://github.com/Blaizzy/mlx-audio)
- Original model and license: [Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS), Apache 2.0

## Local API

Interactive API documentation is available at <http://127.0.0.1:8765/docs> and the generated OpenAPI contract at `/openapi.json`.

- `GET /api/status`: loading/ready/error state, busy flag, model ID, languages, voices, and text limit.
- `POST /api/speech`: JSON `{ "text": "Hello there.", "language": "English", "voice": "Ryan", "instruction": "Read warmly." }`. Returns PCM-16 WAV audio and `X-Generation-Seconds` / `X-Audio-Seconds` headers. Text: 1–1,000 characters, instruction: up to 300. Unsupported options or empty text return 422; loading/unavailable model returns 503; busy model returns 409; generation failure returns 500.

```sh
curl --noproxy '*' http://127.0.0.1:8765/api/speech \
  -H 'Content-Type: application/json' \
  -d '{"text":"Welcome to your personal library.","language":"English","voice":"Ryan"}' \
  --output sample.wav
```

This is a short-passage testing server. Audio is returned as a complete WAV, and there is no persistent job queue or voice-cloning interface. It does not yet change narration in the mobile app.

## Verified locally

On this M3 Pro, live requests produced valid WAV audio for all five languages. The first English request produced 4.96 seconds of audio in 8.27 seconds; subsequent short language samples generated faster than playback. These are short-sample measurements, not a throughput benchmark. Status remained responsive during generation, a competing request returned 409, and blank/oversized text plus unsupported language/voice selections returned 422. The browser form was also checked end to end. Listening quality and accent preference still need your evaluation.
