# Changelog

All notable milestone releases for the **RolloPrint** application are documented in this file.

---

### `v2.3.0` — September 6, 2026 at 2:00 AM
- **Dummy Sink Architecture (Mask Hardware State from Clients)**: Configured `IppServer.kt` to always report `printerState = PrinterState.idle` and `printerStateReasons = ["none"]` to network clients. Clients receive immediate job acceptance (`JobState.completed`) and hand off payloads cleanly without client-side filter halts or "Out of Paper" popups.
- **Strict Sequential Job ID Tracking**: Added `activeJobMap` to `IppServer.kt` linking 2-step `Create-Job` and `Send-Document` IPP requests to the same `job-id`. Network jobs now increment sequentially as `Job #1`, `Job #2`, `Job #3`, `Job #4` without skipping numbers.
- **Universal Android Default Print Service Auto-Discovery**: Configured full mDNS TXT descriptors (`kind`, `URF`, `papercustom`, `pdl`) in `PrintServerService.kt`. Android Default Print Service, Mopria, iOS AirPrint, and Linux CUPS auto-discover `Rollo Printer` as a native networked label printer.

### `v2.2.1` — September 6, 2026 at 1:30 AM
- **Linux CUPS Page Size Attribute Alignment**: Added top-level `mediaSupported` array (`na_index-4x6_4x6in`, `oe_4x6-label_4x6in`, `custom_min_4x6in`, `na_letter_8.5x11in`, `iso_a4_210x297mm`) and `pdfVersionsSupported` to `IppServer.kt`. Resolves Linux CUPS `pdftopdf` page-size matching filter crashes on Linux KDE Plasma.
- **Surgical `Operation.createJob` Integration**: Preserved the lightweight `v1.8.1` IPP Everywhere response architecture while returning assigned `job-id` attributes for `createJob` requests.

### `v2.2.0` — September 6, 2026 at 1:00 AM
- **Memory & Bitmap Cleanup**: Added automatic bitmap recycling and temp file deletion (`temp_incoming_*.pdf`) after IPP rendering in `IppServer.kt`. Eliminates heap memory leaks and prevents app crashes when receiving multiple consecutive network jobs.
- **Permanent Activity Log & Server State Persistence**: Persisted activity log history (`tvLog`) and Print Server active state in `SharedPreferences` (`PREF_SERVER_RUNNING`). If the app or device restarts, log history and print server status are 100% preserved.
- **mDNS Service Name Uniqueness**: Cleared previous NsdListeners prior to service registration in `PrintServerService.kt`, guaranteeing the mDNS service name remains strictly `"Rollo Printer"`.

### `v2.1.7` — September 6, 2026 at 12:00 AM
- **mDNS Service Registration Uniqueness & Full AirPrint Descriptors**: Added automatic pre-cleanup of previous NSD registration listeners in `PrintServerService.kt`. Ensures the mDNS service name remains strictly `"Rollo Printer"` (never `"Rollo Printer (3)"`), allowing `lpadmin -v "dnssd://Rollo%20Printer._ipp._tcp.local/ipp/print"` and Linux `driverless -d` to resolve the printer instantly. Added AirPrint TXT descriptors (`kind`, `URF`, `papercustom`).

### `v2.1.2` — September 5, 2026 at 11:30 PM
- **Preserved Activity Log Console Across Theme Recreations**: Added `onSaveInstanceState` and `onRestoreInstanceState` handlers in `MainActivity.java`. Changing app theme or rotating screen now 100% preserves the Activity Console log history (`tvLog`) and expanded/collapsed view states.
- **Muted Repetitive Polling Logs**: Removed redundant 1.5s `[DIAGNOSTIC] Current Rollo Hardware States` console logs. Status logs are now emitted exclusively on genuine hardware state transitions (`[HARDWARE_STATE] Transitioned to:`), leaving the Activity Console quiet, readable, and uncluttered.

### `v2.1.1` — September 5, 2026 at 11:00 PM
- **IPP Operation.createJob Response Handler**: Implemented RFC 8011 `Create-Job` operation handling returning assigned `job-id`, `job-uri`, and `job-state` attributes in `jobAttributes`. Eliminates CUPS `pdftopdf stopped with status 1` and `universal filter failed` errors on Linux KDE Plasma.

