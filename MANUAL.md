# Forge IDE Manual (version 1.4)

Forge is a Cursor-style AI coding IDE that runs entirely on your Lenovo Googlebook 15. The editor, the agent, the terminal and the models all live on the laptop, and nothing you type leaves it. This manual explains every part of it.

## 1. The pieces

- **Forge IDE server**: a Python program in the Linux Terminal VM. It serves the editor and runs the agent, the tools and the terminal. It starts automatically with the VM.
- **Forge app**: the windows you open from the taskbar. It also hosts the GPU engine, the vision model and voice input.
- **Ollama with forge-coder**: your Llama 3.1 8B model, tuned for code, running on the CPU inside the VM.
- **Forge GPU engine**: the same Llama 3.1 weights on the Intel graphics, through llama.cpp's Vulkan backend, inside the Forge app.
- **The vision model**: Qwen3-VL, which looks at screenshots for Llama. It runs on the Intel graphics in the Forge app (4B, or 2B when memory is tighter), or on the CPU in the VM as a fallback. Text recognition (tesseract) reads the words in every image.
- **Helpers that start only when needed**: language servers for code intelligence, the Python debugger, notebook kernels, a small embedding model for the codebase index, and whisper.cpp for voice input in a browser. Each one starts when you use its feature, only when memory allows, and stops again when it is no longer used.

## 2. Starting and stopping

1. Open the **Terminal** app once after a restart so the Linux VM starts. It can stay in the background.
2. Click the **Forge** icon. If the VM is still booting you see a waiting screen that retries by itself.
3. Chrome works too, at http://localhost:8765.

From the Linux Terminal:

```
forge-ide status      shows whether the server runs, and the address
forge-ide start       starts it
forge-ide stop        stops it
forge-ide restart     restarts it (do this after editing its code)
forge-ide log         shows the last log lines
forge-ide open DIR    makes DIR the folder for new windows
```

## 3. Windows and folders

Every Forge window has its own folder, like a window in Cursor. The folder name is shown in the title bar and in the taskbar.

- **Open folder** (Ctrl O, or click the folder name) switches this window to another folder. **New window** (Ctrl Shift N, or the button next to the folder name) opens another window. The Open folder dialog has a New window button, and every recent folder has a ↗ that opens it in a new window.
- Each folder keeps its own chats, AI Changes, open files, explorer state, project rules, memories and folder settings. The terminal of a window starts in its folder.
- A chat always works in the folder it was started in. Opening a chat from another folder switches the window to that folder.
- **Several folders in one window**: the folder button with a plus at the top of the Explorer (or "Add folder to this window" in the command palette) adds another folder next to the project, like Cursor's multi-root workspaces. Each folder gets its own section in the Explorer, and quick open, search, replace and the agent's codebase search cover all of them. The ✕ on a folder's section removes it from the window; the folder itself is not touched. The list is saved in the project's `.forge/settings.json`.

## 4. The layout

- **Title bar**: folder name, new window, search (Ctrl P), the window's default model, and buttons for the sidebar, the terminal and the AI panel.
- **Activity bar**: Explorer, Search, AI Changes, Source control, Run and Debug, Notepads, Chats, Settings.
- **Editor**: the Monaco editor from VS Code, with code intelligence from language servers. Ctrl S saves. Ctrl \ splits the editor into two panes.
- **Bottom panel**: Terminal (several bash tabs), Agent log, Problems (with a count of errors and warnings), Debug console.
- **AI panel**: the chat, with a tab for each open chat. Drag the gutters to resize; the layout is remembered.
- **Status bar**: engine state, free memory of the VM and of the Googlebook, the codebase index, files waiting for review, the debugger state, speed, context use, cursor position and the Tab completion state.

## 5. Working with the agent

Type at the bottom of the AI panel and press Enter (Shift Enter makes a new line). The mode menu next to the input box chooses how the agent works:

