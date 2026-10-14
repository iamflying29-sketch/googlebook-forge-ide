# Forge IDE Manual

Forge is a Cursor-style AI coding IDE that runs entirely on your Lenovo Googlebook 15. The editor, the agent, the terminal and the models all live on the laptop, and nothing you type leaves it. This manual explains every part of it.

## 1. The pieces

- **Forge IDE server**: a Python program in the Linux Terminal VM. It serves the editor and runs the agent, the tools and the terminal. It starts automatically with the VM.
- **Forge app**: the windows you open from the taskbar. It also hosts the GPU engine and the vision model.
- **Ollama with forge-coder**: your Llama 3.1 8B model, tuned for code, running on the CPU inside the VM.
- **Forge GPU engine**: the same Llama 3.1 weights on the Intel graphics, through llama.cpp's Vulkan backend, inside the Forge app.
- **The vision model**: Qwen3-VL, which looks at screenshots for Llama. It runs on the Intel graphics in the Forge app (4B, or 2B when memory is tighter), or on the CPU in the VM as a fallback. Text recognition (tesseract) reads the words in every image.

## 2. Starting and stopping

1. Open the **Terminal** app once after a restart so the Linux VM starts. It can stay in the background.
2. Click the **Forge** icon. If the VM is still booting you see a waiting screen that retries by itself.
3. Chrome works too, at http://localhost:8765.

From the Linux Terminal:

```
forge-ide status      shows whether the server runs, and the URL
forge-ide start       starts it
forge-ide stop        stops it
forge-ide restart     restarts it (do this after editing its code)
forge-ide log         shows the last log lines
forge-ide open DIR    makes DIR the folder for new windows
```

## 3. Windows and folders

Every Forge window has its own folder, like a window in Cursor. The folder name is shown in the title bar and in the taskbar.

- **Open folder** (Ctrl O, or click the folder name) switches this window to another folder. **New window** (Ctrl Shift N, or the button next to the folder name) opens another window, and the Open folder dialog has a New window button and a ↗ next to every recent folder.
- Each folder keeps its own chats, AI Changes, open files, explorer state, project rules and folder settings. The terminal of a window starts in its folder.
- A chat always works in the folder it was started in. Opening a chat from another folder switches the window to that folder.

## 4. The layout

- **Title bar**: folder name, new window, search (Ctrl P), model menu, and buttons for the sidebar, the terminal and the AI panel.
- **Activity bar**: Explorer, Search, AI Changes, Source control, Notepads, Chats, Settings.
- **Editor**: the Monaco editor from VS Code. Ctrl S saves.
- **Bottom panel**: Terminal (several bash tabs), Agent log, Problems.
- **AI panel**: the chat. Drag the gutters to resize; the layout is remembered.
- **Status bar**: engine state, free memory of the VM and of the Googlebook, speed, context use, cursor position, Tab completion state.

## 5. Working with the agent

Type at the bottom right and press Enter (Shift Enter makes a new line). Three modes:

- **Agent** reads, searches, edits and runs commands until the task is done. Every command is shown first with Run, Always allow this chat and Skip. Edits are snapshotted and shown as file chips you can click to see the diff.
- **Ask** gives a plain answer with no edits.
- **Plan** investigates with read-only tools and writes a numbered plan first.

Habits that help:

- **Thinking** shows the model's reasoning before each step.
- **Auto-run** lets the agent run commands without asking; destructive commands still ask.
- **Context**: type @ to attach a file, @codebase, @web, @git, @terminal or @notepad. Ctrl L adds the current selection or file.
- **Stop** with the red square or Esc. Type "continue" to resume after the step limit.
- **Code blocks** in replies have Copy, Insert at cursor and Apply to file with a diff review.
- **Verbose mode** (the V button) shows raw requests, tool calls, trimming and timing.

## 6. Screenshots and images

- **Paste** a screenshot into the chat box with Ctrl V, **drop** an image on the chat, or click **+ Image**. PNG, JPEG, GIF and WebP up to 15 MB work. The image shows as a thumbnail; click it to see it full size.
- When you send, Forge reads the text in the image and asks the vision model to describe it. Llama gets both and answers. A panel "What the vision model sees in image 1" under your message shows exactly what Llama was told.
- Llama can ask the vision model its own follow-up questions with its look_at_image tool, for example what an error dialog says exactly. You see these as "Look at image" cards. It can also look at image files anywhere on the computer by path.
- On the GPU, Llama's engine pauses while the vision model looks, then resumes. A look takes about a minute, and Llama needs about 15 seconds to resume.
- When there is not enough free memory for the vision model, Forge uses text recognition only and says so in the panel.
- The Images section in Settings chooses: auto (GPU, then CPU, then text recognition), gpu, cpu, or ocr (text recognition only).

## 7. Editing with AI

- **Ctrl K** on a selection: describe the change, review the diff, Ctrl Enter accepts, Esc rejects.
- **Tab completions** are off by default; Alt \ asks for one, the Tab chip in the status bar turns them on.
- **Right-click** in the editor: Add selection to chat, Edit with AI, Explain, Fix problems.

## 8. Reviewing and undoing

- Click a **file chip** under a reply for the side-by-side diff with Keep and Revert. **Revert all** under a reply undoes every file change of that reply.
- **AI Changes** lists the changes of this folder by request, newest first, with Revert all per request. "This folder" switches to "All folders". The changes are saved, so you can still review and revert them after Forge, the VM or the Googlebook restarts.
- Files the agent removes go to `.forge-trash` inside the project, or to `~/.forge-ide/trash` outside it. Nothing is deleted outright.
- Every save and agent edit runs a syntax check; failures appear in **Problems** with Open and Fix with AI.

