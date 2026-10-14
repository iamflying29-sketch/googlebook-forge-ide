# Forge IDE

Forge is my Cursor-style AI coding IDE for the Lenovo Googlebook 15. It runs a local Llama 3.1 model through Ollama in the Linux Terminal, and it can run the same model on the laptop's Intel graphics through a GPU engine built into its own Android app. The editor, the agent, the terminal and the model all live on the laptop. Nothing I type leaves it.

I built it because I wanted to work with my local model the way I work in Cursor, with an editor, an agent that edits files and runs commands for me, a visible thinking process, and diffs I can review, instead of typing into a terminal.

## What it does

- **Agent mode**: the model reads, creates and edits files, searches the codebase, runs commands, reads the results and keeps going until the task is done. Every command it wants to run is shown to me first with Run, Always allow and Skip. Auto-run skips the prompt for safe commands; destructive commands and anything that moves files to the trash always ask.
- **Ask mode** answers questions about the code with files and selections attached. **Plan mode** investigates with read-only tools and writes a numbered plan before anything changes.
- **Thinking process**: the model reasons in a visible, collapsible block before each step. **Verbose mode** shows the raw requests, tool calls, context trimming and timing.
- **Checkpoints and diffs**: every file the agent changes is snapshotted first. A click shows the before and after side by side with Keep and Revert, and the AI Changes view can revert a whole run.
- **Ctrl K inline edits** with diff review, **Ctrl L** to add a selection to the chat, **@file**, **@codebase** (ranked search of the whole workspace), **@web**, **@git**, **@terminal** and **@notepad** context, Tab completions on demand, and code block actions (Copy, Insert, Apply with a diff).
- **Automatic verification**: after every save and agent edit the server syntax-checks Python, JavaScript, JSON and shell files and feeds the result back to the model, so it fixes its own mistakes. Failures appear in a Problems panel with Fix with AI.
- **Rules for the AI** (global and per project, including `.cursorrules`, `AGENTS.md` and `CLAUDE.md`), **notepads**, a **source control view** with model-written commit messages, **terminal Ctrl K** (plain English to a shell command), and **MCP servers** whose tools the agent may call after my approval.
- **Integrated terminal** (a real bash shell on a pseudo terminal, several tabs, survives page reloads), file explorer with right-click actions, quick open, command palette, search in files, chat history and settings. The layout is always dark and resizable.
- **The Forge app**: a native window with a launcher icon, no address bar, a waiting screen while the Linux VM boots, and the GPU engine.
- **The GPU engine**: llama.cpp's Vulkan backend, cross-compiled for Android x86-64, running inside the app on the Intel graphics. With Llama 3.1 8B it processes prompts about five to ten times faster than the CPU in the VM, and the VM no longer needs to hold the model at all.

## The problem

Ollama runs Llama 3.1 8B fine in the Googlebook's Linux Terminal, but a terminal is a poor place to do real coding work with a model. I wanted the Cursor experience: an editor next to the chat, an agent with tools, safe review of every change, and speed.

Two things stood in the way. First, this machine processes prompts slowly on the CPU (about 10 to 23 tokens per second), so an agent whose prompts are a few thousand tokens long waited minutes before its first word. Second, the Linux VM has 7.8 GB and the model takes 5.2 GB of it, and the laptop takes memory back from the VM when it needs it. While I was tuning the model, a reload into an already full VM made the laptop kill the whole VM.

I also wanted the Intel graphics to do the work. The Terminal app can pass the GPU through to the VM (gfxstream), but only on devices where Google has set a flag, and the Googlebook is not one of them. Flipping that flag requires root, which this laptop does not have.

## How I solved it

