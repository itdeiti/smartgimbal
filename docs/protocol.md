# FeiyuTech VLOG pocket BLE protocol notes

What this app knows about the gimbal's Bluetooth Low Energy protocol. The basics (characteristics,
joystick and home commands, CRC) come from
[Shalunizm/FeiyuTech-Pocket-Vlog-Protocol](https://github.com/Shalunizm/FeiyuTech-Pocket-Vlog-Protocol).
Everything else was found with this app on an `FY_PocketV_3E` and a Pixel 7 (November 2026).
Code: [`protocol/`](../app/src/main/java/dev/smartgimbal/protocol).

## Connection

- The device advertises as `FY_PocketV_xx`. Its advertisement lists only the generic HID service (`0x1812`), not the UART service, so it can be recognised by name only (checked on `FY_PocketV_3E`).
- It uses the Nordic UART Service:
  - TX (write, with or without response): `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
  - RX (notify): `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
- It also exposes a HID service (`0x1812`) and a vendor service `9e5d1e47-5c13-43a0-8635-82ad38a1386f`, both unused here.

## Frame layout

```
A5 5A | route (2) | command (1) | flags (2) | length (2, LE) | payload (length) | CRC16 (2, LE)
```

- **CRC:** CRC-16/XMODEM (poly `0x1021`, init `0x0000`) over everything after `A5 5A`, sent little-endian.
- **Notifications are not frame-aligned.** One notification can carry the end of one frame and the start of the next, and a frame can be split over two or three notifications. Buffer the stream and cut frames by header and length ([`FrameAssembler`](../app/src/main/java/dev/smartgimbal/protocol/FrameAssembler.kt)).

| route | direction |
| --- | --- |
| `03 00` | phone → gimbal, joystick |
| `03 02` | phone → gimbal, request |
| `02 03` | gimbal → phone, reply |
| `00 FF` | gimbal → phone, broadcast |

## Commands

| cmd | meaning |
| --- | --- |
| `0E` | Joystick (speed). Payload `00 XX XX YY YY`, X/Y signed int16 LE. Send continuously (20 Hz works well) and send zero to stop. |
| `33` | Return to home. Request payload `02`. The gimbal replies on route `02 03`, flags `60 00`, echoing the payload. |
| `61` | Apparently motion state after home: payload `01` while moving, `00` when done. |
| `1E` | Telemetry broadcast at about 2.4 Hz, payload `01 \| A int16 \| B int16 \| C int16 \| D u8`. |

Example frames:

```
A5 5A 03 00 0E 00 00 05 00 00 00 00 00 00 CC 45   joystick 0/0 (stop)
A5 5A 03 02 33 20 00 01 00 02 3C 56               home
A5 5A 00 FF 1E 00 00 08 00 01 9D F0 05 00 C7 0A 4F 6D 12   telemetry
```

## Telemetry

- **Fields:** A = tilt, B = roll, C = pan, each in 0.01°.
- **D:** goes 80 → 77 over a session, probably battery percent.
- **Pan:** wraps at ±180°. Normalise differences to −180…+180.
- **Pan in follow mode:** pan follows the handle, so "home" centres relative to the handle, not to an absolute angle.
- **Tilt end stops:** the mechanical stop upwards was 25–27°, not the ~32° seen once in raw values. The app learns the stop when tilt stops moving under command.

## Directions

Seen from the **display side** of the phone (the side facing you when the clamp and the buttons
point at you), both raw joystick axes are inverted: positive raw X turns left, positive raw Y tilts
down. The app wraps this as `FeiyuProtocol.move(panRight, tiltUp)`.

For face tracking that means:
- **Pan:** pan right turns both cameras towards their own left.
- **Tilt:** tilt up points the front camera up and the rear camera down.

## Speed response

- **Linear range:** roughly linear for small values, about 0.22°/s per joystick unit (51 ≈ 11°/s, 200 ≈ 45°/s).
- **Saturation:** around 60°/s. At 1000 the gimbal keeps moving for a moment after a stop frame.
- **Smoothing:** the gimbal smooths speed changes itself, with a time constant of about 0.25 s. A controller has to brake early (see the PD gains in [`TrackingParams`](../app/src/main/java/dev/smartgimbal/tracking/TrackingParams.kt)).

## Unknown

- Joystick bytes 5–9 and the meaning of `flags`.
- Rotating the phone holder, switching modes (follow, lock, …).
- The vendor service and the HID service.

A raw-frame sender in the app's debug screen (with automatic CRC) helps to explore these.
