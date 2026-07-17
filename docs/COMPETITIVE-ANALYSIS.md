# Termuxy — Competitive Analysis

> How termuxy stacks up against the existing tools vibe coders use to drive AI coding agents from their phone, and what we should steal.

Status: Living document · Companion to `docs/STRATEGY.md` · Last updated: 2026-07-17

---

## TL;DR

The ecosystem splits cleanly into **two approaches**, and termuxy is the only project positioned to do *both*, natively, on Android:

| Approach | What it means | Who's in it | Termuxy's relation |
|---|---|---|---|
| **A. Agent runs ON the phone** | Local compute in your pocket (proot/VM/native) | `claude-code-android`, termuxy's existing **Coding Mode** | We already ship this (proot Debian + `termuxy-agent`) |
| **B. Agent runs on a box; phone is a remote cockpit** | SSH/mosh/WebSocket to a server, tmux keeps it alive | `claude-remote-terminal`, `agent-of-empires`, `Codeman`, `agent-deck`, `tmux-claude-session-manager` | **This is our v1.0 gap and our biggest opportunity** |

**The open lane:** *A native Android app that is an agent-aware, resilient, agent-agnostic remote cockpit — and can also run agents locally.* Every serious competitor is either (a) iOS-only, (b) a desktop TUI/Web app you reach from a *browser* on your phone, or (c) a single-vendor Claude lock-in. None are a native, agent-agnostic Android terminal.

---

## The forks (reference sources)

All five open-source projects have been cloned to `reference/` (gitignored) for study and selective borrowing. All are **MIT** or permissive — compatible with termuxy.

| Dir | Project | Lang/Stack | License | Primary value to us |
|---|---|---|---|---|
| `reference/agent-of-empires/` | **Agent of Empires** (2.8k★) | Rust TUI + Web PWA | MIT | Most mature: **Agent Client Protocol**, status detection, worktrees, remote phone access |
| `reference/agent-deck/` | **Agent Deck** (524★) | Go TUI + Web | MIT | Most feature-rich: **conductor/notifications**, cost tracking, MCP mgmt, remote SSH instances |
| `reference/Codeman/` | **Codeman** | Node/TS WebUI | MIT | Clean web-dashboard model; Claude/OpenCode/Codex multi-agent |
| `reference/claude-remote-terminal/` | **Claude Remote** | Flutter app + Python WS server | MIT | The closest **native Android** competitor — study its onboarding/protocol |
| `reference/tmux-claude-session-manager/` | **craftzdog's tmux-claude-session-manager** | Bash tmux plugin | MIT | Minimal reference: how to detect "agent done vs working" from tmux |

> Closed/commercial apps we can't fork but must out-position: **Prompt** (iOS, simonbs), **ServerCC** (iOS), **Blink Shell** (iOS/Android, generic), **Termius** (generic SSH), **Orca** (desktop+mobile companion), and the platform threat **Claude Code Remote Control** (Anthropic, Claude-only).

---

## Per-project deep dive

### 1. Agent of Empires (AoE) — `reference/agent-of-empires/` ⭐ the benchmark
- **Form factor:** Desktop/server TUI (Rust) + installable **Web PWA dashboard**. You reach agents on your phone *through a browser*.
- **Mobile story:** Press `R` in the TUI → exposes the web dashboard over HTTPS via **Tailscale Funnel** or **Cloudflare Tunnel** with QR + passphrase auth. The web dashboard has a **mobile-first "structured view"** powered by the **Agent Client Protocol** (`src/server/api/acp.rs`): plan panels, tool-call cards, **swipe-to-approve**.
- **Multi-agent:** Claude Code, OpenCode, Codex, Gemini, Cursor, Copilot, Aider, and ~15 more.
- **Status detection:** running / waiting / idle (`src/events/mod.rs`, `src/containers/runtime.rs`).
- **Notifications:** browser/PWA push + sound (`src/server/push.rs`).
- **Strengths:** Most mature agent-aware UX on the planet; ACP structured view is genuinely better than raw terminal for mobile; git worktrees + Docker sandbox.
- **Weaknesses (our opening):** Mobile is *a browser tab*, not a native app. No mosh. Requires a tunnel service (Tailscale/CF) for remote — extra setup. Heavy Rust binary on the server.
- **Steal:** (1) **ACP structured view** concept — render plan/tool-call/approval cards when an agent speaks ACP; (2) status-detection heuristics; (3) the "press R → QR → phone" remote-pairing mental model.

