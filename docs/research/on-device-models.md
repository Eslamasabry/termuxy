# Research: On-Device Models for Phones (kept for later)

> Reference notes on the on-device LLM / ASR landscape as of mid-2026. Captured during the
> voice-mode decision. We chose **whisper.cpp in the Linux env** for transcription now; the options
> below are kept on file for future direction (e.g. on-device LLM agent, better ASR).

## Gemma family (Google — multimodal, can transcribe)

- **Gemma 3n (E2B / E4B)** — *mobile-first*. Multimodal input: text + image + **audio** + video.
  Built-in audio encoder → can do ASR. Runs via Google AI Edge / MediaPipe. Designed for
  low-resource devices. The "phone-specific Gemma."
- **Gemma 4** — strong **audio understanding / ASR / translation**. In several 2026 benchmarks it
  *beats Whisper* at transcription. (ai.google.dev/gemma/docs/capabilities/audio)
- **Gemini Nano** — the system on-device model on Pixel/Galaxy. Powerful but **not freely callable
  by third-party apps** (locked behind AICore). Not a realistic integration target today.

## Other phone-specific text LLMs (2026)

- **Llama 3.2 1B / 3B** — best all-rounder; runs on phones with 6GB+ RAM. Community favorite.
- **Phi-4 Mini (3.8B)** — smartest small model; flagship 8GB+ phones, ~13–18 tok/s.
- **Qwen3-4B-2507** — reported most powerful on mobile overall.
- **SmolLM, Nemotron Nano 4B, MobileLLM** — alternatives in the <4B mobile bracket.
- Run via **llama.cpp / ollama / MLC** (GGUF). In termuxy specifically these can run *inside the
  proot Linux env* — no JNI/MediaPipe needed.

## Transcription-specific (on-device ASR)

| Model | Size | Accuracy | Notes |
|---|---|---|---|
| **whisper.cpp** (base/small) | ~74MB–240MB | High | Safe, accurate; hallucinates in silence. Our choice. |
| **Gemma 3n / 4 audio** | E2B ~2B eff | High (beats Whisper in tests) | A true LLM doing ASR. Heavier; via AI Edge. |
| **faster-whisper / WhisperX** | similar | Higher (less hallucination) | Python; runs in proot. |
| **Vosk** | ~40–50MB | Medium (older Kaldi) | Tiny + fast; lowest accuracy. |
| **SeamlessM4T** | large | High + translation | Heavy; batch-oriented. |

## Tooling reality (Android, 2026)

- **MediaPipe LLM Inference API was deprecated for Android/iOS (Mar 2026).** Successor =
  **Google AI Edge (LiteRT)**. The JNI/native-in-APK path is now fragile and bleeding-edge.
- **Termuxy's unique advantage:** it ships a full Linux env (proot Debian). So model runtimes
  (**llama.cpp, whisper.cpp, ollama, faster-whisper**) run *inside* the app exactly like on a real
  box — no deprecated APIs, model-agnostic, works today. proot shares the network namespace, so a
  local whisper.cpp server at `127.0.0.1:8080` inside proot is reachable from the Android app.

## Decision log

- **Now (voice transcription):** whisper.cpp server in the Linux env; Android records mic and POSTs
  WAV over loopback. Robust, model-agnostic, leverages the terminal nature.
- **Later option A (better ASR):** swap whisper base.en → small/large, or faster-whisper in proot.
- **Later option B ("LLM transcribes"):** Gemma 3n E2B audio via Google AI Edge, or a Gemma/GGUF
  model in llama.cpp fed audio. Kept here for when on-device LLM integration matures.
- **Later option C (on-device agent):** run a text LLM (Llama 3.2 / Gemma 3n / Phi-4) in the Linux
  env as a local agent backend — termuxy already has the `termuxy-agent` launcher to extend.

*Sources: alphacephei.com/vosk/models, ai.google.dev/gemma/docs/capabilities/audio, mvnrepository
vosk-android 0.3.70 (Dec 2025), Google AI Edge LLM Inference docs, LocalLLaMA/mobile benchmarks 2026.*
