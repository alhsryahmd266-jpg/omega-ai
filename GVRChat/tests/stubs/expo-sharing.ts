export const calls: { uri: string; opts: any }[] = [];
export const isAvailableAsync = async () => true;
export const shareAsync = async (uri: string, opts: any) => { calls.push({ uri, opts }); };
