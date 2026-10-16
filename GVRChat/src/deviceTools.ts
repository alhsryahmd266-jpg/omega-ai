/**
 * deviceTools.ts — phone features the agent can use: alarms, reminders, notes.
 * Alarms open the clock app pre-filled (the user confirms there).
 * Reminders are real scheduled notifications. Notes live in app storage.
 */
import * as Notifications from 'expo-notifications';
import * as IntentLauncher from 'expo-intent-launcher';
import AsyncStorage from '@react-native-async-storage/async-storage';

const toLatinDigits = (s: string): string => s.replace(/[٠-٩]/g, d => String('٠١٢٣٤٥٦٧٨٩'.indexOf(d)));

export async function toolSetAlarm(arg: string): Promise<string> {
  const text = toLatinDigits(arg.trim());
  const m = /(\d{1,2})\s*[:.]\s*(\d{2})/.exec(text);
  if (!m) return 'Give the time as HH:MM, e.g. 07:30 | Gym';
  const hour = Number(m[1]);
  const minutes = Number(m[2]);
  if (hour > 23 || minutes > 59) return 'Invalid time.';
  const label = text.split('|')[1]?.trim() || 'GVR alarm';
  const shown = `${String(hour).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
  try {
    await IntentLauncher.startActivityAsync('android.intent.action.SET_ALARM', {
      extra: {
        'android.intent.extra.alarm.HOUR': hour,
        'android.intent.extra.alarm.MINUTES': minutes,
        'android.intent.extra.alarm.MESSAGE': label,
        'android.intent.extra.alarm.SKIP_UI': false,
      },
    });
    return `Opened the clock app with an alarm for ${shown} (${label}). The user confirms it there.`;
  } catch (e: any) {
    return `Could not open the clock app: ${e?.message || e}`;
  }
}

export async function toolRemind(arg: string): Promise<string> {
  const [a, ...rest] = toLatinDigits(arg).split('|');
  const minutes = parseFloat(a);
  const body = rest.join('|').trim();
  if (!(minutes > 0) || !body) return 'Give minutes and text, e.g. 10 | call Ahmed';
  const perm = await Notifications.getPermissionsAsync();
  let granted = perm.granted;
  if (!granted) granted = (await Notifications.requestPermissionsAsync()).granted;
  if (!granted) return 'Notifications are not allowed. Enable them in the phone settings.';
  await Notifications.setNotificationChannelAsync('default', {
    name: 'GVR',
    importance: Notifications.AndroidImportance.HIGH,
  });
  await Notifications.scheduleNotificationAsync({
    content: { title: 'GVR', body },
    trigger: { seconds: Math.round(minutes * 60) },
  });
  return `Reminder set in ${minutes} minute(s): "${body}".`;
}

const NOTES_KEY = 'gvr_notes';
type Note = { t: string; text: string };

async function readNotes(): Promise<Note[]> {
  try {
    return JSON.parse((await AsyncStorage.getItem(NOTES_KEY)) || '[]');
  } catch {
    return [];
  }
}

export async function toolNoteAdd(arg: string): Promise<string> {
  const text = arg.trim();
  if (!text) return 'Give the note text.';
  const notes = await readNotes();
  notes.push({ t: new Date().toISOString(), text });
  await AsyncStorage.setItem(NOTES_KEY, JSON.stringify(notes.slice(-200)));
  return `Note saved. You have ${Math.min(notes.length, 200)} notes.`;
}

export async function toolNoteList(): Promise<string> {
  const notes = (await readNotes()).slice(-20);
  if (!notes.length) return 'No notes yet.';
  return notes.map((n, i) => `${i + 1}. [${n.t.slice(0, 16).replace('T', ' ')}] ${n.text}`).join('\n');
}
