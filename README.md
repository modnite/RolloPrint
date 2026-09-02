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

### `v1.1.0` — Display Cutout & Share Sheet Integration (September 2026)
- **Display Cutout / Notch Awareness**: Implemented edge-to-edge system bar and display cutout insets (`SHORT_EDGES` cutout mode) so UI elements avoid camera hole punches and gesture bars.
- **System Share Integration**: Added `ACTION_SEND` and `ACTION_VIEW` intent filters. Sharing a PDF label from WhatsApp, Email, or File Manager directly opens the print preview dialog in RolloPrint.
- **Untouched Core Engine**: Preserved all existing USB hardware streaming, autolaunch, and TSPL bit-packing logic without modification.

### `v1.0.0` — Initial Release (September 2026)
- **Core TSPL Engine**: Built expert native TSPL2 USB communication manager.
- **Rollo X1038 Integration**: Resolved thermal head polarity and bit-packing order for crisp label output.
- **PDF Label Renderer**: Added native PDF rendering pipeline scaling labels to exact 4x6 dimensions (816x1218 @ 203 DPI).
- **Print Preview Dialog**: Added visual confirmation preview dialog before hardware transfer.
- **USB Permission Auto-Handling**: BroadcastReceiver for USB connection events and permission prompts.
- **Live Diagnostics Console**: Built UI activity log view for streaming transfer statistics.

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
