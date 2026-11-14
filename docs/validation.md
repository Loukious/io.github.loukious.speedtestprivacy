# Validation

Version 1.1.0 (versionCode 3) was validated against Speedtest 7.1.4 (265370) on Android 17/API 37 with Vector-SR 1.2 (3131).

## Build and discovery

- All 50 JVM unit tests passed.
- All 11 standalone Android instrumentation cases passed; the optional twelfth case verified the live Speedtest Binder reply for `io.github.loukious.speedtestprivacy`.
- Android lint reported zero errors and six warnings.
- All 16 semantic targets resolved and installed; 207 hooks were active without recorded runtime errors.
- A fresh method-cache scan took 711 ms; validated cache reuse took 8 ms.

The fixtures test class and method renaming, unchanged version labels with changed APK bytes, ambiguous methods, overlapping executable splits, an unmarked base class shadowing a marked split class, and rejection of a forged status report. Ambiguous discoveries expose no methods and are retried rather than cached.

## Device behavior

A speed test completed with both download and upload completion flags set. Its result remained in local history with absent remote ID `-1`. Sensitive Android permission grants remained denied.

All seven runtime checks passed: permission denial, Android ID redaction, advertising ID redaction, Java report URL denial, legacy URL denial, Mapbox reporting URL denial, and Mapbox native collection disabled. URL probes use `openConnection` without connecting to the network.

The settings screen received the actual cross-process Binder reply and displayed `Speedtest 7.1.4 · protections matched`, with the framework connected and all five controls enabled. The production endpoint rejected a report sent from the module UID while claiming Speedtest's UID.

Map rendering remained available, and the real Mapbox native collection getter remained disabled after map use. The module executes the SDK's actual native disable operation and retains independent HTTP guards for its telemetry and SDK-session routes.

## Scope

These checks establish the inspected behavior for the verified APK. They are not an exhaustive packet or syscall audit. Untested APKs, changed SDK contracts, removed anchors, and newly introduced collection routes still require validation; see [update hardening](update-hardening.md).
