/**
 * skills.ts — step-by-step procedures the agent loads before a multi-step task.
 * Pure TypeScript (no React Native imports) so it can be tested in Node.
 * Each body lists steps; every step has a success check and a fallback.
 */
export interface Skill { name: string; description: string; body: string }

const BASE_SKILLS: Skill[] = [
  {
    name: 'github-install-run',
    description: 'Install or run a program from GitHub on the phone. Use for any request like "install X from GitHub", "run this repo", "get this tool".',
    body: `Goal: get the program installed and proven to run.
1. Identify the repo (owner/repo). If the user gave only a name, ask for the link.
2. Read the README with tool_evaluator. Success check: the README has an install or usage section. If not, go to step 8.
3. Look for a release: fetch_url on https://api.github.com/repos/OWNER/REPO/releases/latest. Success check: an asset name contains aarch64, arm64, or linux. If none, go to step 8.
4. Download the asset with download_file into the tools folder. Success check: the file exists and is not empty.
5. Install it: extract tar.gz/zip into the tools folder, or chmod +x a plain binary. Success check: the command runs without error.
6. Prove it works: run it with --version or --help. Success check: output contains text and exit code is 0.
7. Save a card with mem_save under the key tool:NAME (what it does, path, how to run it).
8. No usable release: if the package exists in Alpine (apk search NAME), install it with install_tool. Otherwise follow the README build steps one command at a time in terminal, checking each step's exit code.
9. If a step fails: try one alternative from this list. If that also fails, stop and tell the user the exact error and which step failed. Never guess a command that you have not checked.`,
  },
  {
    name: 'python-project-setup',
    description: 'Set up and run a Python project inside Alpine: virtualenv, dependencies, then run the main file. Use when the project is Python or has requirements.txt.',
    body: `Goal: the project runs its main file.
1. Check python exists: terminal "command -v python3". Success check: a path is printed. If not, terminal "apk add python3 py3-pip" and check again.
2. Create a venv in the project folder: python3 -m venv .venv. Success check: .venv/bin/python exists.
3. Install dependencies: if requirements.txt exists, run .venv/bin/pip install -r requirements.txt. Success check: exit code 0. If there is no requirements file, read the README for the install command.
4. Find the entry point: README usage line, or main.py / app.py / __main__.py. Success check: the file exists.
5. Run it with a short timeout (#timeout=60 line) and read the output.
6. If an import error appears (ModuleNotFoundError: X): install X with .venv/bin/pip install X, then run again. Limit this to 3 fixes; after that report the error.
7. Report to the user: what was installed, the exact run command, and the last output.`,
  },
];

