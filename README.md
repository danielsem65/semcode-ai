# SemCode AI

A professional AI coding agent that lives on your Android phone — write code,
run real shell commands, and sync projects to GitHub, all from one app.
Built with Kotlin + Jetpack Compose; GitHub Actions builds every APK.

## Providers (v2)

| Provider | Cost | Notes |
|---|---|---|
| **OpenCode Zen** | FREE models | `big-pickle` & friends — key at opencode.ai/auth |
| **OpenRouter** | free tier | `openrouter/free` router, or any model + `:free` |
| **Ollama (local)** | offline | gemma3 / qwen3 via Ollama on-device or `adb reverse` |

Every provider row has a built-in **Test** button (verifies the key by listing
models) and a live model picker fetched straight from the provider's `/models`.

## Agent capabilities

- **Files**: read / write / exact-match edit / grep (`search_in_files`) /
  wildcard find / copy / move / delete / info
- **Shell**: persistent toybox session shared between you and the AI — it sees
  your `cd`, you see its commands. Watchdog kills hung sessions automatically.
- **GitHub** (pure REST — no JGit): clone via zipball, snapshot-push real
  commits, pull, status, create repo. Token with repo scope in Settings.

## Zero-friction start

The app works immediately in an app-private workspace — no permissions needed.
Enable *Full device storage* in Settings to move the workspace to
`/storage/emulated/0/semcode`.

## Install

Repo → Actions → latest green run → artifact **SemCodeAI-debug-apk** → sideload.

First launch: Settings → pick provider → paste key → Test → Use this provider.
Then ask the AI tab to build something.

## Layout

```
core/    SettingsStore · Workspace · ShellSession
ai/      AiCore (providers) · OpenAiCompatEngine · Tools
fs/      FileOps
github/  GitHubSync (REST)
ui/      Chat (markdown) · Terminal · Files · Settings
```
