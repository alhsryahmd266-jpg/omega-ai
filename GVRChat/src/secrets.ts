/**
 * secrets.ts — encrypted storage for secrets such as API tokens.
 * The agent never receives a secret's value. It can only ask the user to fill
 * the secure box (SecretPrompt) and then use the secret by name in a tool.
 */
import * as SecureStore from 'expo-secure-store';

export type SecretRequester = (name: string, reason: string) => Promise<boolean>;
let requester: SecretRequester | null = null;

export function setSecretRequester(fn: SecretRequester | null): void {
  requester = fn;
}

const keyFor = (name: string): string => `gvr_secret_${name.replace(/[^a-zA-Z0-9_]/g, '_')}`;

export async function hasSecret(name: string): Promise<boolean> {
  return !!(await SecureStore.getItemAsync(keyFor(name)));
}

export async function getSecret(name: string): Promise<string | null> {
  return SecureStore.getItemAsync(keyFor(name));
}

export async function saveSecret(name: string, value: string): Promise<void> {
  await SecureStore.setItemAsync(keyFor(name), value);
}

export async function deleteSecret(name: string): Promise<void> {
  await SecureStore.deleteItemAsync(keyFor(name));
}

/** Returns true if the secret exists, or the user just saved it in the secure box. */
export async function ensureSecret(name: string, reason: string): Promise<boolean> {
  if (await hasSecret(name)) return true;
  if (!requester) return false;
  return requester(name, reason);
}
