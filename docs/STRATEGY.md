# Termuxy — Product Strategy

> **The best way to drive AI coding agents from your phone.**
> Run Claude Code, Codex, opencode, Aider — or any terminal agent — persistently in tmux, and command them from anywhere.

Status: Living document · Owner: Termuxy core · Last updated: 2026-07-17

---

## 1. Vision

**One sentence:** Termuxy is the Android home for vibe coders — the mobile cockpit that connects you to the AI agents running on your own machines, keeping every session alive in tmux and every keystroke one tap (or one spoken word) away.

**"Vibe coder" defined:** A developer who codes by *directing* generative-AI coding agents (Claude Code, opencode, Codex CLI, Aider, Cursor-in-terminal, Gemini CLI, etc.) rather than typing every line. They want to start an agent on their dev box, walk away, and steer it from their phone — on the couch, on the train, at the gym.

**The job to be done:** *"Keep my agent working on my server even when I'm not at my desk — and let me jump back in, see what it did, and say the next thing, all from my phone without losing state."*

---

## 2. Why now

- **Agent CLI explosion (2025–2026).** Claude Code, opencode, Codex, Aider, Gemini CLI all run in the terminal. The natural persistence layer is **tmux**; the natural transport is **SSH/mosh**. This is now a documented, widely-shared workflow.
- **Mobile is the missing piece.** Countless blog posts ("Put Your Coding Agents in Your Pocket", "How I code from the gym", "Running AI Coding Agents From My Phone") describe the *same* hand-rolled stack: Termux/Termius + tmux + mosh + Tailscale. It works, but it's duct-taped together by power users.
- **Purpose-built apps are arriving — mostly on iOS.** Prompt (Simon Støvning), ServerCC, NovaAccess, AgentMux target iPhone. **The Android-specific, agent-specialized terminal is an open lane.**
- **Platform risk is real but narrow.** Anthropic's *Claude Code Remote Control* is the gravitational threat — but it's Claude-only, cloud-tethered, and Max-gated. There is durable demand for an **agent-agnostic, bring-your-own-box, open** client. That is Termuxy's lane.

---

## 3. Positioning

**Geoffrey Moore positioning statement:**

> For **vibe coders who run terminal AI agents on their own dev machines**, Termuxy is the **mobile terminal** that **keeps you in command of your agents from anywhere**. Unlike **generic SSH clients (Termius, JuiceSSH) or single-vendor clouds (Claude Remote Control)**, Termuxy is **agent-aware, resilient by default (mosh + tmux), and works with every coding agent you already use** — on Android.

**Category:** Mobile agent cockpit (a specialized terminal, not an SSH client).

**Tagline candidates:**
- *Termuxy — your agents, in your pocket.*
- *The Android terminal for AI coding.*
- *Vibe code anywhere. Your box. Your agents. Your phone.*

---

## 4. Target user (proto-persona)

**"Maya" — the roaming vibe coder**
- Runs 1–3 projects, each with a long-lived AI agent in tmux on a home/lab server (Mac mini, NUC, cloud box).
- Uses a mesh of agents: Claude Code for big refactors, opencode for open work, Codex for quick tasks.
- Pain: To check an agent mid-task she SSHes in from her phone, fights a tiny keyboard for the tmux prefix, loses the connection when the train goes through a tunnel, and can't tell whether the agent is waiting for her to approve a tool call.
- Wants: tap-to-connect, instant reattach, a clear "agent is waiting on you" signal, voice input, and big yes/no/approve buttons instead of typing `y` into a 6pt terminal.
- Device: **Android** (Pixel, Samsung, foldables). Consciously not on iPhone.

**Secondary user:** the on-device experimenter who wants to run a lightweight agent *on the phone itself* (already supported via proot Debian + the `termuxy-agent` launcher).

---

## 5. Current-state audit (what already exists)

The fork already has meaningful, **uncommitted** work toward this vision. This is our starting capital — do not throw it away.

| Capability | Status | Location |
|---|---|---|
| Fork identity `io.termuxy`, app name "Termux Voice" | Done (needs rebrand → "Termuxy") | `app/build.gradle` |
| Local Linux workspace via proot-distro (Debian) | Done | `TermuxyCodingMode.startCodingWorkspace()` |
| 3-pane tmux workspace (code / shell / tests) | Done | `TermuxyCodingMode` lines 136–145 |
| Agent launcher auto-detects **codex / opencode / claude / aider** | Done | `getAgentLauncherInstallCommand()` |
| Agent prompt dialog + FAB | Done | `showAgentPromptDialog()` |
| Voice input for prompts | Done | `TermuxVoiceInput.java` |
| tmux control buttons (prefix, split H/V, new window, choose session) | Done | `TermuxActivity` lines 644–656 |
| Package "autoheal" (rewrites `com.termux`→`io.termuxy` deb paths) | Done | `getPackageAutohealCommand()` |
| **Remote server connection (SSH/mosh)** | **MISSING** | — |
| **Server/connection registry (save hosts, keys)** | **MISSING** | — |
| **Remote tmux auto-attach + session browser** | **MISSING** | — |
| **Agent activity detection / "waiting for you"** | **MISSING** | — |
| Reconnect-on-drop (mosh) | **MISSING** | — |