- **Agent** reads, searches, edits and runs commands until the task is done. Every command is shown first with Run, Always allow this chat and Skip. Edits are snapshotted and shown as file chips under the reply.
- **Ask** gives a plain answer with no edits.
- **Plan** investigates with read-only tools and writes a numbered plan first.
- **Custom modes** are your own modes. Choose "Edit modes…" in the menu, or open Settings, Custom modes. A mode has a name, instructions that are added to every message you send in it, a choice between all tools and read-only tools, and an option to run commands without asking.

The **model menu** next to the mode menu belongs to the chat, like in Cursor. "Default" follows the window's setting; you can also pick the GPU engine or any Ollama model for this chat only. Each chat remembers its choice.

Habits that help:

- **Thinking** shows the model's reasoning before each step.
- **Auto-run** lets the agent run commands without asking; destructive commands still ask.
- **Stop** with the red square or Esc. Type "continue" to resume after the step limit.
- **Code blocks** in replies have Copy, Insert at cursor and Apply to file with a diff review.
- **Verbose mode** (the V button) shows raw requests, tool calls, trimming and timing.
- Forge tells the model which file you have open and where the cursor is, so "this file" works. Only the name and line are sent, not the whole file, so prompts stay small.

## 6. Chat tabs, the queue and the to-do list

- **Tabs**: every chat opens in its own tab at the top of the AI panel, and several chats can work at the same time. The + button (Ctrl N) starts a new chat in a new tab. A pulsing dot marks a chat that is working, and a yellow dot marks one that finished or needs your approval while you were in another tab. Ctrl Tab switches tabs and Ctrl W closes the current tab while you are in the AI panel. Your open tabs come back when you reopen the folder.
- **Queue**: you can keep typing while the agent works. Enter adds your message to a queue above the input box, and queued messages are sent one after another when the reply finishes. Each queued message has Send now (stops the current reply and sends it next), Edit and Remove.
- **To-do list**: for bigger tasks the agent writes a to-do list at the top of its reply and ticks items off as it goes.
- **Chat history**: the clock button, or the Chats view, lists the chats of this folder, with a switch to see all folders. Open chats are marked with a dot.
- **Export**: the download button saves the chat as a Markdown file in Documents, in the Forge/Chats folder.
- **Restore checkpoint**: point at one of your earlier messages and click "Restore checkpoint". Forge undoes every file change made from that message onward, removes the later part of the chat, and puts your message back in the input box so you can change it and send it again.

## 7. Context and attachments

Type **@** in the input box (or click "@ Add context") for the context menu:

- **Files** and **folders** of the project (a folder adds its file list and its most relevant code).
- **codebase**: the most relevant code of the whole project, found by meaning and by keywords.
- **docs**: a documentation site you indexed (see section 15). "add new documentation…" indexes a new one.
- **chat**: the questions and answers of an earlier chat of this folder, without its tool output.
- **web**: web search results for your message.
- **git**: your uncommitted changes.
- **terminal**: recent output of the active terminal.
- **linter errors**: every error and warning from the language servers and the syntax checks.
- **notepad**: one of your notepads.

Typing @codebase, @web, @git or @terminal by hand works too. **Ctrl L** adds the editor selection, or the current file when nothing is selected.

**Attachments**: the 📎 paperclip button attaches files of any kind. Code, text and PDF files are read for the model (PDF text needs `pdftotext`), and images go to the vision model. Other files are saved, and the agent is told where they are, so it can process them with a command. You can also paste or drop files on the chat. Each attachment shows as a chip above the input box; the ✕ removes it.

