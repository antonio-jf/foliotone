# Restoring the FolioTone source archive

Archived on 2026-11-09. This archive contains the Android source and resources, Gradle wrapper and build configuration, development documentation, and the separate Voice Lab source with its dependency lock and pinned model downloader.

Downloaded model weights, Python environments/runtimes, package caches, Android build output, IDE state, machine-specific `local.properties`, and generated sample audio are excluded. The downloaded Qwen3-TTS model was also removed from the original project directory, and its local Voice Lab server was stopped for archiving.

The Android app does not use the Qwen model. The Voice Lab needs its dependencies and model recreated before it can generate audio. The archive is not a fully offline build environment; restoring missing SDKs, Gradle/Maven dependencies, Python packages, or model weights requires download access.

## Restore the Android project

1. Extract the archive. Its project directory is `reader/`.
2. Open that directory in Android Studio and configure Android SDK 35. Android Studio can recreate `local.properties` with the SDK location on the new machine.
3. Use JDK 17 or the previously verified JDK 21 setup. The project's Gradle 8.9 setup did not work with the Java 25 runtime tested during development.
4. Build the `app` configuration or run the following from the extracted project directory:

```sh
chmod +x gradlew tts-lab/setup.sh tts-lab/run.sh
./gradlew assembleDebug
```

If building without Android Studio, set the Android SDK location through the environment or a new `local.properties`, for example `sdk.dir=/absolute/path/to/your/Android/sdk`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. See [README.md](README.md) for app usage and [the development handoff](docs/DEVELOPMENT_HANDOFF.md) for architecture, verified changes, remaining validation, and the working setup used on the original Mac.

## Restore the optional Voice Lab

On Apple Silicon, install `uv` and make Python 3.12 available, then run:

```sh
cd tts-lab
./setup.sh
./run.sh
```

Setup recreates `.venv` from `requirements.lock.txt` and downloads the model pinned in `download_model.py`: `mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-8bit`, revision `41d3337e8b7f2843a75841595fc14e4b9a7a4b96`. Expect roughly 3.08 GB for model files, plus dependencies and caches. Open `http://127.0.0.1:8765` after startup. See the [Voice Lab README](tts-lab/README.md) for full setup, network, and usage details.

## Archive contents and integrity

`ARCHIVE_MANIFEST.sha256` inside the ZIP lists the included project files and their SHA-256 hashes. After extraction, verify from the `reader/` directory with:

```sh
shasum -a 256 -c ARCHIVE_MANIFEST.sha256
```

If a ZIP checksum file was saved alongside the ZIP, keep it with the archive to verify the complete compressed file later.

Books, progress, ratings, downloaded narration, and API keys stored inside an installed Android app are separate device data and are not included in this source archive. There are no API keys bundled with the project.