## 9. Working outside the project folder

The agent may read and change files anywhere on the computer with absolute or ~ paths, for example `/mnt/shared/Documents` (your Documents folder). With **External-file protection** on (Settings, Agent), it asks before changing a file outside the project; the approval card shows the path and the change. System files (for example in /etc or /usr) and keys (for example ~/.ssh) always ask, even with protection off. You can open any file yourself by typing its path in quick open (Ctrl P), for example `/etc/hostname`.

## 10. Source control, notepads, rules, MCP

- **Source control**: branch, changed files, stage, unstage, discard (a copy is kept), diff against HEAD, Generate message, Commit, Pull, Push.
- **Notepads**: reusable notes attached with @notepad.
- **Rules for the AI** in Settings: global rules and project rules in `.forge/rules.md`; `.cursorrules`, `AGENTS.md` and `CLAUDE.md` are read too.
- **MCP servers** in Settings: their tools run only after your approval.
- **Terminal Ctrl K**: plain English in, a command pasted into the terminal for you to run.

## 11. Settings: User and Folder

The Settings view has two tabs. **User** settings apply to every folder. **Folder** settings override them for the current folder only and are saved in `.forge/settings.json` inside it. A setting without an override says "(from User)", and an override has a Reset link. Engine, connection and memory settings are machine-wide and only appear under User.

## 12. The GPU engine

**Start it**: Forge app, Settings, the GPU engine card, Start engine. The first start downloads the model weights once (4.9 GB). The app asks the VM to hand memory back, waits until the free-memory check passes, loads the model and reports "running". A notification with a Stop engine button stays visible.

**Use it**: Use GPU engine for chats on the card, or Settings, Engine, gpu. Switching engines starts a fresh chat context.

**Stop it**: the notification's Stop engine, or the card.

If the GPU engine is not running, a chat tells you so instead of loading the 5 GB CPU model by itself. Start the engine, or switch the engine to Ollama in Settings.

## 13. Memory safety

The Googlebook has 16 GB for Android, the VM and the models together. If it runs out, Android closes apps, and if it closes the Terminal app, the VM goes with it. Forge's rules:

- Every load on the GPU must pass the free-memory check, which keeps at least 2.5 GB free afterwards. It is never bypassed. The engine needs about 5.9 GB free to start, the 4B vision model about 3.6 GB.
- Only one model is on the GPU at a time. The vision model and Llama take turns.
- When a GPU model stops, the graphics driver keeps its memory for reuse, and Android does not show it as free. Forge accounts for that only within 15 minutes of its own GPU use. After a long pause, the engine may wait for memory; closing heavy apps helps, and restarting the Googlebook clears it.
- In the VM, the CPU model loads only with about 5.8 GB free in the VM and, when the Forge app can report it, on the Googlebook too. A watchdog unloads models under 320 MB free in the VM.
- The command palette has Unload the model from memory.

Do not run Waydroid, a second model or a large build at the same time as a model.

## 14. Keyboard shortcuts

| Keys | Action |
| --- | --- |
| Ctrl P | Quick open a file (also any path starting with / or ~) |
| Ctrl Shift P | Command palette |
| Ctrl Shift N | New window |
| Ctrl O | Open a folder |
| Ctrl L | Add selection or file to the chat |
| Ctrl K | Edit the selection with AI (in the terminal: ask for a command) |
| Ctrl V in the chat | Paste a screenshot |
| Ctrl I | Toggle the AI panel |
| Ctrl Enter | Send |
| Esc | Stop the agent, close the palette, a dialog or the image viewer |
| Ctrl ` | Toggle the terminal |
| Ctrl B | Toggle the sidebar |
| Ctrl S | Save |
| Ctrl W | Close the file |
| Ctrl N | New chat |
| Ctrl Shift F | Search in files |
| Ctrl Shift G | AI Changes |
| Ctrl Shift H | Chat history |
| Alt \ | Ask for a completion |
| Ctrl R (in the app) | Reload the IDE |

## 15. Where things are

Code in `~/projects/forge-ide`; data in `~/.forge-ide/` (settings, chats, checkpoints, images, notepads, rules, vision models); folder settings and rules in `.forge/` inside each folder; the app and this manual in Documents/Forge; the engine build in `~/tools/gpu-build`; Ollama tuning in `/etc/systemd/system/ollama.service.d/forge-tuning.conf`.

## 16. Troubleshooting

- **Waiting screen**: the VM or server is not up. Open the Terminal app, wait, Retry, or `forge-ide status`.
- **Ollama offline**: `sudo systemctl restart ollama`.
- **Slow first reply**: the model is loading or a cold prompt is being processed. Follow-ups are fast.
- **The engine waits for free memory**: close heavy apps. If it keeps waiting after GPU work, restart the Googlebook.
- **A screenshot gets only text recognition**: the vision model did not fit in free memory, or the Forge app is not running. The panel under the image says why.
- **"Not enough free memory"** for the CPU model: close other programs, or use Unload, then retry.
- **The agent seems confused by earlier parts of a long chat**: start a new chat (Ctrl N), or use Reset conversation context in the palette; the transcript stays.
- **A bad agent change**: Revert all under the reply, or AI Changes.

## 17. Updating Forge

Edit the code, then `forge-ide restart`. For the app, `android/build.sh` and `adb install -r android/Forge.apk`. For the model file, edit `model/Modelfile` and run `forge-ide model`.
