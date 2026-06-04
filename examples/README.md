# Light SDK demo tools

| Module | Package | Description |
|--------|---------|-------------|
| `ui-demo` | `com.thelightphone.uidemo` | UI toolkit gallery: theme, icons, scroll, text input, modal |

## How to run on device

```bash
./gradlew :examples:ui-demo:installDebug
adb shell am start -n com.thelightphone.uidemo/com.thelightphone.sdk.LightActivity
```

