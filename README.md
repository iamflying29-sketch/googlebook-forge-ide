# Forge IDE

Forge is my Cursor-style AI coding IDE for the Lenovo Googlebook 15. It runs a local Llama 3.1 model through Ollama in the Linux Terminal, and it can run the same model on the laptop's Intel graphics through a GPU engine built into its own Android app. A small vision model works together with Llama, so I can paste screenshots into the chat. The editor, the agent, the terminal and the models all live on the laptop. Nothing I type leaves it.

I built it because I wanted to work with my local model the way I work in Cursor, with an editor, an agent that edits files and runs commands for me, a visible thinking process and diffs I can review, instead of typing into a terminal.

## What it does

- **Agent mode**: the model reads, creates and edits files, searches the codebase, runs commands, reads the results and keeps going until the task is done. Every command it wants to run is shown to me first with Run, Always allow and Skip. Auto-run skips the prompt for safe commands; destructive commands and anything that moves files to the trash always ask.
- **Ask mode** answers questions about the code with files, selections and images attached. **Plan mode** investigates with read-only tools and writes a numbered plan before anything changes.
- **Screenshots and images**: paste a screenshot with Ctrl V, drop an image on the chat, or use the Image button. Llama 3.1 reads only text, so a vision model (Qwen3-VL) looks at the image for it, and text recognition (tesseract) reads every word in it. Llama gets both, and it has a `look_at_image` tool to ask the vision model its own follow-up questions about any image, as often as it needs. A "What the vision model sees" panel under each image shows exactly what Llama was told.
- **Projects like in Cursor**: every window has its own folder, and I can open several windows at once. Chats, AI Changes and folder settings belong to their folder. The Chats and AI Changes views show the current folder, with a switch to see all folders.
- **Work anywhere on the computer**: relative paths stay in the project, and the agent may read and change files anywhere with absolute or ~ paths. External-file protection (like Cursor's) asks before it changes a file outside the project; system files and keys always ask.
- **Thinking process**: the model reasons in a visible, collapsible block before each step. **Verbose mode** shows the raw requests, tool calls, context trimming and timing.
- **Checkpoints and diffs**: every file the agent changes is snapshotted first and saved to disk, so it survives restarts. A click shows the before and after side by side with Keep and Revert, Revert all under a reply undoes everything that reply changed, and the AI Changes view lists every run with the request that caused it.
- **Ctrl K inline edits** with diff review, **Ctrl L** to add a selection to the chat, **@file**, **@codebase** (ranked search of the whole project), **@web**, **@git**, **@terminal** and **@notepad** context, Tab completions on demand, and code block actions (Copy, Insert, Apply with a diff).
- **Automatic verification**: after every save and agent edit the server syntax-checks Python, JavaScript, JSON and shell files and feeds the result back to the model, so it fixes its own mistakes. Failures appear in a Problems panel with Fix with AI.
- **Settings in two scopes**: User settings for every folder, and folder overrides saved in `.forge/settings.json`, like `.vscode/settings.json` in Cursor.
- **Rules for the AI**, **notepads**, a **source control view** with model-written commit messages, **terminal Ctrl K** (plain English to a shell command), and **MCP servers** whose tools the agent may call after my approval.
- **Integrated terminal** (a real bash shell on a pseudo terminal, several tabs), file explorer with right-click actions, quick open (also for any path on the computer), command palette, search in files, chat history and settings. The layout is always dark and resizable.
- **The Forge app**: native windows with a launcher icon, one per folder, each labeled with its folder in the taskbar, a waiting screen while the Linux VM boots, the image picker, and the GPU engine.

## The problem

Ollama runs Llama 3.1 8B fine in the Googlebook's Linux Terminal, but a terminal is a poor place to do real coding work with a model. I wanted the Cursor experience: an editor next to the chat, an agent with tools, safe review of every change, screenshots in the chat, and speed.

Several things stood in the way. The CPU in the Linux VM processes prompts at only 10 to 23 tokens per second, so an agent with a few thousand tokens of context waited minutes before its first word. Llama 3.1 cannot see images. The Terminal app only passes the GPU through to the VM on devices where Google has set a flag, and the Googlebook is not one of them; flipping it needs root. And memory is tight: the laptop has 16 GB for Android, the VM and the models together. Twice while I was building this, loading too much at once made Android kill the Terminal app, and with it the whole VM.

## How I solved it

- **A pure Python server** (standard library only, no pip) in the VM serves the web IDE and runs the agent, the tools, the checkpoints and the terminal. The web UI uses the Monaco editor from VS Code, xterm.js, marked and DOMPurify, downloaded once so everything works offline.
- **A tool protocol that an 8B model can follow.** Instead of Ollama's JSON tool API, the model writes one tool call per step in a tagged block, with long text in sub-blocks so no JSON escaping is needed. The parser repairs the usual small-model mistakes.
- **Speed through the prompt cache.** The system prompt never changes within a chat, conversations are append-only, the project outline given to the model is small, and a new chat is pre-warmed in the background.
- **The GPU engine where the GPU is reachable.** I cross-compiled llama.cpp's Vulkan backend for Android x86-64 with the NDK and packaged `llama-server` into the Forge app as a foreground service. The app downloads the weights once from the IDE (straight out of Ollama's store), and the IDE talks to it through a client with the same interface as the Ollama client.
- **Eyes for Llama.** Qwen3-VL runs in the same llama.cpp engine on the Intel GPU: the 4B model with its full-precision image encoder at a detail level of 1,280 image tokens, which read small status bar text correctly in my tests where lower detail and the 2B model made mistakes. The 2B model is the fallback, then Qwen3-VL 2B through Ollama on the CPU, then text recognition alone. The vision model and Llama take turns on the GPU: Llama's engine pauses while the vision model looks, then resumes.
- **A free-memory check that is never bypassed.** Every GPU load must leave at least 2.5 GB free on the Googlebook, which is where Llama's engine ran stably for hours. When a GPU process stops, the graphics driver keeps its memory for reuse and Android does not count it as free, so the check also counts the free memory measured before Forge's GPU session began, but only while no Forge GPU process runs and only within 15 minutes, and it refuses whenever Android reports low memory. There is no "start anyway", and Forge never loads the 5 GB CPU model as an automatic fallback when the GPU engine stops. The status bar shows the free memory of both the VM and the Googlebook.

