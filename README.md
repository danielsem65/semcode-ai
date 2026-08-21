# SemCode AI

A personal AI file manager for Android. Talk to it in plain language and it can
create, read, write, copy, move, rename, search and delete any file on your
device — powered by the free Gemini API.

Built with **Kotlin + Jetpack Compose**. No Android Studio needed: GitHub
Actions builds the APK for you.

## Features

- **AI tab** — chat interface. The model gets real file tools (function calling)
  and executes them directly on your storage:
  `list_files`, `read_file`, `write_file`, `create_folder`, `delete_path`,
  `copy_path`, `move_path`, `search_files`, `get_file_info`
- **Files tab** — manual browser: browse, preview text files, new file/folder,
  copy / cut / paste, rename, delete, info.
- **Settings tab** — save your Gemini API key, grant "All files access".

## Setup (first launch)

1. Install the APK (see *Getting the APK* below).
2. Open the app → **Settings** → paste your free API key from
   <https://aistudio.google.com/apikey> → Save.
3. Still in Settings → **Grant access** → allow "All files access".
4. Go to the **AI** tab and try: *"List everything in Download"*.

## Getting the APK

1. Push this repo to GitHub (`main` branch).
2. Open the repo → **Actions** → wait for the *Build APK* workflow to finish.
3. Download the artifact **SemCodeAI-debug-apk**, unzip, sideload the APK
   (enable "Install unknown apps" for your browser/file manager if asked).

## Building locally (optional)

Requires JDK 17 + Gradle 8.10+ + Android SDK:

```bash
gradle assembleDebug
```

## Notes

- Personal-use build: uses `MANAGE_EXTERNAL_STORAGE` (not Play-Store compliant,
  which is fine — this app is just for you).
- The API key is stored in app-private SharedPreferences.
- The AI is instructed to confirm destructive actions in its replies before
  executing them in the same turn; double-check paths when deleting.

## Project layout

```
app/src/main/java/com/danielsem65/semcodeai/
├── MainActivity.kt          # tabs: AI / Files / Settings
├── AppViewModel.kt          # chat state + agentic tool loop
├── ai/GeminiService.kt      # Gemini REST client + tool declarations
├── fs/FileOps.kt            # all real filesystem operations
├── data/SettingsStore.kt    # API key storage
└── ui/                      # Compose screens + theme
```
