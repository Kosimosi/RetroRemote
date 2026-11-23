# Architecture

RetroRemote separates UI, platform discovery, protocol implementations, and transports.

## Current modules

- `app`: standalone Jetpack Compose Android application.
- `remote-core`: platform-neutral device model and remote transport contract.
- `discovery-mdns`: Android NSD discovery for Android TV Remote v2 and v1 service advertisements.

## Planned modules

- `protocol-androidtv-v2`: TLS identity, pairing, protobuf framing, configuration, and key events.
- `protocol-androidtv-v1`: legacy Polo pairing and control.
- `protocol-webos`: LG webOS SSAP over secure WebSocket.
- `protocol-roku`: Roku ECP over HTTP.
- `transport-bluetooth`: Bluetooth HID fallback/input.
- `transport-ir`: device-specific infrared encoding and transmission.
- `device-storage`: paired device and credential persistence.

Protocol modules must depend on explicit interfaces and must not depend on Compose UI. Private keys and pairing secrets must be protected by Android Keystore-backed storage.