### 2. Agent Deck — `reference/agent-deck/` ⭐ the feature ceiling
- **Form factor:** Go TUI + Web UI. Desktop/server. **Not a native mobile app.**
- **Mobile story:** A **"conductor"** (a supervising Claude/Codex session) bridges to your phone over **Telegram or Slack** — you text a bot to check/restart/orchestrate sessions. Plus web UI reachable from a phone browser.
- **Features:** Status detection, **cost tracking dashboard** (per-model token $), MCP manager, skills manager, git worktrees, Docker sandbox, **remote SSH instances** (`agent-deck remote add`), socket isolation.
- **Strengths:** Deepest feature set; the conductor pattern solves "agent waiting while you're away"; cost tracking is table-stakes for heavy vibe coders.
- **Weaknesses:** Zero native mobile presence — you live in Telegram/Slack or a browser. Complex.
- **Steal:** (1) **Cost/token tracking**; (2) the **conductor → push-notification** idea (we do it as native Android notifications); (3) **remote instance registry** model for multi-server.

### 3. Codeman — `reference/Codeman/`
- **Form factor:** Node/TS **WebUI** (Fastify). `codeman web` → browser. Desktop/server.
- **Mobile story:** Phone browser to the web UI. No native app.
- **Multi-agent:** Claude Code, OpenCode, Codex.
- **Strengths:** Clean web-dashboard UX, parallel subagent visualization, 2861 tests (quality bar to match).
- **Weaknesses:** No native mobile, no terminal fidelity (web rendering), Node runtime dependency.
- **Steal:** Multi-agent session list UX; test-discipline precedent.

### 4. Claude Remote — `reference/claude-remote-terminal/` ⚠️ closest native-Android rival
- **Form factor:** **Flutter Android app** + a **Python WebSocket server** you install on your Mac/Linux box.
- **Architecture:** App **auto-discovers** the server on the LAN (mDNS/scan), 4-digit pairing, then a WebSocket PTY. Sessions are **tmux-backed** (`server/server.py`, `rt_<sid>`), with a **scrollback bytearray replayed on reconnect** (935-line server).
- **Agent:** **Claude Code only.** Custom slash commands `/continue-remote` and `/remote-devices-logout`.
- **Strengths:** Genuinely mobile-native; auto-discovery onboarding is delightful; session scrollback persistence is exactly right; `/continue-remote` handoff is a killer micro-feature.
- **Weaknesses (our entire wedge):** **LAN-only** (no mosh/Tailscale/remote); **Claude-only**; requires installing a **custom Python server** (not standard SSH); **no real terminal** (PTY-over-WS for Claude only); no agent-agnostic awareness; no structured/approval UX; hardcoded `AUTH_TOKEN` shipped in the repo.
- **Steal:** (1) **LAN auto-discovery + tap-to-connect** onboarding; (2) **scrollback replay on reconnect**; (3) the **4-digit/QR pairing** flow; (4) a termuxy `/continue-remote`-style handoff command.

### 5. tmux-claude-session-manager — `reference/tmux-claude-session-manager/`
- **Form factor:** A single Bash **tmux plugin** (`claude_session_manager.tmux`). Desktop only.
- **Value:** Minimal, readable reference for *how to detect whether a Claude session is "done" vs "working"* by inspecting tmux panes — the seed of termuxy's agent-status detection.