const NEW_SKILLS: Skill[] = [
  {
    name: 'phone-screen-task',
    description: 'Do any task inside an app on the phone (open it, read it, tap, type). Use for "افتح X واعملي Y" requests in any app.',
    body: `Goal: finish the task inside the app and prove it.
1. Check control first: call diagnose. If accessibility is not connected, stop and tell the user the exact fix from the result. Do not guess.
2. Open the app: find_app with the name (it also returns the screen). If it says not installed, tell the user.
3. Allow the app if it is not allowed yet: screen_allow with its package. The user confirms. Never allow banking or payment apps.
4. Read: screen_read. Find the element numbers for the target (button, input, text). Never guess a number.
5. Act one step at a time: screen_type for text fields, screen_tap for buttons. Use screen_back to leave a wrong screen.
6. Verify after every action: screen_read again and check the expected change happened. If not, try one alternative, then report the exact screen state.
7. Finish: say what was done and what is still missing. Never claim success without a screen check.
Limits: games and some apps hide their content from accessibility. If screen_read returns no elements, say so instead of pretending.`,
  },
  {
    name: 'chat-session',
    description: 'Hold a bounded chat with a person in an app (WhatsApp or similar) inside an approved session. Use for "كلمني فلان" or "رد على فلان".',
    body: `Goal: a short, natural conversation the user approved, with clear limits.
1. Confirm the contact name is visible on screen (screen_read). If not, stop and ask the user to open the right chat.
2. Start a session only after the user agrees: session_start with contact, goal, message limit and minutes. The user confirms.
3. First message: say you are an AI assistant writing for the user, then the goal. Short sentences, no lists, no markdown, in the other person's dialect.
4. For each reply: read the last message on screen (screen_read), write one reply that answers it, type it (screen_type), then tap send (screen_tap on the send button). Check the message appears (screen_read).
5. Never discuss money, bank data, passwords, codes, appointments or commitments. Ask the user first.
6. If the other person asks whether you are human, say you are an AI assistant. Never deny it.
7. Stop when the session limit is reached, when the user says stop, or when the bubble is pressed. Then summarize the session with session_status.`,
  },
  {
    name: 'web-research',
    description: 'Answer questions that need current or checkable facts: news, prices, people, versions, software, documentation.',
    body: `Goal: an answer backed by sources, not memory.
1. Search: search with 2 to 4 precise keywords. Use the exact name of any product or person the user gave.
2. Read the best result: fetch_url on the most official or most recent link (docs, company site, reputable news).
3. If the page needs JavaScript or the content is missing: browser on the URL, then browser_click to open sub-pages by element number.
4. Cross-check important facts with a second source. If sources disagree, say so.
5. Answer in the user's language: direct answer first, then 2 to 4 key points, then the sources as links.
6. Never answer a person question from a search snippet alone. If you cannot verify, say you could not confirm it.
7. Dates matter: state the date of the source when it affects the answer.`,
  },
  {
    name: 'video-and-media',
    description: 'Work with a video or audio file: extract frames, text, speech, and say what can and cannot be known from it.',
    body: `Goal: be honest about what the media can tell you, and extract what is possible.
1. Check the file exists and its type (terminal: ls -la and ffprobe if available). If ffmpeg is missing, install it with install_tool or apk add ffmpeg.
2. Extract frames: ffmpeg -i FILE -vf fps=1/5 frame_%03d.jpg (one frame every 5 seconds).
3. Extract audio: ffmpeg -i FILE -vn -ac 1 -ar 16000 audio.wav.
4. Text on screen: report it only if you have an OCR tool. If not, say the on-screen text could not be read.
5. Speech: report it only if you have a speech-to-text tool. If not, say the audio could not be transcribed.
6. Visual events (who does what, what happens): say clearly that you cannot see the images. Never invent what happens in the video.
7. Summarize only what the tools actually produced, with timestamps when you have them.`,
  },
  {
    name: 'app-install-update',
    description: 'Install or update this app to the newest build, or install another APK the user gives.',
    body: `Goal: the newest build is installed and the user confirms it.
1. Call app_update. It opens the newest APK from the releases page.
2. Tell the user: Android asks for confirmation; the user must approve the install.
3. If the install says it is blocked, the user must allow installs from this source once in settings.
4. Keep the old app data in mind: if the signature differs, Android needs the old app uninstalled first, which removes local data. Warn the user before that step.
5. After the install, call diagnose to confirm the new version's control state.`,
  },
  {
    name: 'phone-troubleshoot',
    description: 'Find and fix why a phone feature does not work: screen control, bubble, alarms, reminders, speech, or model loading.',
    body: `Goal: find the real cause from evidence, then fix it.
1. Run diagnose first. Its output is the evidence.
2. If accessibility is OFF in settings: open the page with screen_enable and ask the user to turn GVR Agent on. Wait for confirmation, then diagnose again.
3. If accessibility is ON but NOT connected: ask the user to turn it off and on, then reopen the app once, then diagnose again.
4. If the overlay permission is NO: ask the user to allow "display over other apps" for GVR Agent, then call bubble_show.
5. If an app is not allowed: screen_allow it.
6. If a tool returns an error text: quote the exact error to the user. Never invent a cause.
7. Change one thing at a time, and test after each change.`,
  },
  {
    name: 'github-repo-work',
    description: 'Read, understand, and build or run a GitHub repository, including reading a README and following its install steps.',
    body: `Goal: the repository is understood, built or run, and verified.
1. Get the repo name (owner/repo) or the link. If only a name is given, ask for the link.
2. Read the repo overview: github_api with path /repos/OWNER/REPO for the default branch and description.
3. Read the README with tool_evaluator. Note the requirements, install commands, and run commands.
4. Check the file tree: github_api with /repos/OWNER/REPO/git/trees/HEAD?recursive=1. Find the entry point, requirements file, and build file.
5. Install dependencies in the right environment: Python in a venv (python-project-setup), Node with npm install in the project folder.
6. Run the smallest check first (tests, --help, --version). Only then run the full program.
7. Report: what was installed, the exact command that works, and the last output. If a step fails, quote the error and stop after one fix attempt.`,
  },
  {
    name: 'reminders-alarms-notes',
    description: 'Set alarms, reminders, and notes on the phone.',
    body: `Goal: the time-based or saved item is set, and the user knows it.
1. Alarm: set_alarm with HH:MM and a label. The clock app opens pre-filled; tell the user to confirm it there.
2. Reminder: remind with minutes and the text. Notifications permission may be asked the first time.
3. Note: note_add with the text. To review, note_list shows the latest 20.
4. Convert relative times (tomorrow, after an hour) to minutes or a clock time before calling the tool, and say the result back to the user.
5. Never promise an alarm or reminder will fire unless the tool confirmed it was scheduled.`,
  },
  {
    name: 'model-and-speed',
    description: 'Choose the right model and speed settings for this phone, or explain why a model is slow.',
    body: `Goal: the best model that fits the phone, with honest speed expectations.
1. Ask for the phone RAM if it is not known. Call model_pick with it.
2. Explain the result: the model name, the memory it needs, and the speed settings it uses.
3. Speed notes: smaller quantization is faster but slightly less accurate. Smaller context uses less memory. Speculative decoding needs a model that supports it; do not promise it for models that do not.
4. If the answer is slow, first check the context size and batch size, then suggest a smaller model. Do not claim a speed number you did not measure.
5. Vision models are separate files. Do not claim vision works unless a vision model is loaded.`,
  },
  {
    name: 'safety-and-boundaries',
    description: 'Rules that apply to every task: permissions, privacy, money, banking, other people, and what the agent must never do.',
    body: `Rules that always apply:
1. Never act in an app the user has not allowed (screen_allow). Never act in banking, payment, or wallet apps.
2. Never type or send money details, passwords, codes, card numbers, or account numbers.
3. Typing and sending to other people happens only inside an approved chat session, with the AI disclosure in the first message.
4. Ask before anything destructive: deleting, overwriting, uninstalling, sending money, or changing account settings.
5. Never try to bypass Android permissions, and never ask the user to paste a token in chat. Use request_secret.
6. Never invent a result. If a tool failed, say so with the exact message.
7. If the user asks for something unsafe or against the rules above, say no briefly and offer the closest safe alternative.`,
  },
  {
    name: 'answer-style',
    description: 'How to write answers: language, length, structure, honesty, and when to ask a question.',
    body: `Rules for every answer:
1. Reply in the user's language and dialect. Arabic dialect is fine; keep English words for names and commands.
2. Start with the direct answer or the result. Then the key details. No long preambles.
3. Use short paragraphs. Use a list only when the content is a list of steps or options.
4. State uncertainty plainly. Say what was checked and what was not.
5. If a question is ambiguous and the answer would change, ask one short question instead of guessing.
6. Never repeat the same answer twice in a row. If the user asks again, add what is new.
7. Never end with an empty reply. If the model has no text, answer from the latest tool result in one short paragraph.`,
  },
];

