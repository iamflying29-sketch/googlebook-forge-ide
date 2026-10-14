# Forge IDE Manual

Forge is a Cursor-style AI coding IDE that runs entirely on your Lenovo Googlebook 15. The editor, the agent, the terminal and the model all live on the laptop, and nothing you type leaves it. This manual explains every part of it: how to start it, how to work with the agent, how the GPU engine works, what the safety features do, and what to do when something goes wrong.

## 1. The pieces, in one picture

- **Forge IDE server**: a Python program running in the Linux Terminal VM. It serves the editor and runs the agent, the tools and the terminal. It starts automatically with the VM.
- **Forge app**: the window you open from the taskbar. It is the desktop shell around the IDE, like Cursor is a shell around a web app. It also hosts the GPU engine.
- **Ollama with forge-coder**: your Llama 3.1 8B model, tuned for code, running on the CPU inside the VM. This is the fallback engine.
- **Forge GPU engine**: the same Llama 3.1 weights running on the Intel graphics through llama.cpp's Vulkan backend, inside the Forge app on the Android side of the laptop. This is the fast engine.

## 2. Starting and stopping

1. Open the **Terminal** app once after a reboot so the Linux VM starts. It can stay in the background.
2. Click the **Forge** icon. If the VM is still booting you see a waiting screen that retries by itself and opens the IDE as soon as the server answers.
3. You can also open Forge in Chrome at http://localhost:8765.

From the Linux Terminal:

```
forge-ide status      shows whether the server runs, and the URL
forge-ide start       starts it
forge-ide stop        stops it
forge-ide restart     restarts it (do this after editing its code)
forge-ide log         shows the last log lines
forge-ide open DIR    switches the workspace to a folder
```

## 3. The layout

- **Title bar**: the workspace name (click it to open another folder), the search box (Ctrl P), the model menu, and buttons for the sidebar, the terminal and the AI panel.
- **Activity bar** (left edge): Explorer, Search, AI Changes, Source control, Notepads, Chats, Settings.
- **Editor**: the Monaco editor from VS Code, with tabs, breadcrumbs, a minimap, bracket colors and sticky scroll. Ctrl S saves.
- **Bottom panel**: Terminal (a real bash shell, several tabs), Agent log, Problems.
- **AI panel** (right): the chat with the agent. Drag the thin gutters to resize everything; the layout is remembered.
- **Status bar**: Ollama or GPU engine state, the free RAM of the VM, speed in tokens per second, context use, cursor position, Tab completion state.

## 4. Working with the agent

Type in the box at the bottom right and press Enter (Shift Enter makes a new line). Three modes:

- **Agent**: the model reads files, searches, edits, runs commands and keeps going until the task is done. Every command it wants to run is shown first with **Run**, **Always allow this chat** and **Skip**. Edits are applied directly, snapshotted first, and shown as file chips you can click to see the diff.
- **Ask**: a plain answer, no edits. Attach files or selections for context.
- **Plan**: the model investigates with read-only tools and writes a numbered plan you can approve before anything changes.

Useful habits:

- **Thinking**: the Thinking chip shows the model's reasoning before each step as a collapsible "Thought process" block. Turn it off for shorter replies.
- **Auto-run**: lets the agent run commands without asking. Destructive commands and anything that moves files to the trash still ask.
- **Context**: type **@** to attach a file, **@codebase** (ranked search of your whole workspace for this message), **@web** (web search results), **@git** (your uncommitted changes), **@terminal** (recent terminal output) or **@notepad** (a saved note). Ctrl L adds the current selection or file.
- **Stop**: the red square or Esc stops a run. "Continue" in the box resumes after the step limit.
- **Code blocks** in replies have Copy, Insert at cursor and Apply to file (with a diff review).
- **Verbose mode** (the V button) shows the raw requests, tool calls, context trimming and timing inline and in the Agent log.

## 5. Editing with AI

- **Ctrl K** on a selection: describe the change, review the diff, accept with Ctrl Enter or reject with Esc.
- **Tab completions**: off by default because they are slow on a CPU. Alt \ asks for one completion on demand; the Tab chip in the status bar turns them on for every pause in typing.
- **Right-click** in the editor: Add selection to chat, Edit with AI, Explain selection, Fix problems in selection.

## 6. Reviewing and undoing what the agent did

- Click a **file chip** under a reply to see the before and after side by side, then **Keep** or **Revert**.
- The **AI Changes** view lists every change by run with **Revert all**. Files the agent removes go to `.forge-trash` inside the workspace; nothing is deleted outright.
- Every save and every agent edit runs a syntax check (Python, JavaScript, JSON, shell). Failures appear in the **Problems** panel with **Open** and **Fix with AI**.

## 7. Source control, notepads, rules

- **Source control** view: branch, changed files, stage, unstage, discard (a copy is kept in AI Changes), diff against HEAD, **Generate message** (the model writes the commit message from the diff), Commit, Pull, Push. Initialize a repository if the folder has none.
- **Notepads**: reusable notes (conventions, API facts, plans) you attach to any chat with @notepad.
- **Rules for the AI** (Settings): global rules for every workspace, and project rules kept in `.forge/rules.md`. A `.cursorrules`, `AGENTS.md` or `CLAUDE.md` file in the workspace is read too. They are added to every prompt.
- **MCP servers** (Settings): add stdio MCP servers in the configuration box; their tools appear to the agent as `mcp_<server>_<tool>` and run only after your approval.
- **Terminal Ctrl K**: describe a command in plain English; the model's command is pasted into the terminal for you to run.

