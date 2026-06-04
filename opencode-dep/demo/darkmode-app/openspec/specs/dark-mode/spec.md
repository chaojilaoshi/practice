# dark-mode Specification

## Purpose
TBD - created by archiving change add-dark-mode. Update Purpose after archive.
## Requirements
### Requirement: Theme Selection
The system MUST let users choose between light, dark, and system themes, and MUST
apply the selected theme immediately without a page reload.

#### Scenario: User selects dark theme
- **WHEN** the user opens settings and selects "Dark"
- **THEN** the UI switches to the dark theme immediately
- **AND** the preference is saved to localStorage under the key `theme`

#### Scenario: User selects system theme
- **WHEN** the user selects "System"
- **THEN** the UI follows the OS `prefers-color-scheme` value
- **AND** the UI updates live when the OS preference changes

### Requirement: Theme Persistence
The system MUST restore the previously selected theme on application load.

#### Scenario: Returning user with saved preference
- **WHEN** a user who previously chose "Dark" reloads the app
- **THEN** the app renders in dark theme on first paint
- **AND** no light-to-dark flash is visible