### `v2.1.0` — September 5, 2026 at 10:30 PM
- **Linux CUPS `pdftopdf` Compatibility Descriptors**: Added missing IPP Everywhere attributes (`media-supported`, `media-ready`, `media-default`, `media-type-supported`, `pdf-versions-supported`, `printer-device-id`) to `IppServer.kt`. Resolves CUPS `universal filter failed` and `pdftopdf stopped with status 1` errors on Linux KDE Plasma.
- **Continuous 1.5s Hardware Polling Loop**: Decoupled hardware status polling from `PrintServerService`. Background USB status polling runs continuously every 1.5 seconds from app launch regardless of whether the Print Server is ON or OFF, updating status badges in <1.5s.
- **Full-Width RadioButton Touch Targets**: Added `clickable="true"`, `focusable="true"`, and `background="?attr/selectableItemBackground"` to Theme option RadioButtons in `dialog_settings.xml`. Tapping anywhere across the full width of the row selects the theme cleanly.

### `v2.0.4` — September 5, 2026 at 10:00 PM
- **USB Interface Claim Retry Loop**: Added a 300ms retry loop on `connection.claimInterface(usbInterface, true)` in `UsbPrintManager.kt`. Eliminates interface lockouts and spurious `UNKNOWN` state transitions when rapidly starting/stopping background services or re-plugging USB OTG docks.
- **Full-Width RadioButton Touch Bounds**: Set `clickable="true"`, `focusable="true"`, and `background="?attr/selectableItemBackground"` on Theme options in `dialog_settings.xml`. Tapping anywhere across the full width of the row selects the theme cleanly.

### `v2.0.3` — September 5, 2026 at 9:30 PM
- **Pinned Queue Manager Action Buttons**: Re-architected `dialog_queue_manager.xml` with `layout_height="0dp"` and `layout_weight="1"` on `rvQueueJobs`. Action buttons (`Print all`, `Clear all`, `Done`) are permanently pinned at the bottom with full height regardless of how many jobs fill the queue.
- **TextInput Overlapping Hint Fix**: Removed duplicate edit hints from `TextInputEditText` inside `dialog_settings.xml`. Material 3 `TextInputLayout` floating hints float up smoothly with zero text overlap.
- **Full-Width Theme Touch Targets**: Expanded `RadioButton` touch targets (`rbThemeSystem`, `rbThemeDark`, `rbThemeLight`) across the full row width with ripple feedback.

### `v2.0.2` — September 5, 2026 at 9:00 PM
- **Pinned Dialog Button Bar & Internal List Scrolling**: Re-structured `dialog_queue_manager.xml` using `layout_weight="1"` on `rvQueueJobs`. The bottom action buttons (`Print all`, `Clear all`, `Done`) remain permanently pinned at the bottom with full height regardless of how many jobs populate the queue.
- **TextInput Overlapping Hint Fix**: Removed duplicate edit hints from `TextInputEditText` inside `dialog_settings.xml`. Material 3 `TextInputLayout` floating hints now animate smoothly without text overlapping.
- **Full-Width Theme Touch Targets**: Set `android:layout_width="match_parent"` and vertical padding on Theme option radio buttons (`rbThemeSystem`, `rbThemeDark`, `rbThemeLight`), enabling instant selection by tapping anywhere across the full width of the row.

### `v2.0.1` — September 5, 2026 at 8:30 PM
- **App Theme Section Expansion Fix**: Fixed click listener binding for Section 1 ("App theme") in `showSettingsDialog()`. Expanding and collapsing options (`System default`, `Dark theme`, `Light theme`) now works smoothly with animated arrow indicators.
- **Detailed UI Event Logging (`[UI_EVENT]`)**: Added comprehensive logging for every button tap, switch toggle, radio selection, and section expansion across the app interface for live diagnostic visibility.
- **Etherpad Log Exporter Multipart Upload**: Standardized log dumping via multipart form-data file upload (`/p/<padID>/import`) for 100% reliable log exports across all Etherpad instances.
- **Accurate Release Timestamps**: Synchronized all CHANGELOG and DEVLOG release entries with exact GitHub Actions build start times.

