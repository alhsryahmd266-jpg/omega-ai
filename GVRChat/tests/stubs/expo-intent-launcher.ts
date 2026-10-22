export const calls: { action: string; params: any }[] = [];
export const startActivityAsync = async (action: string, params: any) => { calls.push({ action, params }); return {}; };
