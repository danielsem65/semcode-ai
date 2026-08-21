# SemCode AI

An AI coding agent that runs entirely on your Android phone — an OpenCode-style
agent in your pocket. Chat with it to write and refactor code, run real shell
commands, and operate git repos (clone / commit / pull / push). Includes a
show/hide terminal and a manual file browser.

Built with **Kotlin + Jetpack Compose**. GitHub Actions builds the APK — no
Android Studio needed.

## Features

- **Multi-provider AI** — pick a provider and paste your key (stored per-provider):
  | Provider | Notes |
  |---|---|
  | Gemini | free tier, thinking enabled |
  | OpenCode Zen | **free models** incl. `big-pickle` — key at opencode.ai/auth |
  | OpenRouter | `openrouter/free` or any `model:free` = $0 |
  | Groq | free tier |
  | DeepSeek | cheap |
  | Anthropic Claude | native Messages API |
  Model override box lets you use any model ID.
- **Agent loop** — up to 25 tool rounds per message with verification steps:
  `list_files`, `read_file`, `write_file`, `edit_file` (exact-match replace),
  `search_in_files` (grep), `search_files`, folder/file create/copy/move/delete,
  `get_file_info`, `run_command`, plus git tools.
- **Terminal** — persistent toybox shell session (`cd`, env vars survive),
  slides up/down over any screen; the AI uses the *same* session via run_command,
  so it sees your cwd and you see what it did.
- **Git** — JGit under the hood: clone over HTTPS, status, stage, commit, pull,
  push using your GitHub username + personal access token.
- **Files tab** — manual browser with preview/edit helpers.

## Setup (first launch)

1. Install the APK from the latest green **Build APK** workflow artifact.
2. Settings → **AI Provider** → pick one → paste API key → Save key → Use provider.
   Free path: Gemini key (aistudio.google.com/apikey) or OpenCode Zen (opencode.ai/auth).
3. Settings → **Storage access** → grant "All files access".
4. Optional: add GitHub username + token for push/private clones.
5. Projects live in `/storage/emulated/0/semcode/`.

## Getting the APK

Repo → **Actions** → latest *Build APK* run → download **SemCodeAI-debug-apk**
→ sideload.

## Notes

- Personal-use build: uses `MANAGE_EXTERNAL_STORAGE` (not Play-Store compliant — fine, this app is just for yours truly).
- Keys/tokens are stored in app-private SharedPreferences on-device only.
- The device shell has no root, no apt, no python/node/javac out of the box —
  the agent knows its limits and won't fake output.

## Project layout

```
app/src/main/java/com/danielsem65/semcodeai/
├── MainActivity.kt            # tabs + sliding terminal panel
├── SemApp.kt                  # app-scoped shell session + workspace
├── AppViewModel.kt            # agent loop, tool dispatch, terminal state
├── ai/
│   ├── AiCore.kt              # neutral msg model + provider catalog
│   ├── GeminiEngine.kt        # native Gemini REST (thinking budget)
│   ├── OpenAiCompatEngine.kt  # Zen / OpenRouter / Groq / DeepSeek / any v1
│   └── AnthropicEngine.kt     # native Claude Messages API
├── shell/ShellSession.kt      # persistent sh process w/ sentinel IO
├── git/GitOps.kt              # JGit operations
├── fs/FileOps.kt              # filesystem tools
├── data/SettingsStore.kt      # keys, model, git creds
└── ui/                        # Compose screens + theme
```