export const SKILLS: Skill[] = [...BASE_SKILLS, ...NEW_SKILLS];


export function skillsCatalog(): string {
  return SKILLS.map(s => `- ${s.name}: ${s.description}`).join('\n');
}

export function loadSkill(name: string): string | null {
  const s = SKILLS.find(x => x.name === name.trim());
  return s ? `# Skill: ${s.name}\n${s.body}` : null;
}

/**
 * PLAYBOOK — short rules the agent always sees, so it acts on its own:
 * it searches, installs, runs and checks without the user naming the tool.
 */
export const PLAYBOOK: string[] = [
  'Act on your own. Never tell the user to open the internet, the terminal or an app: call the tool yourself.',
  'Names of programs, apps, companies, models, versions or anything you may not know well: call search first, then fetch_url on the best link. Do not say you do not know before searching.',
  'Pages that need JavaScript or links to follow: use browser, then click_link with a number from its link list.',
  'Not sure which tool fits: call tool_search with keywords.',
  'Need to install or run a program or a Python project: call skill with github-install-run or python-project-setup, then follow its steps in order.',
  'Each step has a success check. Read the result. A non-zero exit code or an error means the step failed: try the fallback once, then report the exact error.',
  'Never claim something works until a tool result proves it.',
  'If tool results already answer the question, write the answer from them, naming the source. Never end with an empty reply.',
  'Alarms, reminders and notes on the phone: call set_alarm, remind, note_add or note_list directly. Do not ask the user to open the clock or notes app.',
  'In a chat session: write like a normal person, short messages, no markdown or lists, in the same dialect as the other person. The first message says you are an AI assistant writing for the user. If asked directly whether you are human, say you are an AI assistant. Stop when the session limits are reached or the user asks.',
  'To open any installed app by name: call find_app with the name. It opens the app and returns its screen. Then continue with screen_type and screen_tap, following the same steps as any screen task.',
  'To talk with the user while they stay in another app (for example WhatsApp): call chat_show. The floating chat window then carries the conversation. Call chat_hide when the user is done.',
  'Phone settings and app updates have their own tools: screen_enable for accessibility, bubble_show for the floating button, app_update to install a new build. Never run am, pm or settings commands in the terminal; the terminal is Alpine Linux and those commands do not exist there.',
  'On a web page: use browser to read it, then browser_click or browser_type with the element numbers it lists. Never guess a number. Video files: extract frames and their text and audio; say clearly that you cannot see what happens visually.',
  'To chat or act inside an app: call open_app with its package, then screen_allow it (the user confirms), then screen_read, then screen_type and screen_tap with the numbers shown. Never answer with a web search when the user asks to use an app.',
  'To act on the phone screen: call screen_read first, then screen_tap or screen_type with the numbers it shows. If the screen is off, call screen_enable.',
  'Secrets such as tokens: call request_secret. Never ask the user to paste a token in chat.',
];
