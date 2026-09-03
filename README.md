# RolloPrint

[![Build & Publish APK](https://github.com/modnite/RolloPrint/actions/workflows/release.yml/badge.svg)](https://github.com/modnite/RolloPrint/actions/workflows/release.yml)

**RolloPrint** is a specialized, open-source Android app that turns your Android device into a direct driver for the **Rollo X1038** thermal label printer and an office-wide wireless print server.

---

## Why RolloPrint Was Created

This project was born out of a real-world office frustration.

In our office, the Rollo thermal printer was originally plugged into our boss's Windows computer. When that PC went down, printing shipping labels ground to a halt. Whenever a label arrived via WhatsApp from our boss, I felt completely useless not being able to print it directly. Getting a label printed required someone in the office to physically plug the printer into their MacBook, log into WhatsApp Web, download the PDF, and print it.

I rely heavily on my **Samsung Galaxy S23 paired with Samsung DeX and a docking station**. While my phone isn't docked 100% of the time, whenever it is docked at my desk, I wanted a setup where it could connect directly to the Rollo printer over USB and host a wireless print server for the whole office.

Now with **RolloPrint**:
- Whenever my phone is docked, it connects to the Rollo printer via USB and activates the print server.
- Anyone in the office can share a PDF shipping label directly from **WhatsApp** on their phone or print wirelessly over Wi-Fi from their **MacBook, Windows PC, or Linux machine**.
- No swapping cables, no downloading files on WhatsApp Web, and no driver installations required!

While this is admittedly a very niche use-case, I wanted to publish **RolloPrint** open-source just in case anyone else ever needs to control or share this exact Rollo X1038 thermal printer using an Android device, whether locally over USB or wirelessly across a network.

---

## What RolloPrint Does

- **Direct USB Printing**: Plug your Android device into the Rollo printer via USB OTG or a USB-C dock to print 4x6 labels instantly.
- **Built-in Network Print Server**: Toggle on the Print Server, and any Mac, Windows, Linux PC, or mobile device on the local Wi-Fi network can discover the printer automatically via AirPrint / IPP.
- **WhatsApp & File Share Support**: Tap "Share" on any PDF label in WhatsApp or your file manager, select RolloPrint, preview it, and print.
- **Auto-Formatting for 4x6 Thermal Labels**: Automatically converts PDFs, images, or text into crisp 4" x 6" 203 DPI monochrome shipping labels.
- **Samsung DeX & Phone Friendly**: Fully aware of display cutouts (camera notches) on phones and scales naturally in Samsung DeX desktop mode.
- **Auto Hardware Detection**: Detects when the Rollo printer is plugged in and prompts for connection permissions automatically.

---

## Hardware Compatibility

| Device | Vendor ID (VID) | Product ID (PID) | Status |
| :--- | :--- | :--- | :--- |
| **Rollo X1038 USB Thermal Printer** | `2501` (`0x09C5`) | `1416` (`0x0588`) | Fully Supported |

> *Note: Compatible with standard TSPL-2 direct thermal label printers over USB.*

---

## How It Works

```
[ PDF Label / Network Job ] ➔ [ 816x1218 Canvas (4x6 @ 203 DPI) ]
                                             │
                                             ▼
[ Rollo USB Hardware ] ◄── [ USB Bulk Stream ] ◄── [ TSPL Command Stream ]
```

1. **Input**: A PDF label is selected manually, shared from WhatsApp, or sent over the local Wi-Fi network.
2. **Render**: The document is formatted and scaled onto an 816 × 1218 pixel canvas matching 4x6 inches at 203 DPI.
3. **Convert**: Pixel luminance is packed into 1-bit monochrome bytes with polarity calibrated specifically for Rollo thermal heads.
4. **Stream**: The TSPL command stream is sent directly to the printer over the USB OTG connection.

---

## Getting Started

### Requirements
- **Android Device**: Android 7.0 or newer with USB OTG support (works great on Samsung DeX, phones, and tablets).
- **USB Cable / Hub**: USB OTG adapter or USB-C docking station connecting the phone to the printer.
- **Printer**: Rollo X1038 thermal label printer with 4x6 labels loaded.

### Installation & Download
Download the latest pre-compiled APK from the [Releases](https://github.com/modnite/RolloPrint/releases) page and install it on your device.

---

## Changelog

For the complete release history, detailed feature updates, and exact timestamps, see [CHANGELOG.md](CHANGELOG.md).

---

## License

This project is open-source and available under the [MIT License](LICENSE).
