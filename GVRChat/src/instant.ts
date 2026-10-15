/**
 * instant.ts — pure helpers (no React-Native imports, so they are unit-testable in Node).
 *
 *  • formatNowArabic: an instant, always-correct answer for "what time is it" — no model needed.
 *  • looksUncertain:  detects "I don't know / sorry / I need more info / I can't access…" answers.
 *    Instead of leaving the user with a non-answer, the engine then searches the web by itself.
 */

const norm = (s: string): string =>
  s
    .replace(/[\u064B-\u065F\u0670\u0640]/g, '')
    .replace(/[أإآ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'ه')
    .toLowerCase();

export function formatNowArabic(now: Date = new Date()): string {
  try {
    const time = now.toLocaleTimeString('ar-EG', { hour: 'numeric', minute: '2-digit', hour12: true });
    const date = now.toLocaleDateString('ar-EG', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    let tz = '';
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch { /* no Intl */ }
    return `🕒 الساعة الآن ${time}\n📅 ${date}${tz ? `\n🌍 ${tz}` : ''}`;
  } catch {
    return `🕒 ${now.toString()}`;
  }
}

// Arabic has no \b, so boundaries are spelled out.
const L = '(?:^|[\\s،,.:;!؟?"\'(])';
const R = '(?=$|[\\s،,.:;!؟?"\')])';
const UNCERTAIN = new RegExp(
  [
    `${L}لا\\s+(?:اعرف|ادري|املك|يمكنني|استطيع|تتوفر|اقدر)`,
    `${L}(?:مش|مو)\\s+(?:عارف|متاكد|قادر)`,
    `${L}(?:معرفش|ما\\s+اعرفش|ما\\s+عنديش|مش\\s+عندي)`,
    `${L}ليس\\s+لدي`,
    `${L}(?:اسف|عذرا|للاسف)${R}`,
    `${L}(?:احتاج|يلزمني|محتاج)\\s+(?:الي\\s+)?(?:مزيد|معلومات|تفاصيل)`,
    `i\\s+(?:don'?t|do\\s+not)\\s+(?:know|have)`,
    `i'?m\\s+not\\s+(?:sure|certain|able)`,
    `i\\s+(?:can'?t|cannot|am\\s+unable\\s+to)\\s+(?:access|browse|provide|help|find|answer)`,
    'knowledge\\s+cutoff',
    'as\\s+of\\s+my',
    'no\\s+(?:real[- ]?time|access|information)',
    `(?:^|\\s)sorry${R}`,
    'apologi[sz]e',
  ].join('|'),
  'i',
);

/** Hedges and refusals come first in a reply, so only the opening is checked. */
export function looksUncertain(text: string): boolean {
  return UNCERTAIN.test(norm(text).slice(0, 400));
}
