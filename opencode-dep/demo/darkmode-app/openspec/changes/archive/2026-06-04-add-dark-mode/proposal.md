## Why

Users repeatedly request a dark theme to reduce eye strain during nighttime use.
Today the app only ships a light theme, and there is no way to follow the OS-level
color-scheme preference. Adding dark mode improves accessibility and retention.

## What Changes

- Add a theme toggle (light / dark / system) in the settings panel.
- Detect and follow the OS `prefers-color-scheme` when "system" is selected.
- Persist the chosen preference in `localStorage` and restore it on load.

## Capabilities

### New Capabilities
- `dark-mode`: User-facing theme switching, OS-preference detection, and persistence.

### Modified Capabilities
<!-- none: no existing spec requirements change -->

## Impact

- UI: new `ThemeToggle` component, `ThemeProvider` context.
- Styling: introduce CSS custom properties for theme tokens.
- Storage: read/write `theme` key in `localStorage`.
- No backend or API changes.