Measured on my Googlebook with Llama 3.1 8B Q4_K_M:

| Measurement | CPU in the VM (Ollama) | Intel GPU engine |
| --- | --- | --- |
| Prompt processing, cold | 10 to 23 tokens per second | about 100 tokens per second |
| First token, cold 1300-token agent prompt | 134 s | 9 s |
| Generation | 4 to 7 tokens per second | 6 to 7 tokens per second |
| Three-step agent task (edit, run, summarize) | 257 s | 50 s |
| Model memory inside the VM | 5.2 GB | none |

Reading a full-screen screenshot with Qwen3-VL 4B on the Intel GPU took 51 seconds once the model files were in place (92 seconds the first time, including copying them into the app), and the lowest free memory during that test was 2.5 GB.

## Requirements

- A Lenovo Googlebook 15 (or another GooglebookOS device) with the Linux Terminal app and its Debian VM.
- Ollama installed in the VM with a Llama 3.1 model pulled (`ollama pull llama3.1`).
- Python 3 in the VM (it ships with Debian). No pip packages are needed.
- For screenshots: `sudo apt-get install tesseract-ocr tesseract-ocr-eng`, and the vision model files described under Installation.
- For the Forge app and the GPU engine: the Android SDK build tools in `~/tools/android-sdk` (see the hub's GETTING-STARTED guide), adb over Wireless debugging, and for the engine build the Android NDK r27c, cmake, ninja, g++, glslc, libvulkan-dev and spirv-headers in the VM.

## Installation

1. Clone this repository into the VM, for example into `~/projects/forge-ide`.
2. Run `./install.sh`. It downloads the editor libraries, installs the `forge-ide` command, creates and enables a systemd user service that starts Forge with the VM, builds the `forge-coder` model in Ollama, and starts the server.
3. Optional Ollama tuning: copy `ollama/forge-tuning.conf` to `/etc/systemd/system/ollama.service.d/` and run `sudo systemctl daemon-reload && sudo systemctl restart ollama` while no model is loaded.
4. Vision models: put Qwen3-VL from Qwen's official GGUF repositories on Hugging Face into `~/.forge-ide/models/vision/`: `Qwen3VL-4B-Instruct-Q4_K_M.gguf` and `mmproj-Qwen3VL-4B-Instruct-F16.gguf` (Qwen/Qwen3-VL-4B-Instruct-GGUF), and `Qwen3VL-2B-Instruct-Q4_K_M.gguf` and `mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf` (Qwen/Qwen3-VL-2B-Instruct-GGUF). For the CPU fallback, `ollama pull qwen3-vl:2b`.
5. Open http://localhost:8765 in Chrome, or build the app: `android/build.sh`, then `adb install -r android/Forge.apk`. The first build creates a local signing key next to the script; keep it private.
6. For the GPU engine: `gpu-engine/stage-headers.sh`, clone llama.cpp and download the NDK into `gpu-engine/`, run `gpu-engine/build-android.sh`, strip the binaries with the NDK's `llvm-strip`, copy `llama-server` to `android/jni/x86_64/libllama-server.so` and rebuild the app. `gpu-engine/smoke-test.sh` runs a bounded test with a small model first.

## Using it

`MANUAL.md` covers everything: windows and folders, the layout, the agent modes, screenshots, context sources, inline edits, reviewing and reverting changes, source control, notepads, rules, MCP, the GPU engine, memory safety, shortcuts, settings, troubleshooting and updating.

The short version: open the Forge app, open a folder with Ctrl O, tell the agent what to build or paste a screenshot of the problem, approve the commands it proposes, and click the file chips to review its diffs. Start the GPU engine from the engine card in Settings and switch chats to it.

## Updating

Edit the code, then `forge-ide restart`. For the app, `android/build.sh` and `adb install -r android/Forge.apk`. For the model file, edit `model/Modelfile` and run `forge-ide model`.

## Uninstalling

`systemctl --user disable --now forge-ide.service`, remove `~/.config/systemd/user/forge-ide.service` and `~/.local/bin/forge-ide`, uninstall the Forge app from the Googlebook, and remove the repository folder and `~/.forge-ide` if you do not want the sessions, settings, images and vision models. `ollama rm forge-coder qwen3-vl:2b` removes the tuned model and the CPU vision model (the Llama 3.1 weights stay).

## Troubleshooting

- The app shows the waiting screen: the VM or the server is not up. Open the Terminal app, wait a few seconds, press Retry, or run `forge-ide status`.
- The GPU engine waits for free memory: it needs about 5.9 GB free on the Googlebook. Close heavy apps. After GPU work the graphics driver can hold memory that Android does not count as free; restarting the Googlebook clears it.
- A screenshot gets only text recognition: the vision model was refused by the free-memory check, or the Forge app is not running. The panel under the image says why.
- "The GPU engine is not running" in a chat: start it from the engine card, or switch the engine to Ollama in Settings.
- The agent loops on a malformed tool call: stop it, add detail to the request, or switch Thinking on.

## Safety and privacy notes

- Everything runs locally. The only network calls the agent can make are its web search and web fetch tools, and only when it uses them for a task.
- The server accepts requests only from localhost and the VM's own addresses, rejects cross-origin requests, and rejects WebSocket connections from other web pages, so a website open in Chrome cannot reach the terminal. The GPU engine and the vision server listen only on the address the VM uses and require a key the IDE generates.
- Commands and deletions need approval unless auto-run is on; even then destructive commands ask. Files the agent removes go to a trash folder, never deleted outright. Changes outside the project ask first unless you turn External-file protection off; system files and keys always ask.
- Memory: see "A free-memory check that is never bypassed" above. Android's memory killer chooses whole apps, so if the Googlebook ran out of memory it could close the Terminal app and with it the VM; the check exists to keep that from happening.

## Limitations and known issues

- Llama 3.1 8B is an 8-billion-parameter model. The harness makes it far more useful, but it is not a frontier model.
- The GPU engine needs about 5.9 GB free on the Googlebook to start, and the vision model about 3.6 GB. When memory is tight Forge waits or uses text recognition only instead of risking the VM.
- A screenshot look takes about a minute on the GPU, and Llama's engine pauses meanwhile, so it needs about 15 seconds to resume afterwards.
- Cold prompt processing on the CPU is slow on this machine, which is why the GPU engine exists. Generation speed is similar on both.
- Tab completions are slow on the CPU and off by default.
- Not included: the VS Code extension marketplace, language servers, a debugger, cloud models and cloud agents.

## Files in this repository

- `forge/`: the server (`server.py`), the agent loop and stream parser (`agent.py`), the tools and persistent checkpoints (`tools.py`), images and text recognition (`images.py`), the vision manager (`vision.py`), the prompts, the Ollama and llama.cpp clients, the ranked codebase search, MCP support, the terminal bridge and a small WebSocket implementation.
- `static/`: the web IDE (`index.html`, `style.css`, `app.js`, `chat.js`, `term.js`, `extras.js`) and `fetch-vendor.sh`.
- `android/`: the Forge app: windows (`MainActivity`), the GPU engine (`EngineService`), the vision server control (`VisionControl`), the free-memory check (`GpuBudget`), and its build script.
- `gpu-engine/`: the llama.cpp Android build script, the header staging script and the bounded smoke test.
- `model/Modelfile` and `model/bench.py`, `ollama/forge-tuning.conf`, `systemd/forge-ide.service`, `bin/forge-ide`, `install.sh`, `MANUAL.md`.

## Related projects

All my Googlebook projects are listed in [googlebook-projects](https://github.com/iamflying29-sketch/googlebook-projects), with the website at https://iamflying29-sketch.github.io/googlebook-projects/ .

## Disclaimer and license

This is a personal project, built and documented with the help of Claude Code, Anthropic's AI coding assistant. It is not affiliated with Google, Lenovo, Meta, Alibaba's Qwen team, Ollama, Cursor or the llama.cpp project. Use it at your own risk. The code is released under the MIT License (see `LICENSE`). The downloaded editor libraries, llama.cpp, tesseract and the model weights keep their own licenses.
