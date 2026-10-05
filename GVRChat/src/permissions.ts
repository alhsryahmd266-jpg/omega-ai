/**
 * permissions.ts — central place for Android runtime permissions.
 *
 * Every permission here must also be declared in app.json (android.permissions),
 * otherwise Android silently refuses the request.
 *
 * Honest limits:
 *  - "all_files" (MANAGE_EXTERNAL_STORAGE) can't be granted through a normal
 *    popup on Android 11+. We can only open the app's settings page; the user
 *    has to flip "Allow access to manage all files" there. Its state can't be
 *    read from plain React Native, so it is reported as "unknown".
 *  - Camera / microphone / location are requested here, but a feature that
 *    actually uses them needs a native module (not part of this build yet).
 */
import { PermissionsAndroid, Platform, Linking } from 'react-native';
import type { Permission } from 'react-native';

export type PermKey =
  | 'storage'
  | 'all_files'
  | 'camera'
  | 'microphone'
  | 'location'
  | 'notifications';

export const PERM_KEYS: PermKey[] = [
  'storage', 'all_files', 'camera', 'microphone', 'location', 'notifications',
];

export const PERMISSION_LABELS: Record<PermKey, string> = {
  storage: 'الملفات والوسائط',
  all_files: 'إدارة كل الملفات',
  camera: 'الكاميرا',
  microphone: 'الميكروفون',
  location: 'الموقع',
  notifications: 'الإشعارات',
};

export type PermStatus = 'granted' | 'denied' | 'unknown';

const P = (name: string) => `android.permission.${name}` as Permission;

function androidApi(): number {
  return typeof Platform.Version === 'number' ? Platform.Version : parseInt(String(Platform.Version), 10) || 0;
}

/** The concrete Android permissions behind a key (depends on the OS version). */
function androidPermsFor(key: PermKey): Permission[] {
  const api = androidApi();
  switch (key) {
    case 'storage':
      return api >= 33
        ? [P('READ_MEDIA_IMAGES'), P('READ_MEDIA_VIDEO'), P('READ_MEDIA_AUDIO')]
        : api >= 30
          ? [P('READ_EXTERNAL_STORAGE')]
          : [P('READ_EXTERNAL_STORAGE'), P('WRITE_EXTERNAL_STORAGE')];
    case 'camera':        return [P('CAMERA')];
    case 'microphone':    return [P('RECORD_AUDIO')];
    case 'location':      return [P('ACCESS_FINE_LOCATION'), P('ACCESS_COARSE_LOCATION')];
    case 'notifications': return api >= 33 ? [P('POST_NOTIFICATIONS')] : [];
    case 'all_files':     return [];
  }
}

export function isPermKey(s: string): s is PermKey {
  return (PERM_KEYS as string[]).includes(s);
}

/** Loose matching so the model can say "files", "mic", "gps", ... */
export function normalizePermKey(raw: string): PermKey | null {
  const s = raw.trim().toLowerCase().replace(/[\s-]+/g, '_');
  if (isPermKey(s)) return s;
  const alias: Record<string, PermKey> = {
    files: 'storage', file: 'storage', media: 'storage', photos: 'storage', gallery: 'storage',
    all_file: 'all_files', manage_files: 'all_files', manage_external_storage: 'all_files',
    mic: 'microphone', audio: 'microphone', record_audio: 'microphone',
    gps: 'location', position: 'location',
    notification: 'notifications', notify: 'notifications',
  };
  return alias[s] ?? null;
}

export async function checkPermission(key: PermKey): Promise<PermStatus> {
  if (Platform.OS !== 'android') return 'unknown';
  if (key === 'all_files') return 'unknown';
  const perms = androidPermsFor(key);
  if (perms.length === 0) return 'granted'; // not needed on this Android version
  try {
    const results = await Promise.all(perms.map(p => PermissionsAndroid.check(p)));
    return results.every(Boolean) ? 'granted' : 'denied';
  } catch {
    return 'unknown';
  }
}

export async function getPermissionsStatus(): Promise<Record<PermKey, PermStatus>> {
  const out = {} as Record<PermKey, PermStatus>;
  for (const k of PERM_KEYS) out[k] = await checkPermission(k);
  return out;
}

export interface PermResult {
  key: PermKey;
  status: PermStatus;
  message: string;
}

export async function requestPermission(key: PermKey): Promise<PermResult> {
  if (Platform.OS !== 'android') {
    return { key, status: 'unknown', message: 'Permissions are only handled on Android.' };
  }

  if (key === 'all_files') {
    try {
      await Linking.openSettings();
      return {
        key, status: 'unknown',
        message: 'Opened the app settings page. The user must enable "Allow access to manage all files" there (this cannot be granted by a popup).',
      };
    } catch (e: any) {
      return { key, status: 'unknown', message: `Could not open settings: ${e?.message || e}` };
    }
  }

  const perms = androidPermsFor(key);
  if (perms.length === 0) {
    return { key, status: 'granted', message: 'Not required on this Android version.' };
  }

  try {
    const res = await PermissionsAndroid.requestMultiple(perms);
    const values = perms.map(p => res[p]);
    if (values.every(v => v === PermissionsAndroid.RESULTS.GRANTED)) {
      return { key, status: 'granted', message: 'Granted.' };
    }
    if (values.some(v => v === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN)) {
      return {
        key, status: 'denied',
        message: 'Denied permanently. The user must enable it manually in the app settings.',
      };
    }
    return { key, status: 'denied', message: 'Denied by the user.' };
  } catch (e: any) {
    return { key, status: 'unknown', message: `Permission request failed: ${e?.message || e}` };
  }
}
