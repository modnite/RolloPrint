# Changelog

All notable changes to the **RolloPrint** application are documented in this file.

---

### `v1.5.1` — September 4, 2026 at 2:00 AM
- **Simultaneous Job Progress & Hardware State Logging**: Logs the exact USB hardware state (`READY`, `OUT_OF_PAPER`, `HEAD_OPEN`) simultaneously alongside USB transfer progress and IPP job ingestion events for live diagnostic visibility.
- **Real-Time State Transition Logs**: Emits `[HARDWARE_STATE]` logs whenever the Rollo hardware transitions between states (e.g. `[READY]`, `[OUT_OF_PAPER]`).

### `v1.5.0` — September 4, 2026 at 1:00 AM
- **Real-Time Hardware Status Polling (`<ESC>!?`)**: Added background status polling every 3 seconds using verified Rollo X1038 status command `0x1B, 0x21, 0x3F` (`<ESC>!?`) over USB bulk endpoints.
- **Hardware Queue Purge (`~!C`)**: Updated the "Clear Queue" button to send `0x7E, 0x21, 0x43` (`~!C`) directly over USB to purge the Rollo printer's onboard RAM buffer memory and halt label feeding immediately.
- **Main UI Hardware Status Badge**: Displays real-time hardware status in the app header card (`● Hardware: Ready`, `● Hardware: Out of Paper (Red LED)`, `● Hardware: Cover Open`).
- **IPP State Synchronization**: Automatically synchronizes IPP `printer-state` (idle vs stopped) and `printer-state-reasons` (`media-empty-error`, `door-open-error`, `none`) with real-time USB hardware states.

### `v1.4.4` — September 3, 2026 at 11:59 PM
- **Pre-Payload Hardware Soft Reset (`~@`)**: Prepended TSPL soft reset byte sequence (`byteArrayOf(0x7E, 0x40, 0x0D, 0x0A)`) directly to the head of every TSPL bitmap payload. When thermal paper is loaded, `~@` instantly flushes any unexecuted page commands previously sitting in the Rollo printer's internal RAM.

### `v1.4.3` — September 3, 2026 at 11:30 PM
- **Pre-Transfer Out-of-Paper Hardware Guard (`~!S`)**: Added real-time TSPL status checks (`~!S`) in `UsbPrintManager` before USB bulk streaming. If paper is not loaded (Red LED flashing), `UsbPrintManager` halts transfer before any bytes enter printer RAM, holding the job safely in `JobQueueManager` memory.
- **Hardware Status Check Diagnostic Tool**: Added "Run Hardware Status Check" button in the Settings menu to test USB endpoint status reads and print real-time hardware status flags into the Activity Log.
- **Improved Bulk Read Reliability**: Added retry logic and increased timeout to 800ms for bulk IN endpoint reads on Samsung DeX USB-C docks.

### `v1.4.2` — September 3, 2026 at 11:00 PM
- **Real-Time Out-of-Paper Hardware Detection (`queryPrinterStatus`)**: Added TSPL status query (`~!S`) in `UsbPrintManager` before streaming bitmap payloads. If the printer is out of paper (Red LED), `UsbPrintManager` logs `"[HARDWARE] Out of Paper detected"` and holds the job in our app's queue without sending bytes to the Rollo USB endpoint.
- **Unified Network IPP Queue Integration**: Routed IPP network jobs through `JobQueueManager`. Incoming network jobs update the UI Print Queue Card in real-time (`Queue: X job(s) waiting`) and can be cleared using the "Clear Queue" button.

### `v1.4.1` — September 3, 2026 at 10:30 PM
- **Rollo Hardware Memory Buffer Purge (`clearHardwareBufferAsync`)**: Updated the "Clear Queue" button to send TSPL soft-reset and buffer purge commands (`~@`, `CLS`) directly to the Rollo printer over USB. This clears the printer's internal RAM buffer so old jobs do not print out when paper is reloaded.

### `v1.4.0` — September 3, 2026 at 10:00 PM
- **Print Queue Card with Clear Queue Button**: Added a dedicated Print Queue Card in the main UI displaying real-time queue count (`Queue: Empty` or `Queue: X job(s) waiting`) and a `Clear Queue` button. Un-printed jobs waiting during paper changes or hardware disconnects can now be purged instantly.
- **`JobQueueManager` Integration**: Built a thread-safe sequential job queue manager that processes local and network jobs sequentially and updates queue state in real-time.
- **Collapsible Activity Log Console**: Formatted the activity log card to be collapsible (collapsed by default). Tapping the header bar smoothly expands or collapses the console with an animated arrow indicator.
- **Default Network Preview Setting**: Confirmed `PREF_NETWORK_PREVIEW` defaults to `false` for new installs.

