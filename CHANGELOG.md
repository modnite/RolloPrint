# Changelog

All notable milestone releases for the **RolloPrint** application are documented in this file.

---

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
