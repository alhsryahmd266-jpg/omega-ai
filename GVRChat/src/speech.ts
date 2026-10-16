/**
 * speech.ts — the agent speaks out loud with the phone's own voice.
 * Works offline. Speech-to-text is a separate decision and is not here yet.
 */
import * as Speech from 'expo-speech';

export async function toolSpeak(arg: string): Promise<string> {
  const text = arg.trim();
  if (!text) return 'Give the text to say.';
  const isArabic = /[\u0600-\u06FF]/.test(text);
  Speech.speak(text.slice(0, 1000), { language: isArabic ? 'ar' : 'en-US', rate: 1.0 });
  return 'Spoken.';
}

export async function toolStopSpeaking(): Promise<string> {
  await Speech.stop();
  return 'Stopped speaking.';
}
