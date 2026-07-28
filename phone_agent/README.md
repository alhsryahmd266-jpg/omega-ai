# GVR-Agent — Phone Edition

## Setup on Android (Termux)

### Step 1: Install Termux
Download from [F-Droid](https://f-droid.org/packages/com.termux/) (NOT Play Store)

### Step 2: One-command setup
```bash
curl -sL https://raw.githubusercontent.com/alhsryahmd266-jpg/omega-ai/main/phone_agent/install.sh | bash
```

### Step 3: Run
```bash
python ~/gvr-agent/agent.py
```

## What it does
- **DeepSeek-R1-14B** runs 100% offline on your phone
- **Terminal tool** — real shell commands
- **Web search** — DuckDuckGo live search
- **Code executor** — runs Python code
- **File manager** — read/write files
- **Memory** — remembers between sessions

## Modes
| Mode | How |
|------|-----|
| `/mode agent` | ReAct loop (uses tools automatically) |
| `/mode gvr` | Generate → Verify → Refine |
| `/mode chat` | Simple fast chat |

## Requirements
- Android with 12+ GB free RAM
- 12+ GB storage for model
- Termux (F-Droid)
