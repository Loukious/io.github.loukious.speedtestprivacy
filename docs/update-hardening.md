# Compatibility across minor Speedtest updates

The module now resolves selected app hooks by their behavior and structure instead of depending on the current obfuscated names. This can survive class or method renaming when the relevant strings, signatures, and callback contracts remain intact. It does not establish compatibility with an untested Speedtest release.

## Startup and discovery

Stable Android, Java, and preserved SDK entry points install at `onPackageReady`. The module then intercepts Speedtest's `Application.attach` before providers and `Application.onCreate`, obtains the target Context, disables automatic collection metadata, and prepares discovery synchronously. Once discovery finishes, it installs the validated app hooks and marks installation complete. Facebook's delayed secondary-DEX workflow is not used: early Speedtest providers can otherwise initialize collection before the hooks exist.

`DiscoveryEngine` scans executable DEX in the currently installed base APK and splits using DexKit 2.2.0. Every semantic target supplies a DEX query and a separate reflection validator. Queries combine exact string constants, signatures, modifiers, field usage, and caller/callee relationships as appropriate. Reflection resolves against the running Speedtest classloader without initializing classes. A target must produce one validated method; empty, ambiguous, invalid, or unresolved candidates produce an explicit failure report rather than a guessed hook.

The current registry has 16 keys:

| Key | Purpose | Critical |
| --- | --- | --- |
| `ads.state.compute` | Advertising state calculator | Yes |
| `ads.state.get` | Published advertising state | Yes |
| `ads.banner.allowed` | Banner eligibility policy | Yes |
| `ads.amazon.initialize` | App Amazon advertising initializer | Yes |
| `ads.prebid.initialize` | App Prebid advertising initializer | Yes |
| `ads.prebid.fetch` | Prebid demand request and completion contract | Yes |
| `ads.prebid.sdkInitialize` | Prebid SDK initialization and completion contract | Yes |
| `identifiers.config.enrich` | Configuration advertising identifier collector | Yes |
| `identifiers.config.params` | Final configuration parameter map | Yes |
| `identifiers.report.allowField` | Report field inclusion policy | Yes |
| `identifiers.report.allowPath` | Report path inclusion policy | Yes |
| `identifiers.report.serialize` | Final report batch serializer | Yes |
| `permissions.fixedDenial` | Permission result Back classification gate | Yes |
| `results.legacyUpload` | Legacy uploader after local result saving finishes | Yes |
| `results.batchUpload` | Explicit reporting queue processing | No |
| `results.batchAutomatic` | Automatic reporting queue processing | No |

For example, the permission manager owner is anchored by both `permissionsAccounting` and `permissionsManager`, then its unique private instance `()boolean` gate is validated. The legacy uploader is anchored by local-save and extended-report error strings; its ID setter, two result-model fields, absent-ID sentinel, completion flag, and callback contract must satisfy the required structure. Callback and completed-Single helpers resolve from their types and signatures, so renamed callback methods and RxJava classes do not require historical names.

## Identity and cache

`HostIdentity` hashes the actual bytes of every installed base/split APK with SHA-256 and combines their digests, ordered source positions, and lengths into one APK-set fingerprint. The long version code is retained without narrowing it to an integer. The digest cache reuses a prior digest only when the canonical source path, length, and modification time are unchanged. Metadata is checked before and after reading and again across the complete APK set; a concurrent source change prevents a trusted identity from being produced. This optimization assumes unchanged source metadata means unchanged installed bytes.

The method cache is keyed by the APK-set fingerprint, the module application ID and version name/code, and independent resolver schema `2`. The schema change invalidates snapshots produced before owner-definition provenance checks existed. Each entry stores the declaring owner, method name, every parameter type, return type, and modifiers. Cache restoration resolves the complete descriptor and reruns the target validator. Covariant-return bridges are distinguished by their return types and modifiers. A stale member, changed target set, changed module/schema, or failed validator invalidates the complete snapshot and triggers discovery.

Only a complete positive snapshot is written. Missing, invalid, query-failed, unresolved, and ambiguous targets are not negative-cached; they are retried on later startup. Consequently, an absent optional target can also prevent a full cache hit. Both cache files live in Speedtest's private cache directory, and same-directory atomic replacement prevents readers from seeing a partial write. The cache contains discovery metadata, not user privacy preferences; settings remain in the framework remote-preferences channel.

## Ambiguity and remaining raw hooks

Distinct validated candidates are rejected. Identical full descriptors returned from different executable APK sources are also rejected: equal names and signatures in overlapping base/split definitions do not prove that both describe the runtime class chosen by Android. Source provenance is retained for this check.

Before reflection, discovery also looks up every candidate's declaring owner in the actual class definitions of every executable APK. This catches an unmarked base class shadowing an identically named marked split class, even when the semantic query returns only the split method. More than one owner definition is rejected, including duplicate definitions across DEX files inside one APK. An exact composite class matcher avoids DexKit's single-owner lookup shortcut; definition lookups are reused across targets during the scan. A failed lookup, absent owner, or unique definition in a source different from the semantic match produces `QUERY_FAILED`. Repeated semantic query hits can be deduplicated, but separate owner definitions remain ambiguous.

The installer also refuses a method claimed by a different semantic role, feature, or exception mode rather than silently treating the second protection as installed.

