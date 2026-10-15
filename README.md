# Forge IDE

Forge is my Cursor-style AI coding IDE for the Lenovo Googlebook 15. It runs a local Llama 3.1 model through Ollama in the Linux Terminal, and it can run the same model on the laptop's Intel graphics through a GPU engine built into its own Android app. A small vision model works together with Llama, so I can paste screenshots into the chat. The editor, the agent, the terminal and the models all live on the laptop. Nothing I type leaves it.

I built it because I wanted to work with my local model the way I work in Cursor, with an editor, an agent that edits files and runs commands for me, a visible thinking process and diffs I can review, instead of typing into a terminal.

## What it does

- **Agent mode**: the model reads, creates and edits files, searches the codebase, runs commands, reads the results and keeps going until the task is done. Every command it wants to run is shown to me first with Run, Always allow and Skip. Auto-run skips the prompt for safe commands; destructive commands and anything that moves files to the trash always ask. An allowlist and a denylist of commands fine-tune that.
- **Ask mode** answers questions about the code with files, selections and images attached. **Plan mode** investigates with read-only tools and writes a numbered plan before anything changes. **Custom modes** add my own modes with their own instructions and tool access.
- **Chats like in Cursor**: several chats side by side in tabs, each working on its own, a model menu per chat, messages I type while the agent works wait in a queue, the agent keeps a live to-do list, Restore checkpoint on any of my earlier messages undoes everything from there, slash commands (built in and my own), export to Markdown, and voice input (Android's speech recognizer in the app, whisper.cpp in a browser).
- **Context**: @files, @folders, @codebase, @docs (documentation sites I index), @chat (earlier chats), @web, @git, @terminal, @linter errors and @notepad, plus a paperclip button that attaches files of any kind, including PDFs.
- **Code intelligence**: language servers for Python, JavaScript, TypeScript, shell, JSON, CSS, HTML, XML, C or C++, Kotlin, Java and Rust give suggestions, hover information, go to definition, references, rename, formatting and live errors in a Problems panel. Kotlin uses JetBrains' Kotlin language server with its data sharing set to none.
- **Android development**: the Android SDK (platforms up to 36.1, build-tools 37, NDK 29), Kotlin 2.4, Gradle 9.8, bundletool, Java 21 and Rust are ready in the terminal and for the agent. An Android view builds, installs and runs the app on the device I choose, shows a live logcat, and sends a screenshot of the running app to the chat so the vision model can look at it for Llama.
- **Virtual Android devices without an emulator**: real Android 11 to 17 runs in containers inside the Linux VM, as a phone, small phone, foldable, tablet, round or square watch-sized screen, TV or desktop window. Android 16 starts in about 15 seconds and Android 17 in about 25. A floating device window shows the live screen, takes taps, swipes and typing, and switches the screen shape, dark theme, text size and rotation live. Phones, tablets, watches and TVs can also be paired over Wi-Fi, and `forge-device` gives the agent all of it, so it can test an app on several screen shapes by itself.
- **Watching the models work together**: a bar in the chat shows what Llama 3.1 and the vision model are doing at every moment, each conversation between them appears as a card with the vision model's answer streaming in, and a timeline shows every step, including Llama's GPU engine pausing so the vision model can use the Intel graphics.
- **Review in the editor**: the agent's changes are highlighted inline with Keep and Undo for each change, plus Keep all and Undo all, like in Cursor.
- **A debugger for Python, C, C++, Rust, Kotlin and Java, and for Android apps on a device**, with breakpoints, stepping, variables, watch expressions, the call stack and a Debug console (debugpy for Python, GDB for C, C++ and Rust, and the debugger built into JetBrains' Kotlin language server for Kotlin, Java and Android apps, which it reaches through adb), and **Jupyter notebooks** in Python, Kotlin or Bash (any installed Jupyter kernel), rich output (tables, images, errors with Fix with AI) and a memory limit per kernel.
- **A meaning-based codebase index**: a small embedding model (nomic-embed-text) in its own server lets codebase search find code by what it does, blended with keyword ranking. It indexes in the background, pauses while a chat runs and stops when idle.
- **Memories** the agent offers to save (I approve and can edit each one), **hooks** that run my scripts before prompts, before commands, after edits and at the end of a reply, and Cursor's **rules folder** (`.cursor/rules` with always, glob and on-request rules).
- **Editor features**: a split editor, a Markdown preview, an image viewer, search and replace across files (undoable), and several folders in one window.
- **Screenshots and images**: paste a screenshot with Ctrl V, drop an image on the chat, or use the Image button. Llama 3.1 reads only text, so a vision model (Qwen3-VL) looks at the image for it, and text recognition (tesseract) reads every word in it. Llama gets both, and it has a `look_at_image` tool to ask the vision model its own follow-up questions about any image, as often as it needs. A "What the vision model sees" panel under each image shows exactly what Llama was told.
- **Projects like in Cursor**: every window has its own folder, and I can open several windows at once. Chats, AI Changes and folder settings belong to their folder. The Chats and AI Changes views show the current folder, with a switch to see all folders.
- **Work anywhere on the computer**: relative paths stay in the project, and the agent may read and change files anywhere with absolute or ~ paths. External-file protection (like Cursor's) asks before it changes a file outside the project; system files and keys always ask.
- **Thinking process**: the model reasons in a visible, collapsible block before each step. **Verbose mode** shows the raw requests, tool calls, context trimming and timing.
- **Checkpoints and diffs**: every file the agent changes is snapshotted first and saved to disk, so it survives restarts. A click shows the before and after side by side with Keep and Revert, Revert all under a reply undoes everything that reply changed, and the AI Changes view lists every run with the request that caused it.
- **Ctrl K inline edits** with diff review, **Ctrl L** to add a selection to the chat, Tab completions on demand, and code block actions (Copy, Insert, Apply with a diff).
- **Automatic verification**: after every save and agent edit the server syntax-checks Python, JavaScript, JSON and shell files and feeds the result back to the model, so it fixes its own mistakes. Failures appear in a Problems panel with Fix with AI.
- **Settings in two scopes**: User settings for every folder, and folder overrides saved in `.forge/settings.json`, like `.vscode/settings.json` in Cursor.
- **Rules for the AI**, **notepads**, a **source control view** with model-written commit messages, **terminal Ctrl K** (plain English to a shell command), and **MCP servers** whose tools the agent may call after my approval.
- **Integrated terminal** (a real bash shell on a pseudo terminal, several tabs), file explorer with right-click actions, quick open (also for any path on the computer), command palette, search in files, chat history and settings. The layout is always dark and resizable.
- **The Forge app**: native windows with a launcher icon, one per folder, each labeled with its folder in the taskbar, a waiting screen while the Linux VM boots, a file picker for any kind of file, voice input, and the GPU engine.

## The problem

Ollama runs Llama 3.1 8B fine in the Googlebook's Linux Terminal, but a terminal is a poor place to do real coding work with a model. I wanted the Cursor experience: an editor next to the chat, an agent with tools, safe review of every change, screenshots in the chat, and speed.

Several things stood in the way. The CPU in the Linux VM processes prompts at only 10 to 23 tokens per second, so an agent with a few thousand tokens of context waited minutes before its first word. Llama 3.1 cannot see images. The Terminal app only passes the GPU through to the VM on devices where Google has set a flag, and the Googlebook is not one of them; flipping it needs root. And memory is tight: the laptop has 16 GB for Android, the VM and the models together. Twice while I was building this, loading too much at once made Android kill the Terminal app, and with it the whole VM.

## How I solved it

- **A pure Python server** (standard library only, no pip) in the VM serves the web IDE and runs the agent, the tools, the checkpoints and the terminal. The web UI uses the Monaco editor from VS Code, xterm.js, marked and DOMPurify, downloaded once so everything works offline.
- **A tool protocol that an 8B model can follow.** Instead of Ollama's JSON tool API, the model writes one tool call per step in a tagged block, with long text in sub-blocks so no JSON escaping is needed. The parser repairs the usual small-model mistakes.
- **Speed through the prompt cache.** The system prompt never changes within a chat, conversations are append-only, the project outline given to the model is small, and a new chat is pre-warmed in the background.
- **The GPU engine where the GPU is reachable.** I cross-compiled llama.cpp's Vulkan backend for Android x86-64 with the NDK and packaged `llama-server` into the Forge app as a foreground service. The app downloads the weights once from the IDE (straight out of Ollama's store), and the IDE talks to it through a client with the same interface as the Ollama client.
- **Eyes for Llama.** Qwen3-VL runs in the same llama.cpp engine on the Intel GPU: the 4B model with its full-precision image encoder at a detail level of 1,280 image tokens, which read small status bar text correctly in my tests where lower detail and the 2B model made mistakes. The 2B model is the fallback, then Qwen3-VL 2B through Ollama on the CPU, then text recognition alone. The vision model and Llama take turns on the GPU: Llama's engine pauses while the vision model looks, then resumes.
- **Helpers that cannot hurt the system.** Language servers, the debugger, notebook kernels, the embedding server and speech recognition start only on demand and only when a memory check passes, run at low priority, and stop when unused. Their output is read continuously so nothing ever waits on a full pipe. Every helper with a memory cap (the debugger, notebook kernels, the Kotlin, Java and Rust language servers, and Gradle and Cargo builds) starts only when the Googlebook has the cap plus a 2.3 GB reserve free, so even a helper that grows to its limit cannot push the laptop into trouble. Builds are sized to the memory that is actually free, with Gradle's heap scaled to fit, and they leave no daemon behind. For Kotlin, Forge writes JetBrains' project description itself (a "light" project model), so the Kotlin language server does not have to run a Gradle import next to it. The embedding server has a watchdog that stops it if the Forge server is gone. The embedding model runs in its own small server instead of Ollama, because Ollama keeps one model in memory here and would have to reload Llama after every search.
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

### Virtual devices without hardware virtualization

The Linux VM has no hardware virtualization, so Google's Android emulator cannot run. But the VM's kernel is an Android kernel with binder support, so Android itself can run directly on it in a container, the way Android's cloud test farms run it. Forge uses Podman for that. Android 11 to 16 are the open-source Redroid images. For Android 17, `forge-vd-image 17` downloads LineageOS 24.0 from the WayDroid-ATV project, checks the published SHA-256 sums and packages it as a container image; it draws into an invisible Weston display of its own.

Getting there took some care, and every step was tested on my Googlebook:

- This kernel lacks some nftables pieces that Podman's network tool needs, so Forge's Podman uses its iptables firewall driver, set only for Forge.
- A privileged container shares the VM's kernel, and Android's own power off can signal every process in the VM or crash its kernel through `/proc/sysrq-trigger`. Hiding that file does not work, because Android mounts its own `/proc`, and this kernel cannot run BPF security programs. So Forge never powers Android off from inside, and a small root service, the Forge Android guard, reads the kernel log and ends any Android that starts to shut down by itself. In a live test it ended Android 4 milliseconds after its first shutdown message, before it had stopped a single service.
- Android's start scripts hand some kernel files to Android's system user, which in the VM is my own user, including `/sys/power/state`. Forge gives them back to root after each start and every 10 seconds, keeps the device's screen on so Android never tries to suspend, and drops the container's rights to load SELinux policies or kernel modules, access hardware directly or change the clock.
- Each device runs inside a memory cap, starts only when the Googlebook has the cap plus 2.3 GB free, and has a watchdog that stops it when free memory gets low.
- The Googlebook stays alone on the shared adb server, because my other Googlebook tools use a plain `adb shell`; virtual and paired devices use Forge's own adb server.

## Requirements

- A Lenovo Googlebook 15 (or another GooglebookOS device) with the Linux Terminal app and its Debian VM.
- Ollama installed in the VM with a Llama 3.1 model pulled (`ollama pull llama3.1`).
- Python 3 in the VM (it ships with Debian). No pip packages are needed.
- For screenshots: `sudo apt-get install tesseract-ocr tesseract-ocr-eng`, and the vision model files described under Installation.
- Optional, installed by `install-extras.sh`: language servers (python3-pylsp, clangd, and Node.js servers through npm), the debugger (python3-debugpy), PDF reading (poppler-utils), whisper.cpp v1.9.5 with its base.en model for voice input in a browser, a CPU build of llama.cpp with nomic-embed-text v1.5 for the codebase index, the Android and Kotlin tools (SDK packages, Kotlin language server 263.6379.0, Eclipse JDT language server 1.61.0, LemMinX 0.29.3, Kotlin 2.4.21, Gradle 9.8.1, bundletool 1.18.3), Rust through rustup 1.29.1, and Podman with the Forge Android guard for virtual devices.
- For the Forge app and the GPU engine: the Android SDK build tools in `~/tools/android-sdk` (see the hub's GETTING-STARTED guide), adb over Wireless debugging, and for the engine build the Android NDK r27c, cmake, ninja, g++, glslc, libvulkan-dev and spirv-headers in the VM.

## Installation

1. Clone this repository into the VM, for example into `~/projects/forge-ide`.
2. Run `./install.sh`. It downloads the editor libraries, installs the `forge-ide` command, creates and enables a systemd user service that starts Forge with the VM, builds the `forge-coder` model in Ollama, and starts the server.
3. Optional Ollama tuning: copy `ollama/forge-tuning.conf` to `/etc/systemd/system/ollama.service.d/` and run `sudo systemctl daemon-reload && sudo systemctl restart ollama` while no model is loaded.
4. Vision models: put Qwen3-VL from Qwen's official GGUF repositories on Hugging Face into `~/.forge-ide/models/vision/`: `Qwen3VL-4B-Instruct-Q4_K_M.gguf` and `mmproj-Qwen3VL-4B-Instruct-F16.gguf` (Qwen/Qwen3-VL-4B-Instruct-GGUF), and `Qwen3VL-2B-Instruct-Q4_K_M.gguf` and `mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf` (Qwen/Qwen3-VL-2B-Instruct-GGUF). For the CPU fallback, `ollama pull qwen3-vl:2b`.
5. Optional parts: `./install-extras.sh all`, or one part at a time with `lsp`, `debug`, `docs`, `voice`, `index`, `android`, `rust`, `kernels` (the Kotlin and Bash notebook kernels) or `devices` (Podman for virtual devices and the Forge Android guard). Builds run at low priority with a memory cap, downloads are checked against their published SHA-256 hashes, and Forge simply turns a feature off when its part is missing.
6. Open http://localhost:8765 in Chrome, or build the app: `android/build.sh`, then `adb install -r android/Forge.apk`. The first build creates a local signing key next to the script; keep it private.
7. For the GPU engine: `gpu-engine/stage-headers.sh`, clone llama.cpp and download the NDK into `gpu-engine/`, run `gpu-engine/build-android.sh`, strip the binaries with the NDK's `llvm-strip`, copy `llama-server` to `android/jni/x86_64/libllama-server.so` and rebuild the app. `gpu-engine/smoke-test.sh` runs a bounded test with a small model first.

## Using it

`MANUAL.md` covers everything: windows and folders, the layout, the agent and its modes, chat tabs and the queue, context and attachments, screenshots, voice input, editing and code intelligence, reviewing and undoing, debugging, notebooks, the codebase index, memories, rules, docs and hooks, the GPU engine, memory safety, shortcuts, settings, troubleshooting and updating.

The short version: open the Forge app, open a folder with Ctrl O, tell the agent what to build or paste a screenshot of the problem, approve the commands it proposes, and click the file chips to review its diffs. Start the GPU engine from the engine card in Settings and switch chats to it.

## Updating

Edit the code, then `forge-ide restart`. For the app, `android/build.sh` and `adb install -r android/Forge.apk`. For the model file, edit `model/Modelfile` and run `forge-ide model`.

## Uninstalling

`systemctl --user disable --now forge-ide.service`, remove `~/.config/systemd/user/forge-ide.service` and `~/.local/bin/forge-ide`, uninstall the Forge app from the Googlebook, and remove the repository folder and `~/.forge-ide` if you do not want the sessions, settings, images, indexes and models. The optional parts live in `~/.local/lib/forge-lsp`, `~/.local/lib/forge-voice`, `~/.local/lib/forge-embed` and `~/.local/lib/forge-android`; Rust lives in `~/.cargo` and `~/.rustup` (`rustup self uninstall` removes it). `ollama rm forge-coder qwen3-vl:2b` removes the tuned model and the CPU vision model (the Llama 3.1 weights stay). Virtual devices live in Podman's storage: `sudo podman rm` and `sudo podman volume rm` remove a device and its data, `sudo podman rmi` an Android image, and `sudo systemctl disable --now forge-android-guard` stops the guard.

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
- JavaScript and TypeScript are not debugged yet. While the debugger is attached to an Android app, evaluating expressions may fail (a known limit of JetBrains' Kotlin debugger).
- Virtual devices run phone Android. Wear OS and Samsung's One UI cannot run in a container, so watches are watch-sized screens, and for Wear OS or One UI itself I pair a real watch or phone, or use Samsung's Remote Test Lab. Virtual devices render in software, so games and heavy animations are slow, and a device needs 2.5 to 3 GB of memory, so it does not run next to the Llama model. Kotlin language support for Android projects is still experimental in JetBrains' server.
- A Gradle build needs about 3.8 GB free on the Googlebook and the Kotlin language server about 4.1 GB (4.7 GB in an Android project, and 5.5 GB for an Android project with the full Gradle import), so with many apps open Forge may ask me to free memory first. Kotlin's light project model may miss libraries that Gradle has not downloaded yet; building once, or the full Gradle import, fixes that.
- Indexing a large folder takes a while on the CPU (about one to two pieces of code per second), so a folder is limited to 4,000 pieces, newest files first.
- Not included: the VS Code extension marketplace, cloud models and cloud agents.

## Files in this repository

- `forge/`: the server (`server.py`), the agent loop and stream parser (`agent.py`), the tools and persistent checkpoints (`tools.py`), images and text recognition (`images.py`), the vision manager (`vision.py`), attachments, memories, hooks, slash commands and @docs (`features.py`), the language server and debugger bridge (`lsp.py`), the debugger's build and launch steps (`debugging.py`), the toolchains (`toolchains.py`), the Android view's project and logcat support (`android.py`), devices, the target device and pairing (`devices.py`), virtual devices (`vdevice.py`), Kotlin's light project model (`kotlin_workspace.py`), the meaning-based index (`semantic.py`), notebook kernels (`notebook.py`, `kernel_runner.py`, and `jupyter_runner.py` for Jupyter kernels such as Kotlin and Bash), speech recognition (`voice.py`), the prompts, the Ollama and llama.cpp clients, the keyword codebase search, MCP support, the terminal bridge and a small WebSocket implementation.
- `static/`: the web IDE (`index.html`, `style.css`, `app.js`, `chat.js`, `term.js`, `lsp.js`, `extras.js`, `review.js`, `debug.js`, `notebook.js`, `voice.js`, `preview.js`, `split.js`, `android.js`) and `fetch-vendor.sh`.
- `android/`: the Forge app: windows (`MainActivity`), the GPU engine (`EngineService`), the vision server control (`VisionControl`), the free-memory check (`GpuBudget`), voice input (`Voice`), and its build script.
- `gpu-engine/`: the llama.cpp Android build script, the header staging script and the bounded smoke test.
- `model/Modelfile` and `model/bench.py`, `ollama/forge-tuning.conf`, `systemd/forge-ide.service`, `bin/forge-ide`, `bin/forge-gradle` and `bin/forge-cargo` (memory-checked, memory-capped builds), `bin/forge-device` (devices from the terminal and for the agent), `bin/forge-vd-image` (builds the Android 17 image), `bin/forge-vd-watchdog` (the memory watchdog for a running virtual device), `guard/` (the Forge Android guard service and its installer, plus an experimental BPF version that this kernel cannot load), `jupyter/requirements.txt` (the pinned Kotlin and Bash notebook kernels), `install.sh`, `install-extras.sh`, `MANUAL.md`.

## Related projects

All my Googlebook projects are listed in [googlebook-projects](https://github.com/iamflying29-sketch/googlebook-projects), with the website at https://iamflying29-sketch.github.io/googlebook-projects/ .

## Disclaimer and license

This is a personal project, built and documented with the help of Claude Code, Anthropic's AI coding assistant. It is not affiliated with Google, Lenovo, Meta, Alibaba's Qwen team, Nomic, OpenAI, JetBrains, the Eclipse Foundation, Red Hat, the Rust project, Ollama, Cursor, Microsoft or the llama.cpp and whisper.cpp projects. Use it at your own risk. The code is released under the MIT License (see `LICENSE`). The downloaded editor libraries, the language servers, debugpy, the Android SDK, Kotlin, Gradle, bundletool, Rust, llama.cpp, whisper.cpp, tesseract and the model weights keep their own licenses.
