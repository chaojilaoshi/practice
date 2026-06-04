## 1. Theme foundation

- [x] 1.1 Define CSS custom properties for light and dark in `src/styles/theme.css`
- [x] 1.2 Replace hardcoded colors in existing components with theme tokens
- [x] 1.3 Add inline `<head>` script to set `data-theme` before hydration (anti-FOUC)

## 2. Theme state & toggle

- [x] 2.1 Implement `ThemeProvider` context (`theme`, `resolvedTheme`, `setTheme`)
- [x] 2.2 Implement `useTheme` hook with localStorage persistence
- [x] 2.3 Subscribe to `prefers-color-scheme` changes for "system" mode
- [x] 2.4 Build `ThemeToggle` component (light / dark / system)

## 3. Tests & verification

- [x] 3.1 Unit-test `useTheme` (persistence, system resolution) — TDD, write first
- [x] 3.2 Component test: selecting "Dark" sets `data-theme="dark"`
- [x] 3.3 e2e (Playwright): preference survives reload with no FOUC