Some app and SDK hooks still use reviewed raw names. Those remaining volatile names are enabled only for the exact analyzed profile:

- Base APK SHA-256: `de1a74960d4377190a88a944a348d9398c237cf65aae6059bdec45a1b4914c1f`.
- Speedtest version code: `265370`.
- No additional executable DEX in installed splits.

The profile gate covers remaining named `com.ookla` hooks, Google internal classes, short obfuscated Firebase owners/methods, and short Prebid methods. Semantic discoveries do not require this profile, but they must pass their own validators. Stable platform and preserved public SDK guards remain available on an unknown build. Version-specific omissions are reported, and an unknown profile remains partial compatibility even if its semantic targets resolve.

Upload suppression retains independent URL, DNS, OkHttp, and Mapbox HTTP protections. Expected network denials use `PASSTHROUGH` exception mode so Vector cannot interpret them as a broken hook and execute the original upload. The legacy replacement preserves the existing absent remote ID and invokes the original completion callback once; it does not invent a remote result or hosted sharing record. If graceful app-specific completion cannot be resolved, independent endpoint guards remain available, but equivalent UI behavior on an unknown build requires validation.

## Runtime status

Speedtest keeps diagnostics in its private files. While the module settings UI is open, it sends a request broadcast targeted to Speedtest with a module-owned `RuntimeStatusEndpoint` Binder. The injected code registers a dynamic receiver inside Speedtest; Android requires request senders to hold the module-declared signature permission. Speedtest replies directly through the supplied Binder instead of resolving a module provider or service. Speedtest needs no manifest changes, package-visibility query for the module, additional permission grant, or root service. The module's own manifest declares visibility of Speedtest for its status and version checks.

The Binder endpoint captures `Binder.getCallingUid()` inside `onTransact`, before parsing application payloads, and compares it with Speedtest's current installed UID. It requires a synchronous transaction, a parcel no larger than 129 KiB, the exact interface token, wire version and module version, a report no longer than 64 Ki characters, and no trailing parcel data. Parsed JSON must also identify Speedtest and the current module application ID and version. Accepted reports update the module's local status preferences and receive an acknowledgment; reports cannot change protection switches. This authenticates the reporting app UID; it is not an independent network monitor or an attestation of every possible data path.

The settings UI checks the reported module application ID/version, host version, and current base/split path/length/mtime metadata before presenting status. A stale report asks the user to reopen or restart Speedtest. Status includes discovered targets, actual installation records, required stable guards, errors, skipped version-specific hooks, and runtime checks. Critical targets and guards are evaluated for their enabled protection switches.

Expected checks include optional permission denial, Android-ID redaction, Java report URL denial, legacy URL denial, Mapbox report URL denial, and Mapbox native collection state where the corresponding feature is enabled. URL checks use `openConnection` without connecting to the network. They verify hook dispatch at that API boundary; they do not prove absence of all traffic. Native collection state is checked through the shipped Mapbox API.

Status remains `initializing` until installation and expected checks finish. Pending checks have a 12-second timer; if they remain pending afterward, status becomes `partial`. A missing enabled critical target/guard, runtime error, failed check, unknown profile, or skipped version-specific family also produces `partial`. `matched` means the recognized profile's enabled installation and checks met these conditions, not that every internal or native collection path has been audited.

## Validation status and limits

The final hardening build passed all 50 JVM tests and all 11 on-device fixture cases, including the production Binder endpoint’s rejection of a forged target UID. An additional live case verified the renamed module’s protected broadcast and authenticated Binder reply from Speedtest. The real cross-process status reply was accepted and the module UI displayed `Speedtest 7.1.4 · protections matched`, with the framework connected and all five switches enabled. Lint completed with zero errors and six warnings. Final discovery took 711 ms from a fresh method cache and 8 ms with validated cache reuse. All 16 semantic targets installed, all seven runtime checks passed, and 207 hooks were active with no recorded errors. Map rendering was checked again and the real native collection getter remained disabled.

JVM coverage includes identity changes, digest reuse, concurrent APK mutation, cache return/staticity/schema/module invalidation, required validator dependencies, covariant bridges, missing targets, and source/owner ambiguity. Device fixtures exercise renamed class/method equivalents, warm-cache reuse, changed APK bytes with unchanged version labels, distinct ambiguous matches, duplicate full descriptors across executable base/split sources, and an unmarked base owner shadowing a marked split owner. Ambiguous fixtures remain uncached on repeated preparation.

A functional Speedtest run completed and its local result was retained with absent remote ID `-1`. Mapbox's native collection flag remained `false` after map use. The final status IPC was checked separately through the module settings screen; see the [validation summary](validation.md).

Minor updates can still remove anchors, inline or merge methods, change callback/state contracts, move related definitions across DEX sources, upgrade SDK internals, or introduce new collection endpoints. The module refuses unsupported targets and reports the limitation instead of replacing unrelated methods. Semantic discovery and known endpoint filtering are bounded coverage, not proof of compatibility with every future version.

Keep sensitive Android permissions denied at the OS level: app-facing permission hooks do not revoke existing grants or cover every native/Binder access path. Internet/network access remains available for actual speed tests; test servers necessarily receive the connecting IP address and test traffic. Hosted sharing, account synchronization, coverage sampling, and other optional features can be unavailable while their protections are enabled.
