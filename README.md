# RolloPrint 🖨️📦

[![Build & Publish APK](https://github.com/modnite/RolloPrint/actions/workflows/release.yml/badge.svg)](https://github.com/modnite/RolloPrint/actions/workflows/release.yml)

**RolloPrint** is a specialized, open-source Android application designed for direct USB thermal printing on the **Rollo X1038** thermal label printer. It provides direct, raw TSPL label streaming over USB Host mode without requiring cloud services, proprietary print servers, or third-party drivers.

---

## ✨ Features

- **Direct USB Host Control**: Communicates directly with Rollo hardware (`VID: 0x09C5`, `PID: 0x0588` / `2501:1416`) via Android USB Host API.
- **On-Device PDF Label Rendering**: Built-in PDF renderer converts standard 4x6 shipping labels (FedEx, UPS, USPS, DHL, Amazon) into crisp monochrome 203 DPI bitmaps (816 × 1218 px).
- **Custom TSPL Command Pipeline**: Generates raw TSPL (Taiwan Semiconductor Printer Language) bitmap streams (`SIZE 4.0,6.0`, `GAP 0,0`, `BITMAP`).
- **Hardware-Optimized Bit Packing**: Implements custom luminance-based bit-packing with inverted bit polarity tailored specifically for Rollo thermal heads.
- **Interactive Print Preview**: On-screen dialog previewing rendered labels before sending byte streams to the printer.
- **Live Activity Console**: In-app real-time log scrollview for hardware diagnostics, transfer status, and error tracing.
- **Office Network Print Server (Port 9100 + mDNS)**: Features a built-in TCP JetDirect/AppSocket print server and mDNS Zeroconf discovery (`_pdl-datastream._tcp.`). When toggled on, any Windows, Mac, or Linux PC on the local Wi-Fi network can discover and print directly to the Rollo thermal printer.
- **Strict Format Guard & Auto-Scaling**: Incoming network print jobs (PDF, PNG, JPG, or Text) are automatically validated, filtered, and rendered onto 4x6 203 DPI canvases. Invalid or garbage print jobs are safely rejected.
- **Display Cutout & Edge-to-Edge Awareness**: Seamlessly handles display cutouts (camera hole punches/notches) and system bar insets on modern phones, foldables, and Samsung DeX.
- **Direct PDF Sharing (WhatsApp, Email, Files)**: Supports Android System Share menu (`ACTION_SEND` & `ACTION_VIEW`). Sharing a PDF directly launches RolloPrint and opens the print preview dialog immediately.
- **Auto Hardware Detection**: Detects USB device attachment events (`USB_DEVICE_ATTACHED`) and prompts for Android USB permissions automatically.

---

## 🖨️ Hardware Compatibility

| Device | Vendor ID (VID) | Product ID (PID) | Status |
| :--- | :--- | :--- | :--- |
| **Rollo X1038 USB Thermal Printer** | `2501` (`0x09C5`) | `1416` (`0x0588`) | ✅ Fully Supported |

> *Note: Also compatible with standard TSPL-2 thermal label printers supporting USB Bulk endpoints.*

---

## 📐 Architecture & Workflow

```
[ PDF Document ] ➔ [ Android PdfRenderer ] ➔ [ 816x1218 ARGB Bitmap ]
                                                         │
                                                         ▼
[ Rollo X1038 USB ] ◄── [ USB Bulk Stream ] ◄── [ TSPL Bit-Packed Payload ]
```

1. **PDF Import**: Select any PDF shipping label using Android's system file picker (`Storage Access Framework`).
2. **Page Rendering**: Render page 0 into a centered 816 × 1218 bitmap matching 4" × 6" thermal dimensions at 203 DPI.
3. **Monochrome Conversion**: Calculate pixel luminance ($0.299R + 0.587G + 0.114B$) and pack into 1-bit bytes with inverted polarity (`1 = Paper/White`, `0 = Ink/Black`).
4. **TSPL Packaging**: Wrap binary bitmap array inside TSPL control headers:
   ```tspl
   SIZE 4.0,6.0
   GAP 0,0
   DIRECTION 1
   REFERENCE 0,0
   SET TEAR ON
   CLS
   BITMAP 0,0,102,1218,0,[BINARY_DATA]
   PRINT 1,1
   ```
5. **USB Bulk Transfer**: Chunk binary payload into 1024-byte packets over the USB OUT Bulk Endpoint.

---

## 🚀 Getting Started

### Prerequisites
- **Android Device**: Android 7.0 (API 24) or higher with USB OTG / USB Host support.
- **Cable**: USB OTG Adapter or USB-C to USB-B cable to connect Android device directly to the Rollo Printer.
- **Printer**: Rollo X1038 Direct Thermal Printer with 4x6 shipping labels loaded.

### Building & Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/modnite/RolloPrint.git
   ```
2. Open the project in **Android Studio**.
3. Build and install the APK onto your Android device:
   ```bash
   ./gradlew assembleDebug
   ```

### 📦 Automated Releases via GitHub Actions
Pre-compiled APKs are automatically generated and attached to GitHub Releases whenever a version tag is pushed:
```bash
git tag v1.0.0
git push origin v1.0.0
```
You can also manually trigger a build and publish from the **Actions** tab on GitHub (`Build and Publish Release APK` ➔ `Run workflow`).

---

## 📜 Version History

### `v1.0.13` — September 2, 2026 at 3:30 PM EDT (Driverless AirPrint & Auto-Print Pipeline)
- **Driverless AirPrint / IPP Everywhere Advertisement**: Configured mDNS TXT attributes (`pdl=application/pdf`, `kind=label,document`, `URF=CP1,SM1...`, `papercustom=4x6in`) on Port 631 and 9100. macOS, Windows, and Linux auto-detect `Rollo Thermal Printer` as a driverless AirPrint printer without prompting for vendor drivers!
- **Zero-Dialog Auto-Print Pipeline**: Network print jobs automatically bypass the UI print preview modal, ingesting PDFs/images, converting them to 816x1218 @ 203 DPI bitmaps, and streaming bit-packed TSPL directly to the Rollo USB hardware.

### `v1.0.12` — September 2, 2026 at 3:15 PM EDT (Rollo Mac Desktop Driver TSPL Direct Streaming)
- **Official Rollo Desktop Driver Stream Support**: Automatically detects native TSPL command streams (`SIZE`, `BITMAP`, `CLS`, `PRINT`) generated by the official Rollo macOS/Windows desktop drivers (`31,798 bytes`), streaming the raw TSPL payload directly to the Rollo USB bulk endpoint!

### `v1.0.11` — September 2, 2026 at 3:00 PM EDT (PostScript Document Size Unlocking & Universal Rendering)
- **Removed 100KB Size Limit Constraint**: Removed legacy 100KB document size check that was inadvertently rejecting 200KB+ PostScript / CUPS network print jobs.
- **Universal PostScript Document Renderer**: Parses and renders PostScript documents (`%!PS-Adobe`) of any size directly onto the 4x6 203 DPI thermal canvas for printing.

### `v1.0.10` — September 2, 2026 at 2:45 PM EDT (Selectable Activity Log & Probe Response Line)
- **Selectable Activity Log Console**: Enabled `textIsSelectable="true"` on the activity log TextView. Users can now long-press and copy/paste log text directly from the app console for easy troubleshooting.
- **PostScript Probe Socket Response**: Automatically returns query acknowledgment bytes (`False\r\nUnknown\r\n`) to CUPS when status probes arrive, allowing macOS CUPS to complete probe handshakes and proceed to streaming document PDF data.

### `v1.0.9` — September 2, 2026 at 2:30 PM EDT (CUPS PostScript Probe Filter & Clean macOS Printing)
- **CUPS PostScript Feature Query Probe Filter**: Detects and silently acknowledges macOS CUPS background PostScript status queries (`%!PS-Adobe-3.0 Query` / `%%?BeginFeatureQuery`). Prevents macOS hardware probes from printing feature query text onto thermal labels.
- **Clean Label Pipeline**: When printing actual documents/labels from macOS using `Generic PostScript Printer`, `RolloPrint` extracts the `%PDF-` document payload, renders the 4x6 203 DPI label bitmap, and streams TSPL directly to the Rollo X1038 printer.

### `v1.0.8` — September 2, 2026 at 2:15 PM EDT (ZPL Guard & Recommended macOS Driver Setup)
- **Zebra ZPL Guard**: Automatically detects raw Zebra ZPL driver commands (`^XA`, `^XZ`, `~DGR`) and logs a clear instruction advising the user to select **`Generic PostScript Printer`** as the driver on macOS / Windows.
- **Generic PostScript Pipeline**: When `Generic PostScript Printer` is chosen on macOS, CUPS outputs standard PDF/PostScript files which RolloPrint renders into high-resolution 4x6 203 DPI bitmaps for the Rollo X1038 thermal printer.

### `v1.0.7` — September 2, 2026 at 2:00 PM EDT (Multi-Port Listening & Universal Stream Extractor)
- **Multi-Port Listening (9100 RAW, 631 IPP, 515 LPD)**: Opens listening sockets on Ports 9100, 631 (IPP/AirPrint), and 515 (LPD). Eliminates connection timeout and queue stalling when macOS CUPS attempts IPP or LPD handshakes.
- **Universal Payload Extractor**: Scans incoming stream data for `%PDF-`, PNG, JPEG, and text headers even when wrapped in HTTP IPP, PostScript, or CUPS headers.
- **HTTP IPP Handshake Response**: Returns HTTP 200 OK headers to client queries, unblocking macOS/Windows print queues instantly.

### `v1.0.6` — September 2, 2026 at 1:40 PM EDT (Network Job Pipeline & Stream Unblocking)
- **Socket Unblocking (`soTimeout` + Document EOF Signatures)**: Added `socket.soTimeout = 1500` and `isCompleteDocument()` detection (`%%EOF`, `IEND`, `0xFF 0xD9`). Fixes network jobs remaining stuck in "Active" queues on macOS / Windows / Linux CUPS when client streams remain open.
- **Enhanced mDNS / Zeroconf Attributes**: Added full mDNS JetDirect TXT record attributes (`pdl`, `rp=raw`, `qtotal=1`, `usb_MFG=Rollo`, `usb_MDL=X1038`) for seamless auto-discovery across office networks.
- **UI Renamed**: Updated UI card title and log console messages to `"Print Server"`.

### `v1.0.5` — September 2, 2026 at 1:25 PM EDT (Proactive Permission Prompting)
- **Upfront Permission Prompts**: Proactively checks and requests all required runtime permissions (`POST_NOTIFICATIONS` for Android 13+, Storage permissions) in a batch upon first app launch (`onCreate`), eliminating permission gaps during print server activation or label sharing.

### `v1.0.4` — September 2, 2026 at 1:15 PM EDT (Print Server Stability & Zero-Crash Architecture)
- **Zero-Crash Network Server Architecture**: Replaced Foreground Service binding with direct background socket management, eliminating Android 14 `ForegroundServiceStartNotAllowedException` crashes and relaunch crash loops.
- **Port 9100 Bind Safety**: `reuseAddress` socket configuration prevents `BindException` on Wi-Fi reconnection or fast server toggled states.
- **Permanent Release Key Signed**: All APKs signed with permanent `rolloprint.jks` release certificate (v1, v2, v3, v4 schemes). *(Note: Uninstall any pre-1.0.3 debug build once to adopt the new release certificate; all future updates will install directly without uninstalling).*

### `v1.0.3` — September 2, 2026 at 1:00 PM EDT (Consistent Signing & Print Server Stability Fixes)
- **Consistent Release Key Signing**: Configured permanent release keystore (`rolloprint.jks`) for both debug and release builds with v1, v2, v3, and v4 signature schemes enabled. Fixes APK update/over-install failures and bypasses Google Play Protect warning flags.
- **Print Server Crash & Infinite Recursion Fix**: Resolved main thread recursive listener crash when toggling the network print server switch. Added socket `reuseAddress` flag and try-catch safety on server restart.
- **Android 13/14 Runtime Notification Permission**: Added `POST_NOTIFICATIONS` runtime permission handler for foreground service notifications.

### `v1.0.2` — September 2, 2026 at 12:45 PM EDT (Office Network Print Server)
- **Local Network Print Server (RAW Port 9100)**: Built-in TCP socket server allowing Windows, macOS, and Linux PCs on the local Wi-Fi network to send print jobs directly to the Rollo thermal printer.
- **mDNS / Zeroconf Discovery**: Advertises `Rollo Thermal Printer` (`_pdl-datastream._tcp.`) so office devices can discover the printer automatically.
- **Format Filter & Renderer**: Filters incoming network jobs (PDFs, Images, Text) and converts them to 816x1218 @ 203 DPI bitmaps before sending to the TSPL engine. Safely rejects incompatible formats.
- **Foreground Service**: Uses a background-resilient Android Foreground Service with status notifications to keep the network print server active when the phone is locked.

### `v1.0.1` — September 2, 2026 at 12:20 PM EDT (Display Cutout & Share Sheet Integration)
- **Display Cutout / Notch Awareness**: Implemented edge-to-edge system bar and display cutout insets (`SHORT_EDGES` cutout mode) so UI elements avoid camera hole punches and gesture bars.
- **System Share Integration**: Added `ACTION_SEND` and `ACTION_VIEW` intent filters. Sharing a PDF label from WhatsApp, Email, or File Manager directly opens the print preview dialog in RolloPrint.
- **Untouched Core Engine**: Preserved all existing USB hardware streaming, autolaunch, and TSPL bit-packing logic without modification.

### `v1.0.0` — September 2, 2026 at 11:35 AM EDT (Initial Release)
- **Core TSPL Engine**: Built expert native TSPL2 USB communication manager.
- **Rollo X1038 Integration**: Resolved thermal head polarity and bit-packing order for crisp label output.
- **PDF Label Renderer**: Added native PDF rendering pipeline scaling labels to exact 4x6 dimensions (816x1218 @ 203 DPI).
- **Print Preview Dialog**: Added visual confirmation preview dialog before hardware transfer.
- **USB Permission Auto-Handling**: BroadcastReceiver for USB connection events and permission prompts.
- **Live Diagnostics Console**: Built UI activity log view for streaming transfer statistics.

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
