## Context

The app currently hardcodes light-theme colors in component styles. There is no
central theme state and no persistence. We want a minimal, dependency-free
approach that works with the existing React + CSS setup.

## Goals / Non-Goals

**Goals:**
- Light / dark / system theme switching with live updates.
- Persist preference and avoid first-paint flash (FOUC).
- No new runtime dependencies.

**Non-Goals:**
- Per-component theme overrides.
- Theming third-party embedded widgets.
- Server-side persistence of the preference.

## Decisions

- Use CSS custom properties (`--color-bg`, `--color-fg`, ...) scoped via a
  `data-theme` attribute on `<html>`.
- A `ThemeProvider` React context holds `theme` and `resolvedTheme`.
- Read the saved preference in a small inline `<head>` script before hydration to
  set `data-theme` early and prevent FOUC.
- Listen to `window.matchMedia('(prefers-color-scheme: dark)')` for "system".

## Risks / Trade-offs

- Inline head script duplicates a little logic, but it is the standard way to kill
  the light-to-dark flash. Accepted.
- CSS variables require touching existing styles to replace hardcoded colors;
  scoped to one PR, tracked in tasks.