**Slash commands**: type / at the start of the input box. Built in: /new, /reset (clears the model's context but keeps the transcript), /export, /plan, /ask, /agent, /review (reviews your uncommitted changes), /explain, /tests, /fix-lints and /summarize. Your own commands are Markdown files in `~/.forge-ide/commands`, in `.forge/commands` or in `.cursor/commands` inside the project; the file name is the command and the file's text is the prompt.

## 8. Screenshots and images

- **Paste** a screenshot into the chat box with Ctrl V, **drop** an image on the chat, or click the image button. PNG, JPEG, GIF and WebP up to 15 MB work. The image shows as a thumbnail; click it to see it full size.
- When you send, Forge reads the text in the image and asks the vision model to describe it. Llama gets both and answers. A panel "What the vision model sees in image 1" under your message shows exactly what Llama was told.
- Llama can ask the vision model its own follow-up questions with its look_at_image tool, for example what an error dialog says exactly. You see these as "Look at image" cards. It can also look at image files anywhere on the computer by path.
- On the GPU, Llama's engine pauses while the vision model looks, then resumes. A look takes about a minute, and Llama needs about 15 seconds to resume.
- When there is not enough free memory for the vision model, Forge uses text recognition only and says so in the panel.
- The Images section in Settings chooses: auto (GPU, then CPU, then text recognition), gpu, cpu, or ocr (text recognition only).

## 9. Voice input

Click the microphone button in the AI panel (or press Ctrl Shift Space) and speak. Click it again when you are done.

- **In the Forge app**, Forge uses Android's own speech recognizer, the same Google speech service the Googlebook uses elsewhere. You see the words appear while you speak, short pauses do not stop it, and it stops by itself after three minutes. The first time, Android asks whether Forge may use the microphone. The microphone also turns off when you minimize or close the Forge window.
- **In a browser**, Forge records your voice and whisper.cpp turns it into text inside the VM after you click the button again. This needs the voice part of `install-extras.sh`.

## 10. Editing

- **Code intelligence**: language servers give suggestions as you type, information when you point at a name, go to definition (F12 or Ctrl click), find references, rename (F2), formatting, quick fixes and live errors, for Python, JavaScript, TypeScript, shell, JSON, CSS, HTML and C or C++. A server starts when you open a file of its language and stops when the window closes. Settings, Editor, Language servers turns them off.
- **Ctrl K** on a selection: describe the change, review the diff, Ctrl Enter accepts, Esc rejects.
- **Tab completions** are off by default; Alt \ asks for one, and the Tab chip in the status bar turns them on.
- **Right-click** in the editor: Add selection to chat, Edit with AI, Explain, Fix problems. Right-click on a tab or a file in the Explorer: Open to the side.
- **Split editor**: Ctrl \ opens the current file in a second pane on the right. Each pane has its own tabs, edits show in both, and Ctrl S saves the file of the pane you are typing in. Ctrl \ again closes the split.
- **Markdown preview**: Ctrl Shift V in a Markdown file shows a live preview next to it.
- **Images** open in an image viewer. Click the image to switch between fitting the window and actual size.
- **Search and replace**: the Search view finds text in all files of the window (with a file filter and regular expressions). Type a replacement and click "Replace all". Forge shows how many matches in which files before it changes anything, and the change can be undone from AI Changes.

## 11. Reviewing and undoing

- **In the editor**: files the agent changed are highlighted like in Cursor. Added lines are green and removed lines are shown in red above them, with **Keep** and **Undo** buttons for each change. A bar at the bottom right shows the number of changes with ‹ and › to step through them, **Keep all** and **Undo all**. Undo puts the old lines back and saves the file; Ctrl Z brings the change back. The status bar shows how many files are still waiting for review; click it to open the next one. "Review in editor" under a reply starts a review of all files that reply changed.
- **File chips** under a reply open a side-by-side diff with Keep and Revert. **Revert all** under a reply undoes every file change of that reply.
- **Restore checkpoint** on your own messages undoes everything from that point on (see section 6).
- **AI Changes** lists the changes of this folder by request, newest first, with Revert all per request. "This folder" switches to "All folders". Replacements from the Search view appear here too. The changes are saved, so you can still review and revert them after Forge, the VM or the Googlebook restarts.
- Files the agent removes go to `.forge-trash` inside the project, or to `~/.forge-ide/trash` outside it. Nothing is deleted outright.
- Every save and agent edit runs a syntax check, and the language servers check open files as you type. Problems lists both, with Open, Fix with AI for each problem, and Fix all with AI.

## 12. Debugging Python

Open the **Run and Debug** view (the play icon in the activity bar).

1. Click left of a line number to set a **breakpoint** (a red dot), or press F9 on the line. Breakpoints are remembered per folder and move with the code when you edit.
2. Press **F5** (or Start debugging). Forge saves your files and runs the Python file you have open; you can type another file and arguments in the view.
3. When the program stops, the line is highlighted. The view shows the **variables** (click ▸ to look inside lists, dictionaries and objects), your **watch** expressions and the **call stack** (click a frame to jump there).
4. Step with **F10** (over), **F11** (into) and **Shift F11** (out). **F5** continues, **F6** pauses a running program, **Ctrl Shift F5** restarts and **Shift F5** stops.
5. The **Debug console** in the bottom panel shows the program's output. While the program is paused, type an expression there and press Enter to evaluate it in the current frame.

"Run without debugging" (Ctrl F5) runs the file with its output in the Debug console. The debugger is debugpy; it needs `python3-debugpy` (`install-extras.sh debug`). Stopping a session also stops the program and everything it started.

## 13. Notebooks

Jupyter notebooks (`.ipynb` files) open as cells, like in Cursor.

- **Code cells** have their own editor. Shift Enter runs a cell and moves to the next, Ctrl Enter runs it and stays, Alt Enter runs it and adds a new cell below. ▶ Run all runs every cell in order.
- **Output** appears under each cell: printed text, the value of the last line, tables (for example from pandas), images (matplotlib figures are shown automatically) and errors. An error has a **Fix with AI** button that sends the cell and the error to the agent.
- **Markdown cells** show formatted text; double-click to edit, Shift Enter to finish.
- Point at a cell for ▲ and ▼ (move), M or { } (switch between Markdown and code) and ✕ (delete). The toolbar adds cells, interrupts a running cell, restarts the kernel and clears all output.
- The **kernel** is a Python process that keeps your variables between cells. It starts when you run the first cell, keeps running while the notebook is open (also across reloads), and stops after 10 minutes without an open window or 30 minutes without use. It runs inside its own 2 GB memory limit, so a cell that uses too much memory stops the kernel instead of the system.
- The notebook is still a normal file: Ctrl S saves it with its outputs, and "{ } JSON" shows the file itself. "New Jupyter notebook" in the command palette creates one.

## 14. The codebase index

Forge turns your code into embedding vectors with nomic-embed-text, a small embedding model, so codebase search and @codebase find code by what it does, not only by the words in it. Results are blended with keyword ranking.

- Indexing starts when a window opens and runs in the background at low priority. The status bar shows "Indexing 120/800" and then "Indexed". After the first time only changed files are indexed again.
- It pauses while a chat is running, waits when memory is short, and skips vendored, minified, generated and ignored files (in a git repository it follows `.gitignore`). A folder is limited to 4,000 pieces of code, newest files first.
- The embedding model runs in its own small llama.cpp server (about 250 MB), never in Ollama, so it never pushes Llama out of memory. It stops after two idle minutes.
- Settings, Codebase index shows the state and has Index now and Pause. Settings, Agent, Codebase index turns it off, also per folder.

## 15. Memories, rules, docs, hooks and more

- **Memories**: when you tell the agent something lasting ("always use tabs", "my server runs on Debian"), it offers to remember it. You see the memory, can edit it, and choose Save or Do not save. Memories are added to every chat, globally or for this project. Settings, Memories lists, edits and removes them.
- **Rules for the AI**: global rules and project rules in Settings (`~/.forge-ide/rules.md` and `.forge/rules.md`); `.cursorrules`, `AGENTS.md` and `CLAUDE.md` are read too. Cursor's rule folder works as well: Markdown files in `.cursor/rules` or `.forge/rules` with a small header. `alwaysApply: true` rules are always followed, rules with `globs` are added when a matching file is attached, and rules with only a `description` are listed so the agent reads them when they are relevant.
- **Docs**: Settings, Docs indexes a documentation site (up to 40 pages under the address you give) so you can mention it with @docs and the agent can search it with its docs_search tool. The ✕ moves an index to `~/.forge-ide/trash/docs`.
- **Hooks**: your own scripts that run at points in the agent loop: beforeSubmitPrompt, beforeShellExecution, afterFileEdit and stop. Configure them in Settings, Hooks (global or for the project). A hook gets the event as JSON on its input and the variables FORGE_EVENT, FORGE_ROOT, FORGE_FILE and FORGE_COMMAND. It may answer `{"permission": "allow" | "ask" | "deny", "message": "..."}`, and exit code 2 also means deny. For example, a beforeShellExecution hook can forbid certain commands, and an afterFileEdit hook can run a formatter.
- **Commands the agent may run**: Settings has an allowlist (commands that start with one of these run without asking) and a denylist (always refused, even with Auto-run on).
- **Source control**: branch, changed files, stage, unstage, discard (a copy is kept), diff against HEAD, Generate message, Commit, Pull, Push.
- **Notepads**: reusable notes; "Attach to chat" or @notepad adds one to a message.
- **MCP servers** in Settings: their tools run only after your approval.
- **Terminal Ctrl K**: plain English in, a command pasted into the terminal for you to run.

## 16. Working outside the project folder

The agent may read and change files anywhere on the computer with absolute or ~ paths, for example `/mnt/shared/Documents` (your Documents folder). With **External-file protection** on (Settings, Agent), it asks before changing a file outside the project; the approval card shows the path and the change. System files (for example in /etc or /usr) and keys (for example ~/.ssh) always ask, even with protection off. You can open any file yourself by typing its path in quick open (Ctrl P), for example `/etc/hostname`.

## 17. Settings: User and Folder

The Settings view has two tabs. **User** settings apply to every folder. **Folder** settings override them for the current folder only and are saved in `.forge/settings.json` inside it. A setting without an override says "(from User)", and an override has a Reset link. Engine, connection and memory settings are machine-wide and only appear under User. Below the settings are the cards for the GPU engine, the codebase index, custom modes, the command lists, memories, docs, hooks, rules and MCP servers.

## 18. The GPU engine

**Start it**: Forge app, Settings, the GPU engine card, Start engine. The first start downloads the model weights once (4.9 GB). The app asks the VM to hand memory back, waits until the free-memory check passes, loads the model and reports "running". A notification with a Stop engine button stays visible.

**Use it**: Use GPU engine for chats on the card, Settings, Engine, gpu, or pick it in a chat's model menu. Switching engines starts a fresh chat context.

**Stop it**: the notification's Stop engine, or the card.

If the GPU engine is not running, a chat tells you so instead of loading the 5 GB CPU model by itself. Start the engine, or pick an Ollama model.

## 19. Memory safety

The Googlebook has 16 GB for Android, the VM and the models together. If it runs out, Android closes apps, and if it closes the Terminal app, the VM goes with it. Forge's rules:

- Every load on the GPU must pass the free-memory check, which keeps at least 2.5 GB free afterwards. It is never bypassed. The engine needs about 5.9 GB free to start, the 4B vision model about 3.6 GB.
- Only one model is on the GPU at a time. The vision model and Llama take turns.
- When a GPU model stops, the graphics driver keeps its memory for reuse, and Android does not show it as free. Forge accounts for that only within 15 minutes of its own GPU use. After a long pause, the engine may wait for memory; closing heavy apps helps, and restarting the Googlebook clears it.
- In the VM, the CPU model loads only with about 5.8 GB free in the VM and, when the Forge app can report it, on the Googlebook too. A watchdog unloads models under 320 MB free in the VM. Switching a chat to a different Ollama model is refused when there is no room for it.
- The helpers check memory before they start: language servers, the debugger, notebook kernels, the embedding server and speech recognition each need free memory in the VM and on the Googlebook. Notebook kernels run inside a 2 GB limit. Helpers stop when they are not used, and the embedding server stops by itself if the Forge server ever stops.
- The command palette has Unload the model from memory.

Do not run Waydroid, a second model or a large build at the same time as a model.

## 20. Keyboard shortcuts

| Keys | Action |
| --- | --- |
| Ctrl P | Quick open a file (also any path starting with / or ~) |
| Ctrl Shift P | Command palette |
| Ctrl Shift N | New window |
| Ctrl O | Open a folder |
| Ctrl N | New chat in a new tab |
| Ctrl Tab | Next chat tab (Ctrl Shift Tab goes back) |
| Ctrl W in the AI panel | Close the chat tab |
| Ctrl L | Add selection or file to the chat |
| Ctrl K | Edit the selection with AI (in the terminal: ask for a command) |
| Ctrl V in the chat | Paste a screenshot or a file |
| Ctrl Shift Space | Voice input |
| Ctrl I | Toggle the AI panel |
| Enter or Ctrl Enter | Send (while the agent works: add to the queue) |
| Esc | Stop the agent, close the palette, a dialog or the image viewer |
| Ctrl ` | Toggle the terminal |
| Ctrl B | Toggle the sidebar |
| Ctrl S | Save |
| Ctrl \ | Split the editor, or close the split |
| Ctrl Shift V | Markdown preview |
| F12, Shift F12, F2 | Go to definition, find references, rename |
| F5 | Start debugging, or continue |
| Ctrl F5 | Run without debugging |
| Shift F5 | Stop debugging |
| F9 | Toggle a breakpoint |
| F10, F11, Shift F11 | Step over, into, out |
| Shift Enter in a notebook | Run the cell and go to the next |
| Ctrl Shift F | Search (and replace) in files |
| Ctrl Shift G | AI Changes |
| Ctrl Shift H | Chat history |
| Alt \ | Ask for a completion |
| Ctrl R (in the app) | Reload the IDE |

## 21. Where things are

Code in `~/projects/forge-ide`; data in `~/.forge-ide/` (settings, chats, checkpoints, images, attachments, memories, docs, hooks, commands, the codebase index, notepads, rules and models); folder settings, rules, memories and hooks in `.forge/` inside each folder; the app and this manual in Documents/Forge; exported chats in Documents/Forge/Chats; language servers in `~/.local/lib/forge-lsp`; whisper.cpp in `~/.local/lib/forge-voice`; the embedding server in `~/.local/lib/forge-embed`; the engine build in `~/tools/gpu-build`; Ollama tuning in `/etc/systemd/system/ollama.service.d/forge-tuning.conf`; helper logs in `~/.forge-ide/logs`.

## 22. Troubleshooting

- **Waiting screen**: the VM or server is not up. Open the Terminal app, wait, Retry, or `forge-ide status`.
- **Ollama offline**: `sudo systemctl restart ollama`.
- **Slow first reply**: the model is loading or a cold prompt is being processed. Follow-ups are fast.
- **The engine waits for free memory**: close heavy apps. If it keeps waiting after GPU work, restart the Googlebook.
- **A screenshot gets only text recognition**: the vision model did not fit in free memory, or the Forge app is not running. The panel under the image says why.
- **"Not enough free memory"** for the CPU model or a helper: close other programs, or use Unload, then retry.
- **No suggestions or hover in the editor**: the language server for that language is not installed (`install-extras.sh lsp`), memory was short when the file opened, or Language servers is off in Settings. Reopen the file after fixing it.
- **The microphone does nothing in the app**: allow the microphone for Forge in Android's settings (Apps, Forge, Permissions).
- **The index stays at "Index waiting"**: memory was short; it continues by itself when memory is free again. Settings, Codebase index shows the reason.
- **A notebook says the kernel stopped**: a cell used more than 2 GB of memory, or the kernel was idle for a long time. Run a cell to start a new kernel; variables need to be computed again.
- **The agent seems confused by earlier parts of a long chat**: start a new chat (Ctrl N), use /reset, or Restore checkpoint on an earlier message.
- **A bad agent change**: Undo in the editor, Revert all under the reply, Restore checkpoint, or AI Changes.

## 23. Updating Forge

Edit the code, then `forge-ide restart`. For the app, `android/build.sh` and `adb install -r android/Forge.apk`. For the model file, edit `model/Modelfile` and run `forge-ide model`. For the optional parts, run `install-extras.sh` again with the part's name.