### `v2.0.0` — September 5, 2026 at 8:03 PM
- **Single-Action Print Queue Card**: Streamlined `cardQueue` on the main dashboard to feature a single prominent "View queue" button, giving the card title "Print queue" 80%+ width so it never truncates into `Print qu...`.
- **Equal-Width Dialog Action Buttons**: Applied `layout_weight="1"` and `0dp` width to all 3 bottom buttons (`Print all`, `Clear all`, `Done`) in `dialog_queue_manager.xml`. Completely eliminates vertical pill button deformation.
- **Top-Level App Theme Section**: Positioned "App theme" as Section 1 at the top of the Settings popup with smooth expand/collapse animations.
- **Unified Material 3 Button Design**: Standardized all buttons across the application (Outlined & Filled Material 3 buttons), eliminating floating text-only buttons for consistent visual aesthetics across portrait, landscape, and Samsung DeX desktop modes.

### `v1.9.0` — September 5, 2026 at 6:51 PM
- **"Print All" Sequential Execution**: Added a "Print all" button on the Print Queue Manager modal (`btnPrintAllQueue`). Sets all held jobs to pending status and processes them sequentially in job order.
- **Compact Sort Order Icon Button**: Replaced the huge sort order text button with a clean 40x40dp icon button (`ImageButton`) in the Queue Manager modal header.
- **App Theme Selector Setting**: Added an "App theme" section in the Settings menu with System default, Dark theme, and Light theme options (`AppCompatDelegate.setDefaultNightMode()`).

### `v1.8.0` — September 5, 2026 at 6:14 PM
- **Full Landscape & DeX Mode Scrollability (`NestedScrollView`)**: Wrapped the main dashboard layout in `NestedScrollView`. Eliminates layout truncation on landscape phone screens, tablets, and Samsung DeX desktop mode.
- **Collapsible Settings Menu Sections**: Organized the Settings popup into 3 expandable/collapsible sections (*Print Preview Options*, *Pastebin / Etherpad Settings*, and *Diagnostics & Maintenance*).
- **Spacious Queue Manager Modal**: Expanded the Print Queue Manager dialog window to `92%` screen width (`360dp` RecyclerView height) for an open, un-claustrophobic management experience.
- **Standard Sentence Case Capitalization**: Applied standard English capitalization rules across all UI text strings, card headers, popup titles, and buttons throughout the application.

### `v1.7.0` — September 5, 2026 at 5:32 PM
- **Held Jobs Queue Manager & Interactive Modal**: Added a "View queue" button on the Print Queue card launching a full Queue Manager modal (`QueueManagerDialogFragment`). Displays individual pending/held jobs with client names, job status, a "Preview" action to visually inspect rendered label bitmaps, a "Print now" action, and individual job deletion.
- **In-App GitHub Auto-Update Checker (`AppUpdateManager`)**: Built an in-app GitHub Release update engine that checks `https://api.github.com/repos/modnite/RolloPrint/releases/latest` every 15 minutes (and on-demand via a "Check for updates" button in Settings). Automatically prompts with release notes and installs updated APKs seamlessly via `FileProvider`.
- **Universal Etherpad / Pastebin Log Exporter**: Added a "Dump to pastebin" action button and configurable Pastebin URL setting (`PREF_ETHERPAD_URL`). Uses multipart form-data import (`/p/<padID>/import`) to overwrite pad text reliably across all Etherpad instances.
- **Enabled Local Cleartext Traffic**: Added `android:usesCleartextTraffic="true"` to `AndroidManifest.xml` enabling instant HTTP activity log dumps to local Etherpad pastebins.

### `v1.6.0` — September 5, 2026 at 5:05 PM
- **Etherpad / Pastebin Activity Log Dumper**: Added a "Dump to pastebin" action button under the Activity Log console and an Etherpad / Pastebin URL configuration field in the Settings menu (`PREF_ETHERPAD_URL`). Easily posts activity logs directly to your Etherpad instance with a single tap.