### `v1.3.5` — September 3, 2026 at 9:00 PM
- **Fixed Network Print Preview Callback Wiring**: Passed `onNetworkBitmapRendered` from `PrintServerService` to `IppServer`, making the "Preview Network Prints" setting fully functional when enabled.
- **CUPS `#PDF-BANNER` Test Page Rendering**: Added a native 4x6 thermal test page renderer (`createCupsTestPagePdf`) for Linux CUPS and KDE Plasma test pages, printing a crisp, professional **RolloPrint Test Page** with printer status, resolution, and timestamp instead of raw banner text code.

### `v1.3.4` — September 3, 2026 at 8:00 PM
- **GitHub Release CI Workflow Fix**: Updated `.github/workflows/release.yml` to build `assembleRelease` and attach `app/build/outputs/apk/release/*.apk` (wildcard matching `RolloPrint.apk`). Resolves the missing release binary issue on GitHub Releases.
- **Extracted UI Layout Strings into `strings.xml`**: Extracted hardcoded layout strings into string resources in `res/values/strings.xml`, resolving all Android Studio layout lint warnings.
- **Modernized File Picker API**: Replaced deprecated `startActivityForResult` in `MainActivity.java` with Android's modern `registerForActivityResult(ActivityResultContracts.StartActivityForResult)` API.
- **Cleaned Up Permissions & Codebase**: Removed deprecated `READ_EXTERNAL_STORAGE` permission from `AndroidManifest.xml` and removed unused legacy functions in `UsbPrintManager.kt`.
- **Gradle Version Catalog Integration**: Declared `com.hp.jipp:jipp-core:0.7.18` in `gradle/libs.versions.toml` catalog for clean dependency management.

### `v1.3.3` — September 3, 2026 at 7:30 PM
- **Replaced App Launcher Icon from `C:\Users\c\Pictures\RolloPrint.png`**: Updated all mipmap launcher icon folders (`mipmap-mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`) and round icons (`ic_launcher_round.png`) with the exact 66.5KB custom RolloPrint logo.
- **Renamed App Label & Output APK**: Updated application label in `AndroidManifest.xml` and `strings.xml` to `RolloPrint` and output binary filename to `RolloPrint.apk`.

### `v1.3.2` — September 3, 2026 at 7:00 PM
- **App Launcher Icon Updated**: Re-propagated custom Rollo Thermal Printer logo PNG across all mipmap density folders and generated round launcher icons (`ic_launcher_round.png`).
- **Clean UI Title Header**: Removed the image icon from the top title card and formatted the app title cleanly as a single word: **`RolloPrint`**.
- **Optimized Activity Log Console Height**: Adjusted activity log card height to `260dp` (a comfortable middle ground), ensuring ample log visibility while keeping the main action cards spacious.

### `v1.3.1` — September 3, 2026 at 6:00 PM
- **New App Icon**: Updated app launcher icon across all pixel densities using custom Rollo Thermal Printer artwork.
- **Settings Cog Wheel Dialog**: Added a Cog Wheel button in the UI header opening a dedicated Print Settings dialog with toggles for both "Preview Local Prints" (`PREF_LOCAL_PREVIEW`, default `true`) and "Preview Network Prints" (`PREF_NETWORK_PREVIEW`, default `false`).
- **UI Header App Title & Version Display**: Added a prominent header displaying "Rollo Print" and app version name (`v1.3.1`) directly in the main layout.
- **Compact Activity Log Box**: Reduced the activity log card height to `180dp` with smooth scrolling.

### `v1.3.0` — September 3, 2026 at 5:00 PM
- **Direct Network Print (No Dialogs)**: Incoming IPP print jobs bypass all UI preview dialogs, converting to 4x6 203 DPI bitmaps and streaming directly to the USB bulk endpoint in the background with activity logging.
- **Show Preview for Local Prints Settings Switch**: Added a MaterialSwitch in the main UI (`Show Preview for Local Prints`, persisted in `SharedPreferences`, default `true`). Controls whether local PDF prints prompt for confirmation before sending to USB.
- **Aspect-Ratio Preserving Letterbox/Pillarbox PDF Renderer**: Calculates `min(816.0 / pdfWidth, 1218.0 / pdfHeight)` and centers scaled pages on an 816x1218 white canvas, ensuring non-4x6 documents (e.g. Letter, US Standard, or square slips) print centered without distortion or truncation.
- **Media Expansion**: Added `"na_letter_8.5x11in"` to `media-supported` in `Get-Printer-Attributes` so desktop print queues do not reject jobs.