## 8. The GPU engine

The Linux VM cannot use the Intel graphics, so the fast engine runs on the Android side inside the Forge app.

**Start it**: Forge app, Settings, the card "GPU engine (Intel graphics, via the Forge app)", **Start engine**. The first start downloads the model weights from Ollama's store once (4.9 GB, about a minute). Then the app asks the VM to hand memory back, waits until about 6 GB are free on the Googlebook, loads the model on the GPU and reports "running". A notification with a **Stop engine** button stays visible while it runs.

**Use it**: click **Use GPU engine for chats** on the card, or set Settings, Engine to "gpu". Switching engines starts a fresh chat context. If the engine is not running when you send a message, the reply comes from Ollama on the CPU with a note.

**Stop it**: the notification's Stop engine button, or Stop engine on the card. This frees about 5 GB on the Googlebook.

What to expect with Llama 3.1 8B: prompt processing about 100 tokens per second on the GPU against 10 to 23 on the CPU, generation 6 to 7 tokens per second against 4 to 7, and a typical three-step agent task in under a minute instead of four minutes.

## 9. Memory safety, and why it matters

The VM has 7.8 GB and the model needs 5.2 GB of it when it runs on the CPU. The laptop has 16 GB in total and takes memory back from the VM when it needs it. Forge protects you in these ways:

- The status bar shows the VM's free RAM, and "host took X GB" when the laptop has reclaimed memory from the VM.
- The server refuses to load the CPU model when the VM has less than about 5.8 GB free, and says what to close.
- A watchdog checks every three seconds; under 320 MB free it stops the run and unloads the model before the VM can get into trouble.
- In GPU mode the VM never holds the model, and the server keeps the VM small so the laptop has room for the engine.
- The engine is marked as the first thing Android reclaims under memory pressure. Worst case, the engine stops and chats fall back to the CPU. While the engine loads, Android may evict cached background apps once; they restart on their own when needed.
- The command palette has **Unload the model from memory** to free 5 GB in the VM at any time.

Do not run Waydroid, a second model or a large build at the same time as the model.

## 10. Keyboard shortcuts

| Keys | Action |
| --- | --- |
| Ctrl P | Quick open a file |
| Ctrl Shift P | Command palette |
| Ctrl L | Add selection or file to the chat |
| Ctrl K | Edit the selection with AI (in the terminal: ask for a command) |
| Ctrl I | Toggle the AI panel |
| Ctrl Enter | Send the message |
| Esc | Stop the agent, close the palette or a dialog |
| Ctrl ` | Toggle the terminal |
| Ctrl B | Toggle the sidebar |
| Ctrl S | Save |
| Ctrl W | Close the file |
| Ctrl N | New chat |
| Ctrl O | Open a folder |
| Ctrl Shift F | Search in files |
| Ctrl Shift G | AI Changes |
| Ctrl Shift H | Chat history |
| Alt \ | Ask for a completion |
| Ctrl R (in the app) | Reload the IDE |

## 11. Settings worth knowing

- **Engine**: ollama (CPU in the VM) or gpu.
- **Model**: any model Ollama serves. forge-coder is the tuned Llama 3.1. If it is missing, Forge falls back to llama3.1.
- **Context window**: 8192 is the sweet spot on this machine. Bigger means slower first replies and more RAM.
- **Keep model loaded**: how long Ollama keeps the model in memory after the last message (20m by default).
- **Tool calling**: "prompt" is Forge's tagged format, which small models follow reliably; "native" uses Ollama's JSON tool API.
- **Max tool steps**: how many tool calls one message may trigger before Forge pauses.
- **Pre-warm**: processes the system prompt in the background when you open a new chat so the first reply is fast.

## 12. Where things are

- Code: `~/projects/forge-ide` (server in `forge/`, web UI in `static/`, app in `android/`, model file in `model/`).
- Data: `~/.forge-ide/` (config.json, sessions, notepads, rules, mcp.json).
- The app and this manual: Documents/Forge on the Googlebook.
- Engine build: `~/tools/gpu-build` (NDK, llama.cpp, build-android.sh, smoke-test.sh).
- Ollama tuning: `/etc/systemd/system/ollama.service.d/forge-tuning.conf`.

## 13. Troubleshooting

- **The app shows the waiting screen**: the VM or the server is not up. Open the Terminal app, wait a few seconds, press Retry. From the Terminal, `forge-ide status`.
- **"Ollama offline" in the status bar**: `systemctl status ollama` in the Terminal; `sudo systemctl restart ollama` if it is stopped.
- **First reply is slow**: the model is loading or a cold prompt is being processed. On the CPU a brand new chat needs about a minute before the first token; on the GPU about ten seconds. Follow-ups in the same chat are fast.
- **"Not enough free memory"**: close other programs in the VM, or use the Unload command, then try again. In GPU mode, close heavy apps on the laptop and let the engine's "Waiting for free memory" step finish.
- **The engine says error or exited**: open the Engine log on the card. Start it again; if it keeps failing, switch Settings, Engine back to ollama.
- **A chat shows an error about the context**: use "Reset conversation context" in the command palette; the transcript stays.
- **The agent cannot parse its own tool call**: it retries by itself. If it loops, stop it, add detail to your request or switch Thinking on.
- **Something the agent changed is wrong**: AI Changes, Revert.

## 14. Updating Forge

Edit the code in `~/projects/forge-ide`, then `forge-ide restart`. For the app, run `android/build.sh` and `adb install -r android/Forge.apk`. For the model file, edit `model/Modelfile` and run `forge-ide model`.