**The single biggest gap between today and the stated vision:** everything today runs agents *locally on the phone*. The user's vision — *connecting to their local servers* — requires a first-class **remote connection layer**. That is the top priority.

---

## 6. Strategy & wedge

**The wedge (first experience that earns the user):**

> Open Termuxy → tap your server → you are **instantly inside the tmux session where Claude Code is running**, the agent's last 200 lines are right there, and a big bar at the bottom lets you approve, deny, send a spoken instruction, or jump between agent windows. You lock your phone; the agent keeps coding. You unlock 20 minutes later, mosh has held the line, and the new scrollback is waiting.

**Strategic pillars (our moats):**

1. **Agent-aware, not terminal-generic.** Termuxy knows it's looking at Claude Code vs opencode vs Codex. It surfaces *agent state* (idle / working / waiting-for-approval), not just bytes. Generic SSH clients will never do this well.
2. **Resilient by default.** mosh + tmux auto-attach means a vibe coder never loses an agent to a tunnel, a wifi switch, or a closed app. This is the feature competitors hand-wave and we make bulletproof.
3. **Agent-agnostic & open.** Works with *any* terminal agent on *your own* hardware. No vendor lock-in, no cloud account, no Max plan. This is the durable answer to Claude Remote Control.
4. **Mobile-native input.** Voice prompts, quick-action bar tuned to agent interactions (✅ approve / ❌ deny / ⎋ esc / `/` commands / paste), gestures for tmux panes. Stop fighting a desktop keyboard on a 6-inch screen.
5. **Local + Remote, one app.** Run an agent on-device (proot) for experiments, or drive the big rig on your server. Same UI, same muscle memory. No competitor does both.
6. **Android-first.** Greenfield, underserved. Foldable/DeX support is a bonus the iOS crowd literally cannot ship.

---

## 7. Competitive landscape

| Player | Platform | Agent-aware? | Resilient (mosh+tmux)? | Verdict |
|---|---|---|---|---|
| **Claude Code Remote Control** | Web/iOS | Claude-only | Cloud (vendor) | Platform risk; narrow, not agent-agnostic |
| **Prompt** (simonbs) | **iOS** | Partial | tmux yes, mosh manual | The iOS benchmark; not on Android |
| **ServerCC** | **iOS** | Claude/Codex | SSH only | Closest iOS analogue; Android gap = our opening |
| **NovaAccess** | Mobile | Partial | — | Niche |
| **AgentMux** | VS Code (desktop) | Claude/Codex | tmux | Desktop-only, not mobile |
| **Codeman** | CLI/server manager | Claude/opencode | tmux, auto-recover | Companion, not a mobile client |
| **Termius / JuiceSSH** | Android | No (generic SSH) | No | Powerful but blind to agents |
| **Stock Termux** | Android | No | Manual | Our upstream; no opinion, no UX |

**Gap we own:** *Android + agent-aware + resilient + agent-agnostic.* No one is there yet.

---

## 8. MVP scope (v1.0 — "Remote Cockpit")

The smallest product that delivers the wedge and earns the tagline.

**Must-have for v1.0:**
1. **Rebrand** "Termux Voice" → **"Termuxy"** everywhere (app name, manifest, strings, about).
2. **Servers screen** — save/edit/delete host entries (alias, host, port, user, auth = password | key | agent). Encrypted at rest (Android Keystore).
3. **Connect** — `pkg install openssh`-backed SSH; one tap connects and auto-runs `tmux attach -t <session>` (or creates a default session). Falls back to a shell if no tmux.
4. **mosh** option per server (install `mosh` package; prefer mosh when available) for resilient roaming.
5. **tmux session browser** — list remote `tmux ls`, attach/detach/new/kill, surfaced as a native sheet (not just `<prefix>s`).
6. **Agent quick-action bar** — replaces raw extra-keys with agent-tuned buttons: ✅ `y`, ❌ `n`, ⎋ `Esc`, `Ctrl-C`, `/`, paste, and **agent prompt** + **voice** (already built).
7. **Keep existing local workspace** (Coding Mode, proot, `termuxy-agent`) as a first-class "On this device" entry alongside remote servers.

**Explicitly out of v1.0:** agent output parsing/"waiting-for-you" detection, sync across devices, team/shared servers, notifications, custom theming engine. (v1.x / v2.)

---

## 9. Roadmap