### 6. claude-code-android — `reference/claude-code-android/`
- **Not an app — a playbook.** Install scripts that get Claude Code running **on the phone** via stock Termux: (Path A) native linux-arm64 binary + `glibc-runner`/`patchelf` ELF patching + auto-updating wrapper; (Path B) proot-Ubuntu; (Path C) Android Virtualization Framework Linux VM.
- **Relation to termuxy:** **Complementary, not competitive.** It documents how to run Claude locally on Android — which termuxy's Coding Mode already does via proot Debian. We can adopt its **native binary-patching path** as a faster alternative to proot for on-device Claude, and its docs/security-model rigor.

---

## Feature comparison matrix

| Capability | Termuxy (target v1.0) | AoE | Agent Deck | Codeman | Claude Remote | tmux-csm | claude-code-android |
|---|---|---|---|---|---|---|---|
| **Native Android app** | ✅ | ❌ (web) | ❌ (web/bot) | ❌ (web) | ✅ (Flutter) | ❌ | ⚠️ (uses stock Termux) |
| **Run agent ON device** | ✅ (proot) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ (via Termux) |
| **Remote to your own server** | 🚧 v1.0 | ✅ (web) | ✅ (SSH) | ✅ (web) | ✅ (WS, LAN only) | ❌ | ❌ |
| **Standard SSH/mosh** | 🚧 v1.0 | ❌ (tunnel) | ✅ (SSH) | ❌ | ❌ (custom WS) | ❌ | ❌ |
| **tmux persistence + auto-attach** | 🚧 v1.0 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| **Agent-agnostic (≥3 agents)** | ✅ | ✅ (15+) | ✅ | ✅ | ❌ (Claude) | ❌ (Claude) | ❌ (Claude) |
| **Agent status detection** | 🔜 v1.x | ✅ | ✅ | ✅ | ❌ | ✅ (basic) | ❌ |
| **Structured/approval UX** | 🔜 v2 | ✅ (ACP) | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Push notifications** | 🔜 v1.x | ✅ (web push) | ✅ (TG/Slack) | ❌ | ❌ | ❌ | ❌ |
| **Voice input** | ✅ (have it) | ❌ | ❌ | ❌ | ❌ | ❌ | ⚠️ (SoX chain) |
| **Cost/token tracking** | 🔜 v2 | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Scrollback replay on reconnect** | 🔜 v1.0 | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| **LAN auto-discovery** | consider | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Git worktrees** | 🔜 v2 | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Local Linux workspace** | ✅ (have it) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

Legend: ✅ ships · 🚧 planned for our v1.0 · 🔜 on our roadmap · ❌ none

---

## Architecture comparison

| | Termuxy | AoE | Claude Remote |
|---|---|---|---|
| **Transport** | SSH + mosh (standard) to *your* box | HTTPS tunnel (Tailscale/CF) to a web server | Custom Python WebSocket (LAN) |
| **Session layer** | tmux on the server (attach-or-create) | tmux on the server | tmux on the server (`rt_<sid>`) |
| **Mobile render** | Native terminal emulator (we own the renderer) | Web/PWA (terminal or ACP structured view) | Flutter terminal over WS |
| **Auth** | SSH keys/pass + (opt) Tailscale | QR + passphrase | 4-digit pairing code |
| **Agent coupling** | Agnostic — any CLI agent | Agnostic — any CLI agent (+ACP for structured) | Claude only |

**Termuxy's architectural edge:** because we *are* a terminal emulator (forked from Termux), we get full terminal fidelity + the ability to run agents locally — things web dashboards and single-purpose Flutter apps structurally cannot match. Standard SSH/mosh means zero server-side install beyond "have sshd + tmux," which is a dramatically simpler story than every competitor.

---

## What termuxy should steal (concrete, with sources)

Ranked by leverage. All sources are in `reference/`.

