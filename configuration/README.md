# Remote configuration

This Android library contains the Kotlin/Retrofit client, process-wide manager,
testable repository and a Compose settings container. It uses the existing
`GET /api/settings?route=features` backend. No deployment is required to build it.

Safe Mode is an emergency off-switch: the admin turns it on or off for every user at
once. The request carries no device data (no language, version or country) and the
server answers the same for everyone. The app as shipped is the default: the state
starts with Safe Mode off, only a literal JSON `true` turns it on, and errors,
timeouts or malformed answers keep the last known state.

`ApplicationLoader` refreshes on process start and on returns to the foreground;
`UMessageRemoteFlags` re-checks it with its poll. The native settings screen observes
changes through a main-thread listener. Individual option pages close when Safe Mode
turns on. `UMessageConfig.get/set` also enforce the current state.

The existing native UI maps extended cache to the three local retention options:
`KEY_SAVE_EDITED`, `KEY_SAVE_DELETED`, and `KEY_SAVE_VIEW_ONCE`. Adjust
`UMessageConfig.isConfigurationAvailable` and the saved-section condition in
`UMessageSettingsActivity.fillMessages` if the intended option set changes.
No cached messages or files are deleted by this integration.

For a Compose screen, use the provided container with existing UI content:

```kotlin
ConfigurationSettings(
    standardContent = { StandardSettings() },
    extendedCacheContent = { ExtendedCacheSettings() }
)
```

`StandardSettings` and `ExtendedCacheSettings` above represent the host screen's
composables. The container observes state with `collectAsStateWithLifecycle` and
only composes the extended content when allowed. The current host application
uses Java/native settings, so it integrates the same state without replacing
that screen with Compose.

Run `./gradlew :configuration:testDebugUnitTest :configuration:assembleDebug`.
The tests cover headers, endpoint routing, strict flag types, HTTP failures,
network failures, timeout, refresh state, cancellation, and concurrent requests.
Directory tests use temporary fixtures to verify size, idempotent deletion,
unrelated-file preservation and refusal to follow symbolic links.
Backend policy tests: `npm --prefix umessage-premium-server run test:features`.

Remote configuration controls client behavior; it does not grant authorization
for server operations. Enforce any server permissions on the backend separately.

## Local data controls

`LocalDataPanel` is a Compose confirmation and size panel with hoisted
`LocalDataState`. Pass it in `ConfigurationSettings.localDataContent`; this slot
always stays in the composition, including when `stable_release` is true. Keep
extended feature controls in `extendedCacheContent`, which is removed from the
composition in standard mode. The optional `configurationFlow` parameter permits
injecting a screen-specific flow without replacing the process-wide manager.

The native host implements the same controls under U message settings →
messages → Local data. `UMessageLocalData.inspect` reports the dedicated media
copy directory and an approximate history size (preference file bytes plus
serialized retained message rows). General Telegram media cache has its own
existing cache-management screen, linked from this section.

After the user confirms, `UMessageLocalData.clear` disables the three retention
options, removes retained deleted-message rows through MessagesStorage's local
deletion API, verifies that those rows are gone, clears history preferences for
every account and removes the dedicated media directory contents. No Telegram
delete RPC is sent. It preserves account credentials, ordinary chats and copies
previously sent to cloud Saved Messages. It is a scoped local-data reset, not an
Android app factory reset. The shared database file itself remains; the history
size is logical data usage, not total SQLite disk allocation.

File traversal refuses symlinks before deleting files and reports partial errors.
The process-wide clear lock, retention checks and generation counter prevent
queued edit/download jobs from recreating cleared copies. Work happens on
background/storage queues; results and refresh notifications return to the UI
thread. Clear controls remain available when remote features are disabled.
User data is never cleared by a remote flag change or on startup.