- **A pure Python server** (standard library only, no pip) in the VM serves the web IDE and runs the agent, the tools, the checkpoints and the terminal. The web UI uses the Monaco editor from VS Code, xterm.js, marked and DOMPurify, downloaded once so everything works offline.
- **A tool protocol that an 8B model can follow.** Instead of Ollama's JSON tool API, the model writes one tool call per step in a tagged block, with long text in sub-blocks so no JSON escaping is needed. Generation stops at the closing tag, the server runs the tool and streams the result back. The parser repairs the usual small-model mistakes.
- **Speed through the prompt cache.** The system prompt never changes within a chat, conversations are append-only, the workspace outline given to the model is small, and a new chat is pre-warmed in the background. A follow-up in a warm chat answers within seconds.
- **Memory safety.** The status bar shows the VM's free RAM. The server refuses to load the model when the VM has no room, a watchdog unloads the model before the VM gets into danger, and the model can be unloaded with one command.
- **The GPU engine where the GPU is reachable.** I cross-compiled llama.cpp with the NDK and packaged `llama-server` into the Forge app as a foreground service. The app downloads the weights once from the IDE (straight out of Ollama's store), asks the VM to hand memory back (unload, drop caches, compact free memory so the VM's free page reporting returns it to the laptop), waits until about 6 GB are free, and starts the engine bound to the address only the VM can see, with an API key. The IDE talks to it through a client that presents the same interface as the Ollama client, so the agent does not care which engine answers. If the engine is off, replies fall back to the CPU with a note.
- **A tuned model file**, `forge-coder`, built from the same Llama 3.1 weights with code-friendly sampling, an 8192-token context and an engineer system prompt, plus Ollama service settings (flash attention, an 8-bit KV cache, one request slot, one loaded model).

Measured on my Googlebook with Llama 3.1 8B Q4_K_M:

| Measurement | CPU in the VM (Ollama) | Intel GPU engine |
| --- | --- | --- |
| Prompt processing, cold | 10 to 23 tokens per second | about 100 tokens per second |
| First token, cold 1300-token agent prompt | 134 s | 9 s |
| Generation | 4 to 7 tokens per second | 6 to 7 tokens per second |
| Three-step agent task (edit, run, summarize) | 257 s | 50 s |
| Model memory inside the VM | 5.2 GB | none |

## Requirements