1. **Scrollback replay on reconnect** — Claude Remote keeps a `scrollback` bytearray per session and replays it on re-attach (`reference/claude-remote-terminal/server/server.py`). *For us:* since we use real tmux, `tmux capture-pane -p -S -` already gives us this for free on every attach — document and lean into it.
2. **LAN auto-discovery + tap-to-connect** — Claude Remote's mDNS scan → server list by hostname. *For us:* a "scan local network" button in the Servers screen to pre-fill host entries. (`reference/claude-remote-terminal/README.md` → Auto-Discovery.)
3. **Agent status detection** — tmux-csm and AoE both infer running/waiting/idle from pane process state / output. *For us:* v1.x feature; `reference/tmux-claude-session-manager/` is the simplest starting point, `reference/agent-of-empires/src/events/mod.rs` the most complete.
4. **ACP structured view + swipe-to-approve** — AoE (`reference/agent-of-empires/src/server/api/acp.rs`). *For us:* v2 differentiator. When an agent speaks ACP, render native plan/tool-call/approval cards instead of raw bytes.
5. **Cost/token tracking** — Agent Deck. *For us:* v2; parse Claude/Codex transcripts, show per-session $.
6. **Notifications when agent waits** — Agent Deck's conductor + AoE's web push. *For us:* native Android foreground-service notification (better than both).
7. **Pairing flow** — Claude Remote's 4-digit/QR. *For us:* optional UX nicety for first connect, but SSH keys remain the real auth.
8. **Native on-device Claude path** — claude-code-android's glibc-runner/patchelf approach is faster than proot. *For us:* offer as an optional "fast local Claude" mode alongside proot Coding Mode.

---

## Where termuxy wins (the moat)

1. **Only native Android agent-agnostic terminal.** AoE/Codeman/agent-deck are web/bot; Claude Remote is native but Claude-only + LAN-only.
2. **Standard SSH/mosh = no server install.** Competitors require a custom daemon (Python/Node/Rust) running on your box. "Just sshd + tmux" is a categorically easier install.
3. **Real terminal fidelity.** We own the renderer (Termux lineage) → perfect colors, signals, TUIs. Web/Flutter renderers approximate.
4. **Local + Remote, one app.** Run an agent on-device *or* drive the big rig — nobody else does both.
5. **Resilience by default.** mosh + tmux auto-attach beats every competitor's "it reconnects (sometimes)".
6. **Voice-first prompting.** Already shipped; nobody else has it.

---

## Threats & response

| Threat | Severity | Our response |
|---|---|---|
| **Claude Code Remote Control** (Anthropic native, mobile/web) | 🔴 High | Stay agent-agnostic + on-prem + Android-native. Be the open layer above all vendors; RC is Claude-only and cloud-tethered |
| **AoE web dashboard** is "good enough" on mobile browser | 🟡 Medium | Win on: native UX, mosh resilience, zero-server-install, on-device option, voice |
| **Claude Remote** ships mosh/Tailscale + goes multi-agent | 🟡 Medium | Move fast on v1.0 remote cockpit; our terminal fidelity + agent-agnostic + local mode are durable |
| **A big player (Anthropic/OpenAI) ships a native Android agent app** | 🟠 Medium-Long | Open + agent-agnostic + bring-your-own-box is the durable position; partner-friendly, no lock-in |
| **Termux upstream stagnation / Android 12+ process kills** | 🟡 Medium | Foreground service + battery-optimization UX; keep close to upstream |

---

## Implications for the roadmap (updates to `docs/STRATEGY.md`)

These findings refine, but do not change, the strategy. Concrete adjustments:

1. **Promote "scrollback replay on reconnect" to a v1.0 must-have** (it's nearly free with real tmux) — it's the feature Claude Remote's whole reputation rests on.
2. **Add "LAN auto-discovery" as a v1.0 delight feature** in the Servers screen.
3. **Add ACP support to the v2 roadmap explicitly** as the path to structured agent rendering (track the standard; don't build a bespoke parser).
4. **Track Agent Deck's cost-tracking schema** for our v2 cost feature rather than inventing one.
5. **Confirm the wedge is real:** the #1 native Android rival (Claude Remote) is LAN-only and Claude-only. Our v1.0 (remote + mosh + agent-agnostic) is a strict superset and lands in open territory.
6. **Adopt claude-code-android's native-binary path** as an optional faster local-Claude mode (alongside proot), since proot is the documented weakness of on-device Claude today.

---

*Source forks live in `reference/` (gitignored). Update this doc whenever we re-evaluate a competitor or ship a feature that changes the matrix.*