### `v1.2.0` — September 3, 2026 at 4:00 PM
- **mDNS Service Layer Update**: Configured `_ipp._tcp` on port `8631` with exact driverless discovery TXT keys (`txtvers`, `ty`, `product`, `rp`, `pdl="image/pwg-raster,application/pdf"`, `qtotal`, `printer-state`, `printer-type`, `note`, `UUID`).
- **HTTP Transport Refactor (Catch-All & Chunked Decoder)**:
  - Catch-All Routing: Accepts HTTP POST requests sent to ANY URI path (`/`, `/cups`, `/ipp/print`) and routes them all to the IPP request dispatcher.
  - Chunked Transfer Decoding: Fully decodes `Transfer-Encoding: chunked` HTTP body streams.
  - Strict Content-Length Framing: Reads exact `Content-Length` byte counts without passing trailing null bytes or socket padding to `jIPP`.
- **Non-Blocking IPP Job Handling**: Immediately returns HTTP 200 `successful-ok` response with assigned `job-id` and `job-state` before closing socket, preventing client keep-alive deadlocks.
- **UI Print Preview Modal for Network Jobs**: Saves incoming payloads to `temp_incoming.pdf`, renders page 1 to an 816x1218 @ 203 DPI bitmap, and displays the UI Print Preview modal for user confirmation before streaming bit-packed TSPL to the Rollo USB printer.

### `v1.1.2` — September 3, 2026 at 3:00 PM
- **Strict RFC 8011 Request ID & Version Echoing**: Reads 32-bit `request-id` and 16-bit `version-number` from incoming IPP requests and echoes them back in the IPP response headers.
- **Added Mandatory PWG-Raster Descriptors**: Added `image/pwg-raster` to `document-format-supported`, `pwg-raster-document-resolution-supported` (`203dpi`), `pwg-raster-document-sheet-back` (`normal`), and `pwg-raster-document-type-supported` (`black_1`).
- **Added Missing IPP 2.0 Attributes & Collection**: Added `copies-default` (`1`), `copies-supported` (`1-99`), `orientation-requested-default` (`3`), `orientation-requested-supported` (`[3]`), `print-quality-default` (`4`), `print-quality-supported` (`[4]`), `sides-default` (`one-sided`), `sides-supported` (`["one-sided"]`), `finishings-default` (`3`), `finishings-supported` (`[3]`), `output-bin-default` (`face-down`), `output-bin-supported` (`["face-down"]`), `printer-more-info`, `printer-uuid`, and `media-col-database` collection (`x-dimension=10160`, `y-dimension=15240`).

### `v1.1.1` — September 3, 2026 at 2:00 PM
- **Integrated HP `jipp-core` IPP Engine (`com.hp.jipp:jipp-core:0.7.18`)**: Replaced custom IPP response serializer with HP's official, production-grade IPP Everywhere library. HP `jipp-core` guarantees 100% PWG 5100.14 / RFC 8010 compliant binary IPP frames.
- **HTTP/1.1 Expect 100-Continue Handshake**: Handles `Expect: 100-continue` headers from Windows IPP and Linux CUPS clients automatically.
- **Driverless Network Print Preview**: When network jobs arrive, `IppServer` triggers the UI Print Preview modal on the Android device so the user can inspect the converted 4x6 label layout before hardware transfer.

### `v1.1.0` — September 3, 2026 at 1:00 PM
- **RFC 8010 Compliant IPP Attribute Encodings**: Fixed IPP multi-value attribute encoding (`name-length = 0` for additional values). Resolves the "That didn't work" error on Windows and "This feature is not available" error on Linux CUPS when adding the printer.
- **Temporary Network Print Preview**: Configured `IppServer` to trigger the app's `PrintPreviewDialogFragment` when network jobs arrive, allowing visual inspection of the rendered 4x6 label on the phone before confirming hardware streaming.
- **Full IPP Attribute Group Coverage**: Added required IPP Everywhere attributes (`charset-configured`, `natural-language-configured`, `printer-is-accepting-jobs`, `operations-supported`, `queued-job-count`).

