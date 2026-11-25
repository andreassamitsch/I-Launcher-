# Local TvProvider diagnostics (Development APK)

The debug-only receiver requires Android's signature/development `android.permission.DUMP` (ADB shell).
It creates a private, on-demand JSON snapshot without launching the TV UI or sending any data to a server.

```sh
adb shell am broadcast -a com.andreassamitsch.ilauncher.action.DUMP_TV_PROVIDER -n com.andreassamitsch.ilauncher/.diagnostics.TvProviderDumpReceiver
adb shell run-as com.andreassamitsch.ilauncher cat files/tvprovider-diagnostic.json
```

Check for broadcast result=1 and the snapshot timestamp. The report contains raw TV-provider preview channels and programs, Watch Next rows, release dates, source packages, row IDs, ordering, and possible duplicate groups (not a confirmed identity match). An empty result with errors is not proof of absence. The receiver performs read-only queries using the launcher's existing TV-listings grant; it does not expose URLs, credentials, or media descriptions. The private file is overwritten atomically each time. No automatic collection. A release build does not contain the receiver.

## TV test

- Install the new signed Development update using the existing updater once.
- With ServusTV closed, trigger the snapshot and find its preview channel and latest titles/dates. Repeat after a periodic refresh and compare rows.
- Verify a Watch Next duplicate can be inspected without changing the order or removing data.
- Check update popup on the Home screen with a newer compatible manifest: download, progress, install, Android confirmation; dismiss with Back/Später; ensure the current version is not offered again in the same session and newer versions can prompt.
- Check no popup interrupts Live TV or detail navigation; confirm Home focus restoration after dismissing.
