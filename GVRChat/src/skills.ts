/**
 * skills.ts — step-by-step procedures the agent loads before a multi-step task.
 * Pure TypeScript (no React Native imports) so it can be tested in Node.
 * Each body lists steps; every step has a success check and a fallback.
 */
export interface Skill { name: string; description: string; body: string }

export const SKILLS: Skill[] = [
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
  'On a web page: use browser to read it, then browser_click or browser_type with the element numbers it lists. Never guess a number. Video files: extract frames and their text and audio; say clearly that you cannot see what happens visually.',
  'To chat or act inside an app: call open_app with its package, then screen_allow it (the user confirms), then screen_read, then screen_type and screen_tap with the numbers shown. Never answer with a web search when the user asks to use an app.',
  'To act on the phone screen: call screen_read first, then screen_tap or screen_type with the numbers it shows. If the screen is off, call screen_enable.',
  'Secrets such as tokens: call request_secret. Never ask the user to paste a token in chat.',
];
