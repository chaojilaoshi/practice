// GREEN phase: minimal implementation to pass the tests
export function resolveTheme(preference, systemPrefersDark) {
  if (preference === 'dark' || preference === 'light') return preference;
  return systemPrefersDark ? 'dark' : 'light';
}
