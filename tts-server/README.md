# FolioTone narration server (Modal + Voxtral)

Your own narration server for FolioTone. It runs [Voxtral 4B TTS](https://huggingface.co/mistralai/Voxtral-4B-TTS-2603) with vLLM-Omni on a Modal GPU. Background, pricing and alternatives: [docs/SELF_HOSTED_TTS_PROPOSAL.md](../docs/SELF_HOSTED_TTS_PROPOSAL.md).

Voxtral's weights and reference voices are licensed CC BY-NC 4.0. That's fine for private, non-commercial use; publishing or charging for the app needs a different model or a license from Mistral.

## How it works

1. In the app, **Manage offline audio → Download book audio** sends the book's pages, as text, to `POST /v1/jobs`.
2. Modal starts an L4 GPU (cold start takes a few minutes). It reads every page with the voice chosen for the book's language and saves one MP3 per page to the `foliotone-jobs` Volume.
3. The app polls the job, downloads each page as soon as it's ready, and deletes the job at the end. Pages are saved on the phone, so listening works offline.
4. The GPU shuts down two minutes after the last job. The web API runs on a cheap CPU container. A daily cleanup deletes any job older than 3 days.

Measured on an L4 in the listening test: about 240 characters per second, roughly 15× faster than real time. A 500,000-character novel takes about 35 minutes of GPU time, about $0.50 at Modal's list price, plus the cold start.

## Setup (once, on your Mac)

```sh
uv tool install modal      # needs Modal 1.6 or newer: `modal --version`
modal setup                # links this Mac to your Modal account
```

In the Modal dashboard:

1. **Usage & Billing**: set a workspace spending limit, so nothing beyond the monthly free credit can be charged.
2. **Settings → Proxy Auth Tokens**: create a token. Copy the **Token ID** (`wk-…`) and **Token Secret** (`ws-…`). The secret is only shown once. Create one token per person so each can be revoked separately.

## Deploy

From the project folder:

```sh
modal deploy tts-server/app.py
```

The output lists the API address, ending in `-api.modal.run`, for example `https://<workspace>--foliotone-tts-api.modal.run`. Re-run the same command after changing `app.py`.

If `tts-server/samples/voxtral/voices/` exists, which the listening test creates, those samples are bundled with the API and the app uses them for instant voice previews. Without them, previews report that no sample is available.

## Connect the app

On the phone: **Listening settings → Change narration source → Your narration server**. Enter the API address, token ID and token secret, then tap **Save and connect**. The app checks the server and stores the settings encrypted on the device. Then choose a voice per language. Each language defaults to its native female voice, and English defaults to `neutral_female`.

To listen to a book, open it and choose **Manage offline audio → Download book audio**. The first page arrives once the GPU has started, which takes a few minutes on a cold start. Pages keep arriving while you read. Pausing the download doesn't stop the server; resuming picks up the same job.

## API

Every request needs the `Modal-Key` and `Modal-Secret` headers (Modal proxy auth). Requests without them are rejected before any container starts.

| Method and path | Purpose |
| --- | --- |
| `GET /v1/health` | Model, voices and default voice per language |
| `GET /v1/voices/{voice}/sample?language=it` | Pre-rendered preview MP3 (no GPU) |
| `POST /v1/jobs` | `{"book": "…", "voice": "it_female", "pages": [{"key": "<sha256 hex>", "text": "…"}]}` → `{"id", "total", "characters"}` |
| `GET /v1/jobs/{id}?after=N` | `state` (`queued`, `running`, `done` or `failed`), counts, and the next finished page keys after position N |
| `GET /v1/jobs/{id}/audio/{key}` | MP3 of one page |
| `DELETE /v1/jobs/{id}` | Cancel and delete the job |

Page keys are `sha256("voxtral-4b-2603|<voice>|<page text>")`, computed by the app. They name the files on both sides, so a page is never generated twice for the same voice.

## Listening and speed test

```sh
modal run tts-server/app.py                     # every voice × language, plus a speed test
modal run tts-server/app.py --texts ~/pages     # use your own en.txt, it.txt, fr.txt, de.txt, es.txt
FOLIOTONE_GPU=A10 modal run tts-server/app.py   # different GPU type
modal run tts-server/app.py --compare                       # one page, before/after the clean-up below
modal run tts-server/app.py --compare --language es --voice es_male --text-file ~/page.txt
```

Results are written to `tts-server/samples/voxtral/`. Open `index.html` there to listen.

## Text and audio clean-up

Each page is narrated in pieces of up to 400 characters, generated separately, so the voice restarts its
intonation at every piece (Voxtral's output is fixed for a given text, and it takes no seed or temperature).
To make the joins less noticeable the server (`prepare_text` and `_polish` in app.py):

- turns words in capitals into normal case (`THE LAST TRAIN` → `The Last Train`), keeping short lone
  abbreviations (BBC) and Roman numerals; drops Gutenberg `_italic_`/`*` marks, `[1]` and `[Illustration]`;
  turns `--` into a dash;
- trims the silence at each piece's edges, levels every piece to the same loudness and joins them with the
  same 0.32 s pause.

Page keys still use the text the app sent, so saved audio stays valid; only newly rendered or regenerated
pages use the clean-up. `--compare` renders one page both ways into `samples/compare/`.

## Troubleshooting

- **Image build fails installing vLLM:** `vllm` and `vllm-omni` must be the same release. Both are pinned to 0.32.0 in `app.py`.
- **`vLLM-Omni exited with code 1`:** the server log above that line explains why. For memory errors, try `FOLIOTONE_VLLM_ARGS="--gpu-memory-utilization 0.85"`.
- **App says the token was rejected (HTTP 401/403):** recreate the proxy auth token and enter both its ID and secret.
- **A job stays `queued`:** the GPU is starting or waiting for capacity. Open the app `foliotone-tts` in the Modal dashboard and check the `Voxtral.render_job` logs.
- **Some pages failed:** the download stops and reports how many. Download again to retry only those pages.
