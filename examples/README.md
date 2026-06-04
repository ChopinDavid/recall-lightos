# Light SDK demo tools

| Module | Package | Description |
|--------|---------|-------------|
| `ui-demo` | `com.thelightphone.uidemo` | UI toolkit gallery: theme, icons, scroll, text input, modal |
| `weather` | `com.thelightphone.weather` | weather tool via Open-Meteo API 

## How to run on device

```bash
./gradlew :examples:ui-demo:installDebug
adb shell am start -n com.thelightphone.uidemo/com.thelightphone.sdk.LightActivity

./gradlew :examples:weather:installDebug
adb shell am start -n com.thelightphone.weather/com.thelightphone.sdk.LightActivity
```

