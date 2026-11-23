# RetroRemote

A Kotlin-based universal remote platform for Android TV / Google TV, LG webOS, Roku, Bluetooth HID, and infrared devices.

RetroRemote is designed as both:
- a standalone Android remote-control app
- a reusable remote-engine library that can later be integrated into RetroTV

## Development stack

- Kotlin
- Android Gradle Plugin
- Jetpack Compose
- Android NSD for mDNS service discovery
- JDK 17

## Initial milestone

The first milestone focuses on Android TV / Google TV:
1. Discover Android TV Remote services over mDNS.
2. Implement secure Android TV Remote v2 pairing.
3. Send remote key events and manage reconnects.
4. Build the standalone remote UI.
5. Add legacy Android TV Remote v1 (Polo) compatibility.

## Planned platform support

- Android TV / Google TV
- LG webOS
- Roku
- Bluetooth HID
- Infrared (IR)

## Architecture

The project is organized into independent modules so protocol implementations and transports can be reused without depending on the app UI. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/ROADMAP.md](docs/ROADMAP.md) as those documents are added.

## Status

Early development. Discovery, pairing, and control are not yet production-ready.