### Phase 0 — Foundations (now)
- Land the current uncommitted work in reviewable commits.
- Rebrand Voice → Termuxy.
- Wire `openssh`/`mosh` bootstrap into the installer so first-run guarantees the toolchain exists.

### Phase 1 — Remote Cockpit MVP (v1.0) ← **the work to start today**
- Servers data model + encrypted store.
- Servers UI (list, add/edit, delete, connect).
- SSH connect + tmux auto-attach.
- mosh support + auto-reconnect.
- tmux session browser sheet.
- Agent quick-action bar.
- "On this device" entry → existing Coding Mode.

### Phase 2 — Agent awareness (v1.x)
- Detect active agent per pane (process tree / scrollback heuristics).
- "Agent waiting for approval" highlight + one-tap approve/deny.
- Per-server default session/agent; resume-last on connect.
- Notifications (foreground service) when an agent prompts for input.

### Phase 3 — Vibe UX (v2.0)
- Voice-driven agent prompts everywhere (extend `TermuxVoiceInput`).
- Prompt library / snippets / history.
- Beautiful defaults: curated fonts/themes, true-color, comfortable thumb typography.
- Foldable / DeX / external-keyboard polish.
- On-device agent (proot) tighter integration: one-tap Claude/opencode install.

### Phase 4 — Distribution & moat (v2.x+)
- F-Droid + GitHub Releases (APK, properly signed).
- Landing page + "vibe code from your phone" walkthrough.
- `termuxy-agent` companion scripts published separately.
- Optional E2E-encrypted sync of servers/snippets across devices.
- Telemetry-free by default; opt-in crash reports.

---

## 10. Risks & mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Claude Remote Control commodifies the core use case | High | Stay agent-agnostic + on-prem + Android; be the open layer above all vendors |
| mosh/SSH reliability on aggressive Android OEMs (battery kill) | High | Foreground service + user-facing "disable battery optimization" prompt; document per-OEM |
| Storing server credentials on-device | High | Android Keystore + encrypted DB; never log secrets; biometric gate |
| Fork drift from upstream Termux | Medium | Keep `termux-shared`/`terminal-*` close to upstream; isolate Termuxy code in its own packages |
| Bootstrap path-rewriting (`com.termux`→`io.termuxy`) fragility | Medium | Keep the autoheal layer; add tests; pin bootstrap versions |
| Google Play policy (terminal apps) | Medium | Ship F-Droid/GitHub first; Play is opt-in/experimental like upstream |

---

## 11. Success metrics

- **Activation:** % of new installs that connect to a server and attach to a tmux session within first session (target >60%).
- **Engagement:** median sessions/week per active user (target ≥5); median time-to-reconnect after drop <3s.
- **Retention:** D7 ≥40%, D30 ≥25%.
- **Reliability:** dropped sessions that auto-recover without user action >90%.
- **Reach:** Android focus — track install sources; aim to be the top-result for "Claude Code Android" / "opencode Android".

---

## 12. Execution plan (concrete, immediately actionable)

Ordered, dependency-aware. Each item is a reviewable unit of work.

**Phase 1 — Remote Cockpit MVP work breakdown:**

1. **Rebrand.** Rename app to "Termuxy" in `app/build.gradle` manifest placeholders, all `strings.xml`, About screen, shortcuts. Drop "Voice" from primary identity (keep voice as a *feature*).
2. **SSH/mosh bootstrap.** Ensure first-run guarantees `openssh` and `mosh` packages (extend installer/`need_pkg` logic in `TermuxyCodingMode` style).
3. **Servers data model.** New package `com.termuxy.servers` — `Server` model + encrypted store (SQLCipher or Keystore-wrapped prefs).
4. **Servers UI.** List/add/edit/delete screens; entry point in the drawer and a launcher shortcut.
5. **Connect engine.** SSH connect via the in-app shell, auto-run `tmux new-session -A -s termuxy` (attach-or-create). Surface connection errors clearly.
6. **mosh + reconnect.** Prefer mosh per-server; auto-reconnect on resume.
7. **tmux session browser.** Run `tmux ls` over the session, render a native bottom-sheet, attach/new/kill.
8. **Agent quick-action bar.** New extra-keys view tuned to agents (✅/❌/⎋/Ctrl-C/`/`/paste/prompt/voice).
9. **"On this device" entry** → existing `TermuxyCodingMode.startCodingWorkspace()`.
10. **Verify.** Build, lint, typecheck. Smoke-test the connect→tmux→quick-actions loop on an emulator/device.

**Definition of done for v1.0:** a vibe coder can install Termuxy on Android, save their dev box, tap connect, land inside their Claude Code tmux session, approve work with one tap, speak a new instruction, survive a network drop, and reattach — all without reading a tutorial.

---

*This document is the source of truth for Termuxy's direction. Update it as we learn.*