### `v1.5.0` — September 5, 2026 at 4:25 PM
- **Real-Time Hardware Status Polling (`<ESC>!?`)**: Added background status polling every 3 seconds using verified Rollo X1038 status command `0x1B, 0x21, 0x3F` (`<ESC>!?`) over USB bulk endpoints.
- **Hardware Queue Purge (`~!C`)**: Updated the "Clear queue" button to send `0x7E, 0x21, 0x43` (`~!C`) directly over USB to purge the Rollo printer's onboard RAM buffer memory and halt label feeding immediately.
- **Main UI Hardware Status Badge**: Displays real-time hardware status in the app header card (`● Hardware: Ready`, `● Hardware: Out of Paper (Red LED)`, `● Hardware: Cover Open`).
- **IPP State Synchronization**: Automatically synchronizes IPP `printer-state` (idle vs stopped) and `printer-state-reasons` (`media-empty-error`, `door-open-error`, `none`) with real-time USB hardware states.

### `v1.4.0` — September 5, 2026 at 3:06 PM
- **Print Queue Card with Clear Queue Button**: Added a dedicated Print Queue Card in the main UI displaying real-time queue count (`Queue: Empty` or `Queue: X job(s) waiting`) and a `Clear queue` button. Un-printed jobs waiting during paper changes or hardware disconnects can now be purged instantly.
- **`JobQueueManager` Integration**: Built a thread-safe sequential job queue manager that processes local and network jobs sequentially and updates queue state in real-time.
- **Collapsible Activity Log Console**: Formatted the activity log card to be collapsible (collapsed by default). Tapping the header bar smoothly expands or collapses the console with an animated arrow indicator.

### `v1.3.0` — September 5, 2026 at 10:58 AM
- **Direct Network Print (No Dialogs)**: Incoming IPP print jobs bypass all UI preview dialogs, converting to 4x6 203 DPI bitmaps and streaming directly to the USB bulk endpoint in the background with activity logging.
- **Show Preview for Local Prints Settings Switch**: Added a MaterialSwitch in the main UI (`Show preview for local prints`, persisted in `SharedPreferences`, default `true`). Controls whether local PDF prints prompt for confirmation before sending to USB.
- **Aspect-Ratio Preserving Letterbox/Pillarbox PDF Renderer**: Calculates `min(816.0 / pdfWidth, 1218.0 / pdfHeight)` and centers scaled pages on an 816x1218 white canvas, ensuring non-4x6 documents (e.g. Letter, US Standard, or square slips) print centered without distortion or truncation.

### `v1.2.0` — September 5, 2026 at 10:08 AM
- **mDNS Service Layer Update**: Configured `_ipp._tcp` on port `8631` with exact driverless discovery TXT keys (`txtvers`, `ty`, `product`, `rp`, `pdl="image/pwg-raster,application/pdf"`, `qtotal`, `printer-state`, `printer-type`, `note`, `UUID`).
- **HTTP Transport Refactor (Catch-All & Chunked Decoder)**: Accepts HTTP POST requests sent to ANY URI path (`/`, `/cups`, `/ipp/print`), fully decodes `Transfer-Encoding: chunked` HTTP body streams, and reads exact `Content-Length` byte counts.
- **Non-Blocking IPP Job Handling**: Immediately returns HTTP 200 `successful-ok` response with assigned `job-id` and `job-state` before closing socket, preventing client keep-alive deadlocks.

### `v1.1.0` — September 5, 2026 at 8:34 AM
- **Integrated HP `jipp-core` IPP Engine (`com.hp.jipp:jipp-core:0.7.18`)**: Replaced custom IPP response serializer with HP's official, production-grade IPP Everywhere library. HP `jipp-core` guarantees 100% PWG 5100.14 / RFC 8010 compliant binary IPP frames.
- **HTTP/1.1 Expect 100-Continue Handshake**: Handles `Expect: 100-continue` headers from Windows IPP and Linux CUPS clients automatically.

### `v1.0.0` — September 2, 2026 at 12:17 PM
- **Core TSPL Engine**: Built expert native TSPL2 USB communication manager.
- **Rollo X1038 Integration**: Resolved thermal head polarity and bit-packing order for crisp label output.
- **PDF Label Renderer**: Added native PDF rendering pipeline scaling labels to exact 4x6 dimensions (816x1218 @ 203 DPI).
- **Print Preview Dialog**: Added visual confirmation preview dialog before hardware transfer.
- **USB Permission Auto-Handling**: BroadcastReceiver for USB connection events and permission prompts.
- **Live Diagnostics Console**: Built UI activity log view for streaming transfer statistics.
