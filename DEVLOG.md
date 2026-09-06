# RolloPrint — Development Journey & Engineering Progress Report

> **Author:** Project Lead & Core Developer  
> **Engineering Partner:** Gemini AI Coding Assistant  
> **Repository:** [modnite/RolloPrint](https://github.com/modnite/RolloPrint)  
> **Target Hardware:** Rollo X1038 / Thermal Printers via USB OTG Bulk Streaming  

---

## Introduction: Why I Built RolloPrint

In our office, the Rollo thermal printer was originally plugged into our boss's Windows computer. When that PC went down, printing shipping labels ground to a halt. Whenever a label arrived via WhatsApp from our boss, I felt completely useless not being able to print it directly. Getting a label printed required someone in the office to physically plug the printer into their MacBook, log into WhatsApp Web, download the PDF, and print it.

I rely heavily on my **Samsung Galaxy S23 paired with Samsung DeX and a docking station**. Whenever my phone is docked at my desk, I wanted a setup where it could connect directly to the Rollo printer over USB and host a wireless print server for the whole office.

To bring this vision to life rapidly, I paired up with my AI co-pilot (Gemini). Together, we architected **RolloPrint**: a zero-dependency, driverless IPP Everywhere print server and USB thermal utility for Android that runs natively, accepts PDF/raster jobs over standard AirPrint / IPP protocols, and streams bit-packed TSPL commands directly to the Rollo USB bulk endpoint.

---

## Milestone 1.0.0 — September 2, 2026 at 12:17 PM: The Core TSPL Engine & USB OTG Bulk Pipeline

My first goal was raw hardware communication over USB OTG. The Rollo X1038 expects monochrome 203 DPI TSPL2 bitmap streams formatted as $816 \times 1218$ pixels for standard $101.6 \times 152.4 \text{ mm}$ (4x6 inch) labels.

### Core Challenges & Breakthroughs:
1. **Bit-Packing & Luminance Thresholding:**
   I built a custom monochrome bitmap packer that iterates over $816 \times 1218$ pixel arrays, calculates ITU-R BT.601 luminance ($0.299R + 0.587G + 0.114B$), thresholding white vs black pixels, and packs 8 pixels per byte across 102 bytes per line ($102 \times 8 = 816$ pixels).
2. **TSPL Command Packaging:**
   Wrapped the monochrome bitmap bytes in a clean TSPL header:
   ```text
   SIZE 102 mm,153 mm
   REFERENCE 0,0
   DIRECTION 0,0
   GAP 3 mm,0 mm
   DENSITY 8
   SPEED 6
   CLS
   BITMAP 0,0,102,1218,1,[mono_bytes]
   PRINT 1,1
   ```
3. **Intent Share Sheet Integration:**
   Added `ACTION_SEND` and `ACTION_VIEW` intent filters so sharing a PDF label from WhatsApp, Email, or File Manager directly opens the print preview dialog in RolloPrint.

---

## Milestone 1.1.0 – 1.3.0 — September 5, 2026 at 10:58 AM: Driverless IPP Everywhere Server & HP `jIPP` Engine

To allow any Linux (CUPS), macOS, or Windows PC on the office network to discover and print to the Rollo thermal printer without installing vendor drivers, I implemented an embedded HTTP/1.1 IPP Everywhere server listening on Port `8631`.

### Highlights:
- **HP `jipp-core` Integration (`com.hp.jipp:jipp-core:0.7.18`):** Replaced custom IPP frame serialization with HP's official, production-grade IPP Everywhere library for 100% PWG 5100.14 compliance.
- **mDNS / Zeroconf Auto-Discovery:** Advertised `_ipp._tcp` on Port 8631 with TXT records (`pdl=image/pwg-raster,application/pdf`, `product=(Rollo Thermal Printer 4x6)`, `printer-type=0x4000000`).
- **CUPS `#PDF-BANNER` Renderer:** When Linux CUPS sent `#PDF-BANNER` test pages, I built a native 4x6 PDF label renderer that generates a crisp **RolloPrint Test Page** complete with resolution, protocol specs, and live timestamp.

---

## Milestone 1.5.0 — September 5, 2026 at 4:25 PM: Real-Time Hardware Status Polling & Onboard RAM Purging

When paper ran out during printing, jobs would sit buffered in memory. Working with my AI pair-programmer, I conducted hardware status investigations on the Rollo X1038 USB endpoints to achieve real-time hardware status detection.

### Empirical Hardware Commands Discovered:
- **Status Query (`<ESC>!?` / `0x1B, 0x21, 0x3F`):**
  Sending `<ESC>!?` over USB bulk OUT returns 1 status byte over bulk IN:
  - `0x00`: Printer Ready (Green LED)
  - Bit 0 (`0x01`): Cover / Print Head Open
  - Bits 1/2 (`0x06`): Media Empty / Out of Paper (Red LED)
  - Bit 5 (`0x20`): Paused
- **Queue Purge (`~!C` / `0x7E, 0x21, 0x43`):**
  Sending `~!C` directly over USB immediately purges the Rollo printer's onboard RAM buffer memory and halts label feeding!

I wired status polling into a 3-second background thread in `PrintServerService` and added a live **Hardware Status Badge** (`● Hardware: Ready`, `● Hardware: Out of Paper (Red LED)`) on the app header.

---

## Milestone 1.7.0 – 1.9.0 — September 5, 2026 at 6:51 PM: Held Queue Manager, Pastebin Log Exporter & In-App Auto-Updates

To prevent accidental printing when thermal paper is reinserted, I created `JobQueueManager`:
- Jobs arriving while paper is out are held in app memory (`JobStatus.HELD`).
- **Interactive Queue Manager Modal:** Added a "View queue" button opening an interactive modal where users can inspect held jobs, preview rendered label bitmaps, trigger "Print now", toggle sort orders ("Oldest first" ↕ "Newest first"), or clear jobs individually.
- **Universal Etherpad / Pastebin Log Exporter:** Added a "Dump to pastebin" action button that posts activity logs directly to a self-hosted Etherpad instance via multipart form upload (`/p/<padID>/import`).
- **In-App GitHub Auto-Updater (`AppUpdateManager`):** Built a background update checker querying GitHub Releases (`https://api.github.com/repos/modnite/RolloPrint/releases/latest`) every 15 minutes. Automatically prompts with release notes, downloads the new `RolloPrint.apk`, and launches Android's native installer via `FileProvider`.

---

## Milestone 2.0.0 — September 5, 2026 at 8:03 PM: Comprehensive UI/UX Design Overhaul & Material 3 Refinements

In Milestone 2.0.0, I completed a thorough UI/UX overhaul to eliminate all visual friction, text wrapping, and layout inconsistencies across phone portrait, landscape, tablet, and Samsung DeX desktop modes:

1. **Single-Action Print Queue Card:** Streamlined `cardQueue` to feature a single prominent **"View queue"** button, giving the card title "Print queue" 80%+ width so it never truncates into `Print qu...`.
2. **Equal-Width Dialog Action Buttons:** Applied `layout_weight="1"` across `Print all`, `Clear all`, and `Done` buttons in `dialog_queue_manager.xml`, eliminating vertical button deformation.
3. **Collapsible Settings Sections & Theme Selector:** Positioned **App theme** (System default, Dark theme, Light theme) as Section 1 at the top of the Settings popup.
4. **Landscape & Samsung DeX Scrollability:** Wrapped the main dashboard in `NestedScrollView` to guarantee seamless vertical scrolling across phone portrait, landscape, tablet, and desktop DeX modes.

---

## Summary & Current Architecture

RolloPrint is now a production-grade, zero-dependency Android utility that turns any Rollo thermal printer into an enterprise driverless AirPrint / IPP network printer.

```
[ Linux / macOS / Windows PC ]
             │ (Driverless IPP Everywhere / Port 8631)
             ▼
    [ Android Device (RolloPrint) ]
             │
             ├── IppServer (jIPP Core Engine)
             ├── JobQueueManager (Sequential Queue & Pre-Transfer Hardware Guard)
             ├── UsbPrintManager (<ESC>!? Polling & ~!C Purging)
             ▼ (USB OTG Bulk OUT Stream @ 203 DPI)
    [ Rollo X1038 Thermal Printer ]
```
