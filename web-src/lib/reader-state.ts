type NativeState = {
  getState(key: string): string | null;
  setState(key: string, value: string): void;
  removeState(key: string): void;
};

const bridge = () => (window as Window & { CamusReaderNative?: NativeState }).CamusReaderNative;

export const readReaderState = (key: string): string | null => bridge() ? bridge()!.getState(key) : localStorage.getItem(key);
export const writeReaderState = (key: string, value: string): void => {
  if (bridge()) bridge()!.setState(key, value);
  else localStorage.setItem(key, value);
};
export const removeReaderState = (key: string): void => {
  if (bridge()) bridge()!.removeState(key);
  else localStorage.removeItem(key);
};
