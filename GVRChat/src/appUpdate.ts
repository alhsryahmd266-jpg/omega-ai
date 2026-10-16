/**
 * appUpdate.ts — opens the newest APK build so Android can install it.
 * The user still confirms the install on the phone.
 */
import { Linking } from 'react-native';

const RELEASES = 'https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/releases?per_page=30';

export async function toolAppUpdate(): Promise<string> {
  try {
    const res = await fetch(RELEASES, { headers: { Accept: 'application/vnd.github+json' } });
    const list: any[] = await res.json();
    const apk = list.find(r => typeof r.tag_name === 'string' && r.tag_name.startsWith('apk-'));
    const asset = apk?.assets?.find((a: any) => String(a.name).endsWith('.apk'));
    if (!asset) return 'No APK release found.';
    await Linking.openURL(asset.browser_download_url);
    return `Opened ${apk.tag_name}. Confirm the install on the phone when Android asks.`;
  } catch (e: any) {
    return `Could not check for the latest APK: ${e?.message || e}`;
  }
}
