export const Platform = { OS: 'android', Version: 33 };
export const Linking = { openURL: async (_u: string) => {}, openSettings: async () => {} };
export const Share = { share: async (_o: unknown) => ({}) };
export const PermissionsAndroid = {
  PERMISSIONS: {} as Record<string, string>, RESULTS: { GRANTED: 'granted', DENIED: 'denied', NEVER_ASK_AGAIN: 'never_ask_again' },
  check: async () => false, request: async () => 'denied', requestMultiple: async () => ({}),
};