### `v1.0.20` — September 3, 2026 at 11:30 AM
- **Driverless IPP Everywhere Server Refactor**: Replaced JetDirect/raw TCP listeners with an embedded HTTP/1.1 IPP server listening on port `8631`.
- **mDNS Zeroconf Advertising**: Publishes `_ipp._tcp` on port `8631` with `rp="ipp/print"`, `pdl="application/pdf"`, `note="Rollo Thermal 4x6"`, `printer-type="0x4000000"`, `UUID="e5b02130-1c4b-483b-9a99-000000000001"`.
- **Standard IPP 2.0 Operations**: Full handling of `Get-Printer-Attributes`, `Validate-Job`, `Print-Job` / `Send-Document`, and `Get-Job-Attributes`.
- **Verified Rollo TSPL Bitmap Packaging**: Converts incoming PDF pages into 816x1218 @ 203 DPI bitmaps and packages them into the strict Rollo TSPL structure (`SIZE 102 mm,153 mm`, `GAP 3 mm,0 mm`, `DENSITY 8`, `SPEED 6`, `BITMAP 0,0,102,1218,1,[mono_bytes]`).
- **Power & Service Management**: Foreground Service with `WakeLock` (`PARTIAL_WAKE_LOCK`) and `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) to prevent Wi-Fi sleep and connection drops when phone screen is locked.

### `v1.0.19` — September 3, 2026 at 10:00 AM
- **Driverless AirPrint mDNS Auto-Selection**: Advertised `_ipp._tcp.` on Port 631 with driverless AirPrint TXT record keys (`pdl=application/pdf`, `kind=label,document`, `URF=CP1,SM1...`, `papercustom=4x6in`). macOS, Windows, and Linux auto-select AirPrint / IPP Everywhere automatically without showing the "Choose a Driver..." prompt.
- **Pure PDF AirPrint Ingestion**: When macOS prints via AirPrint, CUPS sends 100% pure PDF (`%PDF-`). The server ingests the PDF directly into `renderPdfToBitmap` and streams vector graphics to the Rollo X1038 thermal printer without PostScript conversion artifacts.

### `v1.0.18` — September 3, 2026 at 9:30 AM
- **Plug-and-Play JetDirect Auto-Discovery**: Advertised as standard `Generic Label Printer` over mDNS (`_pdl-datastream._tcp.`). macOS, Windows, and Linux auto-detect the printer without prompting for vendor drivers or PPDs.
- **Universal Local PDF Ingestion Pipeline**: Rewrote `NetworkPrintServer` to convert ALL incoming network payloads (PDF, PNG, JPEG, Text, PostScript) into 4x6 203 DPI PDF files stored in app cache, then ingesting them directly into the working local PDF rendering pipeline (`renderPdfToBitmap` -> `printBitmapAsync`), bypassing the preview dialog completely for network jobs.
- **Decoupled Server State**: The network print server operates independently of USB hardware connection state. Jobs are converted and queued cleanly even when the phone is un-docked.

### `v1.0.17` — September 3, 2026 at 9:00 AM
- **TSPL Stream Over-Eagerness Fix**: Replaced `||` with `&&` in TSPL stream detection (`SIZE` && `GAP` && `PRINT`). Fixes bug where CUPS PostScript documents containing the word "print" inside `userdict` definitions were incorrectly ingested as raw TSPL streams instead of being rendered to labels.

### `v1.0.16` — September 2, 2026 at 11:20 PM
- **CUPS Probe Reply Formatting Fix**: Fixed carriage return line endings in CUPS PostScript probe replies (`False\nUnknown\n`) preventing macOS from finalizing print handshakes.
- **Strict TSPL Stream Detection**: Tightened native TSPL command string detection (`CLS `) to prevent false-positive matches on CUPS PostScript variable names (e.g. `Classes`).

### `v1.0.15` — September 2, 2026 at 10:45 PM
- **Startup App Version Log**: Activity log console now displays the exact build version on app launch (`Rollo X1038 Utility v1.0.15 Loaded`).
- **Explicit Log Prefixes (`[LOCAL]` vs `[NETWORK]`)**: Activity log console messages now clearly distinguish between local print actions (`[LOCAL]`) and network print server events (`[NETWORK]`).
- **IPP Attribute Query Filter**: Added explicit filter for IPP attribute/capability queries without embedded document data, preventing IPP attribute strings from being printed onto labels.
- **Landscape Auto-Rotation & Proportional 4x6 Canvas Scaling**: Added 90-degree auto-rotation and proportional scaling for landscape images and PDF pages, ensuring full-page printing without margin cutoffs.

### `v1.0.14` — September 2, 2026 at 9:45 PM
- **PostScript Probe Filter Isolation**: Added strict payload size check (`data.size < 5000`) on probe filtering. Prevents 200KB+ PostScript print documents (such as your 261,942 byte print job) from being misidentified as status probes, allowing the server to process and print full CUPS PostScript documents immediately.

### `v1.0.13` — September 2, 2026 at 9:20 PM
- **Driverless AirPrint / IPP Everywhere Advertisement**: Configured mDNS TXT attributes (`pdl=application/pdf`, `kind=label,document`, `URF=CP1,SM1...`, `papercustom=4x6in`) on Port 631 and 9100. macOS, Windows, and Linux auto-detect `Rollo Thermal Printer` as a driverless AirPrint printer without prompting for vendor drivers.
- **Zero-Dialog Auto-Print Pipeline**: Network print jobs automatically bypass the UI print preview modal, ingesting PDFs/images, converting them to 816x1218 @ 203 DPI bitmaps, and streaming bit-packed TSPL directly to the Rollo USB hardware.

### `v1.0.12` — September 2, 2026 at 3:27 PM
- **Official Rollo Desktop Driver Stream Support**: Automatically detects native TSPL command streams (`SIZE`, `BITMAP`, `CLS`, `PRINT`) generated by the official Rollo macOS/Windows desktop drivers (`31,798 bytes`), streaming the raw TSPL payload directly to the Rollo USB bulk endpoint.

### `v1.0.11` — September 2, 2026 at 3:02 PM
- **Removed 100KB Size Limit Constraint**: Removed legacy 100KB document size check that was inadvertently rejecting 200KB+ PostScript / CUPS network print jobs.
- **Universal PostScript Document Renderer**: Parses and renders PostScript documents (`%!PS-Adobe`) of any size directly onto the 4x6 203 DPI thermal canvas for printing.

### `v1.0.10` — September 2, 2026 at 2:37 PM
- **Selectable Activity Log Console**: Enabled `textIsSelectable="true"` on the activity log TextView. Users can now long-press and copy/paste log text directly from the app console for easy troubleshooting.
- **PostScript Probe Socket Response**: Automatically returns query acknowledgment bytes (`False\r\nUnknown\r\n`) to CUPS when status probes arrive, allowing macOS CUPS to complete probe handshakes and proceed to streaming document PDF data.

### `v1.0.9` — September 2, 2026 at 2:13 PM
- **CUPS PostScript Feature Query Probe Filter**: Detects and silently acknowledges macOS CUPS background PostScript status queries (`%!PS-Adobe-3.0 Query` / `%%?BeginFeatureQuery`). Prevents macOS hardware probes from printing feature query text onto thermal labels.
- **Clean Label Pipeline**: When printing actual documents/labels from macOS using `Generic PostScript Printer`, `RolloPrint` extracts the `%PDF-` document payload, renders the 4x6 203 DPI label bitmap, and streams TSPL directly to the Rollo X1038 printer.

### `v1.0.8` — September 2, 2026 at 1:59 PM
- **Zebra ZPL Guard**: Automatically detects raw Zebra ZPL driver commands (`^XA`, `^XZ`, `~DGR`) and logs a clear instruction advising the user to select **`Generic PostScript Printer`** as the driver on macOS / Windows.
- **Generic PostScript Pipeline**: When `Generic PostScript Printer` is chosen on macOS, CUPS outputs standard PDF/PostScript files which RolloPrint renders into high-resolution 4x6 203 DPI bitmaps for the Rollo X1038 thermal printer.

### `v1.0.7` — September 2, 2026 at 1:42 PM
- **Multi-Port Listening (9100 RAW, 631 IPP, 515 LPD)**: Opens listening sockets on Ports 9100, 631 (IPP/AirPrint), and 515 (LPD). Eliminates connection timeout and queue stalling when macOS CUPS attempts IPP or LPD handshakes.
- **Universal Payload Extractor**: Scans incoming stream data for `%PDF-`, PNG, JPEG, and text headers even when wrapped in HTTP IPP, PostScript, or CUPS headers.
- **HTTP IPP Handshake Response**: Returns HTTP 200 OK headers to client queries, unblocking macOS/Windows print queues instantly.

### `v1.0.6` — September 2, 2026 at 1:32 PM
- **Socket Unblocking (`soTimeout` + Document EOF Signatures)**: Added `socket.soTimeout = 1500` and `isCompleteDocument()` detection (`%%EOF`, `IEND`, `0xFF 0xD9`). Fixes network jobs remaining stuck in "Active" queues on macOS / Windows / Linux CUPS when client streams remain open.
- **Enhanced mDNS / Zeroconf Attributes**: Added full mDNS JetDirect TXT record attributes (`pdl`, `rp=raw`, `qtotal=1`, `usb_MFG=Rollo`, `usb_MDL=X1038`) for seamless auto-discovery across office networks.
- **UI Renamed**: Updated UI card title and log console messages to `"Print Server"`.

### `v1.0.5` — September 2, 2026 at 1:20 PM
- **Upfront Permission Prompts**: Proactively checks and requests all required runtime permissions (`POST_NOTIFICATIONS` for Android 13+, Storage permissions) in a batch upon first app launch (`onCreate`), eliminating permission gaps during print server activation or label sharing.

### `v1.0.4` — September 2, 2026 at 1:18 PM
- **Zero-Crash Network Server Architecture**: Replaced Foreground Service binding with direct background socket management, eliminating Android 14 `ForegroundServiceStartNotAllowedException` crashes and relaunch crash loops.
- **Port 9100 Bind Safety**: `reuseAddress` socket configuration prevents `BindException` on Wi-Fi reconnection or fast server toggled states.
- **Permanent Release Key Signed**: All APKs signed with permanent `rolloprint.jks` release certificate (v1, v2, v3, v4 schemes). *(Note: Uninstall any pre-1.0.3 debug build once to adopt the new release certificate; all future updates will install directly without uninstalling).*

### `v1.0.3` — September 2, 2026 at 1:07 PM
- **Consistent Release Key Signing**: Configured permanent release keystore (`rolloprint.jks`) for both debug and release builds with v1, v2, v3, and v4 signature schemes enabled. Fixes APK update/over-install failures and bypasses Google Play Protect warning flags.
- **Print Server Crash & Infinite Recursion Fix**: Resolved main thread recursive listener crash when toggling the network print server switch. Added socket `reuseAddress` flag and try-catch safety on server restart.
- **Android 13/14 Runtime Notification Permission**: Added `POST_NOTIFICATIONS` runtime permission handler for foreground service notifications.

### `v1.0.2` — September 2, 2026 at 12:56 PM
- **Local Network Print Server (RAW Port 9100)**: Built-in TCP socket server allowing Windows, macOS, and Linux PCs on the local Wi-Fi network to send print jobs directly to the Rollo thermal printer.
- **mDNS / Zeroconf Discovery**: Advertises `Rollo Thermal Printer` (`_pdl-datastream._tcp.`) so office devices can discover the printer automatically.
- **Format Filter & Renderer**: Filters incoming network jobs (PDFs, Images, Text) and converts them to 816x1218 @ 203 DPI bitmaps before sending to the TSPL engine. Safely rejects incompatible formats.
- **Foreground Service**: Uses a background-resilient Android Foreground Service with status notifications to keep the network print server active when the phone is locked.

### `v1.0.1` — September 2, 2026 at 12:35 PM
- **Display Cutout / Notch Awareness**: Implemented edge-to-edge system bar and display cutout insets (`SHORT_EDGES` cutout mode) so UI elements avoid camera hole punches and gesture bars.
- **System Share Integration**: Added `ACTION_SEND` and `ACTION_VIEW` intent filters. Sharing a PDF label from WhatsApp, Email, or File Manager directly opens the print preview dialog in RolloPrint.
- **Untouched Core Engine**: Preserved all existing USB hardware streaming, autolaunch, and TSPL bit-packing logic without modification.

### `v1.0.0` — September 2, 2026 at 12:18 PM
- **Core TSPL Engine**: Built expert native TSPL2 USB communication manager.
- **Rollo X1038 Integration**: Resolved thermal head polarity and bit-packing order for crisp label output.
- **PDF Label Renderer**: Added native PDF rendering pipeline scaling labels to exact 4x6 dimensions (816x1218 @ 203 DPI).
- **Print Preview Dialog**: Added visual confirmation preview dialog before hardware transfer.
- **USB Permission Auto-Handling**: BroadcastReceiver for USB connection events and permission prompts.
- **Live Diagnostics Console**: Built UI activity log view for streaming transfer statistics.
