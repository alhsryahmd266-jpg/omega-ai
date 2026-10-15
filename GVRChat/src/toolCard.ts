/**
 * toolCard.ts — pure helpers for the `install_tool` tool (unit-testable without React Native).
 * The agent installs a CLI tool into Alpine and gets back a "card": executables, version and --help,
 * so it knows HOW to run what it just installed (and can remember it).
 */

const clip = (s: string, n: number): string => (s.length > n ? `${s.slice(0, n)}…` : s);

/** Only characters that are valid in an apk package name survive (also makes the shell quoting safe). */
export function sanitizePkgName(arg: string): string {
  return (arg.trim().split(/[\s,]+/)[0] || '').toLowerCase().replace(/[^a-z0-9._+-]/g, '');
}

// Never auto-run these just to read their --help.
const DENY_BINS = '^(rm|dd|mkfs.*|shutdown|reboot|poweroff|halt|kill|killall|fdisk|sfdisk|init|su|sudo)$';

export function installScript(name: string): string {
  return [
    'set +e',
    `P='${name}'`,
    'if ! apk info -e "$P" >/dev/null 2>&1; then',
    '  if apk search -U -e "$P" 2>/dev/null | grep -q .; then',
    '    apk add -U --no-progress "$P" 2>&1 | tail -n 4',
    '  else',
    '    echo "NOT_IN_APK"',
    '    apk search -U "$P" 2>/dev/null | head -n 12',
    '    exit 3',
    '  fi',
    'fi',
    'echo "INSTALLED=$(apk info -e "$P" 2>/dev/null | head -n 1)"',
    'echo "VER=$(apk list -I "$P" 2>/dev/null | head -n 1 | cut -d\' \' -f1)"',
    'echo "DESC=$(apk info -d "$P" 2>/dev/null | sed -n 2p)"',
    "BINS=$(apk info -L \"$P\" 2>/dev/null | grep -E '^(usr/)?(local/)?s?bin/[^/]+$' | sed 's#.*/##' | sort -u | head -n 12)",
    'echo "EXECUTABLES=$(echo $BINS)"',
    `B=$(echo "$BINS" | grep -Ev '${DENY_BINS}' | head -n 1)`,
    'T=""; command -v timeout >/dev/null 2>&1 && T="timeout 5"',
    'if [ -n "$B" ]; then',
    '  echo "--- $B --version"; $T "$B" --version </dev/null 2>&1 | head -n 3',
    '  echo "--- $B --help"; $T "$B" --help </dev/null 2>&1 | head -n 25',
    'fi',
  ].join('\n');
}

export interface InstallCard {
  ok: boolean;
  /** What the model (and the user) sees. */
  card: string;
  /** Compact how-to kept in memory as "tool:NAME". */
  memory?: string;
}

export function parseInstallOutput(name: string, out: string): InstallCard {
  if (/(^|\n)NOT_IN_APK(\n|$)/.test(out)) {
    const similar = out.split('\n').map(l => l.trim()).filter(l => l && l !== 'NOT_IN_APK').slice(0, 12).join('\n');
    return {
      ok: false,
      card:
        `❌ '${name}' is not an Alpine (apk) package.\n` +
        (similar ? `Similar packages:\n${similar}\n` : '') +
        `Other ways: pip install ${name} (Python), npm i -g ${name} (Node), or download a static aarch64/musl binary with the terminal (wget/curl, then chmod +x).`,
    };
  }
  const field = (k: string): string => (new RegExp(`^${k}=(.*)$`, 'm').exec(out)?.[1] || '').trim();
  const installed = field('INSTALLED');
  if (!installed) return { ok: false, card: `Install failed:\n${clip(out.trim() || '(no output)', 1500)}` };
  const ver = field('VER') || installed;
  const desc = field('DESC');
  const execs = field('EXECUTABLES');
  const hi = out.search(/^--- /m);
  const help = hi >= 0 ? out.slice(hi).trim() : '';
  const lines = [`✅ ${ver}${desc ? ` — ${desc}` : ''}`, execs ? `Executables: ${execs}` : 'No executables (library/data package).'];
  if (help) lines.push('', clip(help, 1800));
  return {
    ok: true,
    card: lines.join('\n'),
    memory: clip(`${desc ? `${desc}. ` : ''}Executables: ${execs || 'none'}. ${help.replace(/\s+/g, ' ')}`, 500),
  };
}
