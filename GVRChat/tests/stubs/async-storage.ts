const store = new Map<string, string>();
const AsyncStorage = {
  getItem: async (k: string) => (store.has(k) ? store.get(k)! : null),
  setItem: async (k: string, v: string) => { store.set(k, v); },
  removeItem: async (k: string) => { store.delete(k); },
  multiRemove: async (ks: string[]) => { ks.forEach(k => store.delete(k)); },
  clear: async () => { store.clear(); },
};
export default AsyncStorage;
export const __dump = () => Object.fromEntries(store);
