<div align="center">

# SemCode AI

### A full AI coding agent — in your pocket.

**Chat with an agent that reads your files, writes code, runs real shell commands,
and pushes to GitHub — from an Android phone, fully offline if you want.**

[![Build](https://github.com/danielsem65/semcode-ai/actions/workflows/build.yml/badge.svg)](https://github.com/danielsem65/semcode-ai/actions/workflows/build.yml)
[![Platform](https://img.shields.io/badge/platform-Android%207%2B-3ddc84)](https://developer.android.com)
[![Engine](https://img.shields.io/badge/engine-llama.cpp%20%7C%20cloud-blueviolet)](https://github.com/ggml-org/llama.cpp)
[![License](https://img.shields.io/badge/license-MIT-informational)](#license)
[![Version](https://img.shields.io/badge/version-2.5.x-ff69b4)](../../releases)

Kotlin · Jetpack Compose · Material 3 · llama.cpp · Zero telemetry

</div>

---

## ✨ What is this?

SemCode AI is a **self-contained AI software engineer** that lives on your phone.
Not a chat app with a text box — an *agent* with hands:

| | |
|---|---|
| 🗂 **It touches real files** | read, write, exact-match edit, grep, glob, move, delete |
| ⌨️ **It runs real commands** | full Linux shell (toybox + optional proot Ubuntu/Alpine) |
| 🔀 **It loops, plans, verifies** | multi-step tool-calling loop with live progress |
| 🛡 **You stay in control** | approval cards with unified diffs before every risky action |
| 🌐 **Any brain you like** | free cloud models, OpenRouter, Ollama — or **no internet at all** |
| 💾 **Your data stays yours** | zero analytics, keys never leave the device |

```
You: "Build me a to-do REST API"
SemCode: creates project → writes files → installs deps → runs tests
         → fixes what broke → pushes to GitHub
```

---

## 🧠 Bring your own intelligence

| Provider | Cost | Best for |
|---|---|---|
| **OpenCode Zen** | Free tier | Big-name coding models at $0 — key from [opencode.ai/auth](https://opencode.ai/auth) |
| **OpenRouter** | Free / pay | Hundreds of models via one key ([keys](https://openrouter.ai/keys)) |
| **Ollama** | Offline | Models on your own PC over WiFi ([ollama.com](https://ollama.com)) |
| **On-device llama.cpp** | **Fully offline** | A bundled llama.cpp server runs any `.gguf` on the phone itself |

> 📥 **Offline models are hosted for you:** grab ready-to-run `.gguf` files from
> [`danielsem65/semcode-models`](https://github.com/danielsem65/semcode-models/releases)
> (400 MB – 2 GB), pick them in Settings → On-device, and chat on airplane mode.
> Streaming everywhere · one-tap Stop · automatic context compaction for marathon sessions.

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Compose UI                        │
│   Chat · Terminal · Files · Settings · Projects      │
├─────────────────────────────────────────────────────┤
│                 Agent Runtime (Kotlin)               │
│   tool loop · streaming SSE · diff approvals         │
│   compaction · AGENTS.md memory · crash reports      │
├───────────────┬───────────────┬─────────────────────┤
│  ai/          │  fs/          │  core/              │
│  engines      │  FileOps      │  proot Linux env    │
│  Zen/OR/Ollama│  DiffUtil     │  llama.cpp server   │
│  local llama  │  previewDiff  │  ProjectStore       │
├───────────────┴───────────────┴─────────────────────┤
│        Durable storage: /storage/emulated/0/.semcode-ai        │
│        workspace/ · projects/ · models/  (survives uninstalls) │
└─────────────────────────────────────────────────────┘
```

**Highlights**

- **Real terminal, two ways** — native toybox session out of the box; a complete
  rootless **Ubuntu or Alpine environment via proot** (binaries shipped as fake JNI
  libs — the Termux trick) when you need `python`, `git`, `apt`.
- **Safety gate** — "Ask before changes" pauses the agent before edits/deletes/
  commands and shows a real `-/+` diff. Approve or deny inline.
- **Memory** — durable `AGENTS.md` per workspace, injected into every session;
  auto-compaction summarizes old turns past ~60k chars.
- **Survives everything** — foreground-service runs finish in deep background and
  notify you; projects persist across reinstalls.
- **Self-building** — GitHub Actions compiles every APK; even the llama.cpp engine
  is cross-built in CI and committed automatically.

---

## 📲 Install

1. Grab the newest APK from [**Actions → latest green run → Artifacts**](../../actions/workflows/build.yml)
   (`SemCodeAI-debug-apk`)
2. Sideload it (allow "install unknown apps" once)
3. Allow storage access when prompted → your `.semcode-ai` home appears
4. Pick a provider in Settings (Zen is free), paste a key, hit **Test**

<details>
<summary><b>Want the offline engine? (3 extra taps)</b></summary>

1. Download any `.gguf` from the [models releases](https://github.com/danielsem65/semcode-models/releases)
2. Settings → **On-device (offline)** → Browse → pick it → **Load**
3. Set it active — done. Airplane mode is now a lifestyle choice.

</details>

---

## 🗺 Roadmap

- [x] Multi-provider agents · streaming · stop button
- [x] Diff-based approvals · AGENTS.md memory · compaction
- [x] Background runs + notifications
- [x] On-device inference (bundled llama.cpp)
- [x] Uninstall-proof project storage
- [ ] In-app model downloader (GitHub-hosted models)
- [ ] **SemCode PC** — same agent, your desktop
- [ ] Voice input · image context · git-native versioning

---

## 🤝 Repo family

| Repo | Purpose |
|---|---|
| **`semcode-ai`** (this repo) | The Android app |
| [`semcode-models`](https://github.com/danielsem65/semcode-models) | Ready-to-run offline model files + guide |

---

<div align="center">

Built by hand, one feature at a time, with an AI pair programmer that
occasionally debugs itself.

**⭐ Star the repo if your phone now writes code too.**

</div>
