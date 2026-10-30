# AppMan UI/UX audit

Short, focused review after v1.5. Format: observation → fix / recommendation.

## Applied in 1.6

| Area | Finding | Change |
|---|---|---|
| About | Version text was hardcoded (`v1.4`) | Read `versionName` from the package at runtime |
| Export | One tap could start a slow export by accident | Confirmation dialog first |
| Export result | Operation log closed with no next step | Completion dialog with **Open Downloads** and **Share zip** |
| Backup result | Dead end after backup | **Open Downloads** action |
| Running now | Only AppMan appeared because Android hides other processes | Read the real process list through Shizuku; fall back to foreground apps and say so |
| Touch target | Batch power icon was 40 dp | 44 dp, tooltip added |
| Docs | README and release notes were long | Rewritten: short sections, bullets, one-line sentences |

## Good patterns already in place

- Destructive actions always confirm (clear data, uninstall, export).
- Every Shizuku action has a timestamped operation log and a copy button.
- Disabled states explain themselves (“Shizuku is off — tap to set up”).
- Long-press tooltips on toolbar, details, power and selection controls.
- Consistent 44–46 dp controls, light theme, high-contrast primary blue.
- Search, filters, sort, empty states and running indicator are visible at a glance.

## Next (recommended, not blocking)

1. **First-run Shizuku onboarding**: a one-card checklist (Install → Start > Grant → Self-test).
2. **Accessibility pass**: TalkBack labels for the power icon and range segments, font-scale test at 1.3×.
3. **Screenshots** in README (need a device capture; Termux cannot capture the screen without shell/root).
4. **Empty state for Running now** (“No background apps visible — is Shizuku running?”).
5. **Per-row long-press hint** (“Long-press to select”) on first launch.
6. **Export/uninstall error copy**: map return codes to human messages (e.g. “system app cannot be uninstalled”).
7. **Small-screen pass**: verify the 5-button selection bar at 320 dp width.

## Principles

- Prefer one clear primary action per screen.
- Explain *why* a control is disabled; never leave a dead button.
- Show progress and results; no silent actions.
- Keep copy short: action + consequence.
- Use the platform dialog only when the platform really requires it; otherwise use AppMan’s own log UI.