- A Lenovo Googlebook 15 (or another GooglebookOS device) with the Linux Terminal app and its Debian VM.
- Ollama installed in the VM with a Llama 3.1 model pulled (`ollama pull llama3.1`).
- Python 3 in the VM (it ships with Debian). No pip packages are needed.
- For the Forge app and the GPU engine: the Android SDK build tools in `~/tools/android-sdk` (see the hub's GETTING-STARTED guide), adb over Wireless debugging, and for the engine build the Android NDK r27c, cmake, ninja, g++, glslc, libvulkan-dev and spirv-headers in the VM.

## Installation

1. Clone this repository into the VM, for example into `~/projects/forge-ide`.
2. Run `./install.sh`. It downloads the editor libraries, installs the `forge-ide` command, creates and enables a systemd user service that starts Forge with the VM, builds the `forge-coder` model in Ollama, and starts the server.
3. Optional Ollama tuning: copy `ollama/forge-tuning.conf` to `/etc/systemd/system/ollama.service.d/` and run `sudo systemctl daemon-reload && sudo systemctl restart ollama` while no model is loaded.
4. Open http://localhost:8765 in Chrome, or build the app: `android/build.sh`, then `adb install -r android/Forge.apk`. The first build creates a local signing key next to the script; keep it private.
5. For the GPU engine: `gpu-engine/stage-headers.sh`, clone llama.cpp and download the NDK into `gpu-engine/`, run `gpu-engine/build-android.sh`, strip the binaries with the NDK's `llvm-strip`, copy `llama-server` to `android/jni/x86_64/libllama-server.so` and rebuild the app. `gpu-engine/smoke-test.sh` runs a bounded test on the laptop with a small model before you trust it with the big one.

## Using it

The manual in `MANUAL.md` covers everything: starting and stopping, the layout, the agent modes, context sources, inline edits, reviewing and reverting changes, source control, notepads, rules, MCP, the GPU engine, memory safety, shortcuts, settings, troubleshooting and updating.

The short version: open the Forge app, open a folder with Ctrl O, tell the agent what to build, approve the commands it proposes, click the file chips to review its diffs. Switch the engine to the GPU from the engine card in Settings once the engine is running.

## Updating

Edit the code, then `forge-ide restart`. For the app, `android/build.sh` and `adb install -r android/Forge.apk`. For the model file, edit `model/Modelfile` and run `forge-ide model`.

## Uninstalling

`systemctl --user disable --now forge-ide.service`, remove `~/.config/systemd/user/forge-ide.service` and `~/.local/bin/forge-ide`, uninstall the Forge app from the Googlebook, and remove the repository folder and `~/.forge-ide` if you do not want the sessions and settings. `ollama rm forge-coder` removes the tuned model (the Llama 3.1 weights stay).

## Troubleshooting

- The app shows the waiting screen: the VM or the server is not up. Open the Terminal app, wait a few seconds, press Retry, or run `forge-ide status`.
- "Ollama offline" in the status bar: `sudo systemctl restart ollama`.
- "Not enough free memory": close other programs in the VM or use the Unload command in the palette, then try again. In GPU mode, close heavy apps on the laptop and let the engine's waiting step finish.
- The engine shows error or exited: read the engine log on the card, start it again, or switch the engine back to Ollama.
- The agent loops on a malformed tool call: stop it, add detail to the request, or switch Thinking on.

## Safety and privacy notes

- Everything runs locally. The only network calls the agent can make are its web search and web fetch tools, and only when it uses them for a task.
- The server accepts requests only from localhost and the VM's own addresses, and rejects cross-origin requests. The GPU engine is bound to the address the VM uses and protected by a key the IDE generates.
- Commands and deletions need approval unless auto-run is on; even then destructive commands ask. Files the agent removes go to `.forge-trash` inside the workspace.
- Memory: the server will not load the model into a full VM, a watchdog unloads it under pressure, and the GPU engine is the first thing Android reclaims, never the VM.

## Limitations and known issues

- Llama 3.1 8B is an 8-billion-parameter model. The harness makes it far more useful, but it is not a frontier model. A coding model of the same size would run at the same speed and may code better.
- Cold prompt processing on the CPU is slow on this machine, which is why the GPU engine exists. Generation speed is similar on both.
- The GPU is the two-core Xe3 part in this chip. Prompt processing gains are large; generation gains are small.
- The GPU engine needs about 6 GB free on the laptop. While it loads, Android may evict cached background apps once.
- Tab completions are slow on the CPU and off by default.
- Not included: the VS Code extension marketplace, language servers, image input, cloud models.

## Files in this repository

- `forge/`: the server (`server.py`), the agent loop and stream parser (`agent.py`), the tools and checkpoints (`tools.py`), the prompts, the Ollama and llama.cpp clients, the ranked codebase search, MCP support, the terminal bridge and a small WebSocket implementation.
- `static/`: the web IDE (`index.html`, `style.css`, `app.js`, `chat.js`, `term.js`, `extras.js`) and `fetch-vendor.sh`.
- `android/`: the Forge app (window shell, offline screen, GPU engine service) and its build script.
- `gpu-engine/`: the llama.cpp Android build script, the header staging script and the bounded smoke test.
- `model/Modelfile` and `model/bench.py`, `ollama/forge-tuning.conf`, `systemd/forge-ide.service`, `bin/forge-ide`, `install.sh`, `MANUAL.md`.

## Related projects

All my Googlebook projects are listed in [googlebook-projects](https://github.com/iamflying29-sketch/googlebook-projects), with the website at https://iamflying29-sketch.github.io/googlebook-projects/ .

## Disclaimer and license

This is a personal project, built and documented with the help of Claude Code, Anthropic's AI coding assistant. It is not affiliated with Google, Lenovo, Meta, Ollama, Cursor or the llama.cpp project. Use it at your own risk. The code is released under the MIT License (see `LICENSE`). The downloaded editor libraries, llama.cpp and the Llama 3.1 weights keep their own licenses.
