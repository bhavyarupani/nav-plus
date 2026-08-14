# Android Auto test checkpoint

The `RoadPulse_API_36` Pixel 7 emulator is configured and RoadPulse installs and launches successfully on it. Android also registers `RoadPulseCarAppService` as a navigation service.

The Android 16 Google Play emulator image contains only `AndroidAutoStubPrebuilt` version `1.2.551000-stub`. Google Play marks the full Android Auto phone host as incompatible with this virtual device, so projected-display validation requires a physical Android phone. This follows Google's DHU workflow, which uses a mobile device connected to the development workstation.

RoadPulse was successfully validated on a Pixel 6 Pro running Android 16 with Android Auto `17.2.662634-release`. Wireless debugging was used for ADB transport. The DHU discovered RoadPulse in its launcher, rendered the route and alert screen, and kept it visible when full driving restrictions were simulated.

## One-time physical-phone step

1. Use an Android phone running Android 9 or newer.
2. Install or update **Android Auto** from Google Play on that phone.
3. Enable **Developer options > USB debugging** on the phone.
4. Connect the phone to this Mac by USB, unlock it, and approve the debugging prompt.
5. Open **Settings > Connected devices > Connection preferences > Android Auto**.
6. Open **Version** repeatedly until Android Auto developer mode is enabled, then use the overflow menu to **Start head unit server**.

The host Mac can then install RoadPulse and connect with the selected device serial:

```sh
/Users/bhavyarupani/Library/Android/sdk/platform-tools/adb -s DEVICE_SERIAL forward tcp:5277 tcp:5277
/Users/bhavyarupani/Library/Android/sdk/extras/google/auto/desktop-head-unit --adb=localhost:5277
```

Wait until Android Auto shows the persistent **Head unit server running** notification before launching DHU. The server may need a moment before port 5277 begins listening.

The full Android Auto host should then discover the exported `com.roadpulse.auto.car.RoadPulseCarAppService` navigation service and render its simulated route surface.
