/**
 * voice.ts — listens to one spoken sentence with Android speech recognition.
 * online: Google speech service (more accurate, audio is sent to Google).
 * offline: on-device, only if the Arabic offline pack is installed on the phone.
 */
import { PermissionsAndroid, Platform } from 'react-native';
import { requireNativeModule } from 'expo-modules-core';

const Native: any = requireNativeModule('Terminal');

export async function listen(mode: 'online' | 'offline' = 'online', lang = 'ar-EG'): Promise<string> {
  if (Platform.OS !== 'android') return 'ERROR: voice input works on Android only.';
  const granted = await PermissionsAndroid.request('android.permission.RECORD_AUDIO');
  if (granted !== 'granted') return 'ERROR: the microphone permission was denied.';
  return String(await Native.listenOnce(lang, mode === 'offline'));
}
