#!/usr/bin/env python3
"""Linux BLE NUS peripheral + virtual keyboard.

Makes this machine act like the ESP32 firmware: it advertises the Nordic UART
Service, accepts the same protocol over the RX characteristic, and types the
received text into the local session via a uinput virtual keyboard.

Usage:
    source linux/.venv/bin/activate
    python linux/ble_kbd.py

Notes:
  - Set the GNOME keyboard layout to match the app ("English (US)" or
    "Spanish (Spain)"); press the layout button in the app accordingly.
  - Requires read/write access to /dev/uinput (user must be in the 'input'
    group; log out/in once after adding it).
"""

import argparse
import asyncio
import logging
import sys

from bluez_peripheral.advert import Advertisement
from bluez_peripheral.gatt.characteristic import CharacteristicFlags, characteristic
from bluez_peripheral.gatt.service import Service
from bluez_peripheral.util import Adapter, get_message_bus

from engine import Engine
from injector import Injector

NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

DEVICE_NAME = "ESP32-S3-KBD"

log = logging.getLogger("ble-kbd")


class NusService(Service):
    """Nordic UART Service. RX = phone -> us, TX = us -> phone (notify)."""

    def __init__(self, on_line):
        self._on_line = on_line
        self._rx_buf = bytearray()
        super().__init__(NUS_SERVICE_UUID)
        self.rx.setter(self._rx_write)  # class-level characteristic

    rx = characteristic(
        NUS_RX_UUID,
        CharacteristicFlags.WRITE | CharacteristicFlags.WRITE_WITHOUT_RESPONSE,
    )
    tx = characteristic(NUS_TX_UUID, CharacteristicFlags.NOTIFY)

    def _rx_write(self, service, value, options):
        log.debug("rx write %r options=%r", bytes(value), options)
        self._rx_buf.extend(bytes(value))
        while True:
            idx = -1
            for i, b in enumerate(self._rx_buf):
                if b in (0x0A, 0x0D):
                    idx = i
                    break
            if idx < 0:
                break
            if idx > 512:
                # runaway line, drop it
                self._rx_buf.clear()
                break
            line = bytes(self._rx_buf[:idx])
            del self._rx_buf[:idx + 1]
            if line:
                self._on_line(line)


async def run_adapter(args):
    engine = Engine(Injector())

    bus = await get_message_bus()
    adapter = await Adapter.get_first(bus)

    if not await adapter.get_powered():
        log.error("adapter is not powered; run: bluetoothctl power on")
        return 1

    def on_line(line):
        text = line.decode("utf-8", errors="replace")
        log.info("RX %r", text)
        engine.submit(text)
        # Reply immediately (matches firmware); only sent when subscribed.
        svc.tx.changed(b"OK")

    svc = NusService(on_line)
    await svc.register(bus, adapter=adapter)

    advert = Advertisement(
        DEVICE_NAME,
        [NUS_SERVICE_UUID],
        appearance=0,
        timeout=args.advert_timeout,
        duration=1440,
    )
    await advert.register(bus, adapter=adapter)

    log.info("advertising as %r (NUS %s) on %s",
             DEVICE_NAME, NUS_SERVICE_UUID, await adapter.get_name())
    log.info("ready - connect with the app")

    try:
        await asyncio.Event().wait()
    finally:
        await svc.unregister()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--advert-timeout", type=int, default=0,
                        help="seconds before the advertisement is removed (0 = never)")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    try:
        return asyncio.run(run_adapter(args))
    except KeyboardInterrupt:
        return 0
    except Exception as exc:  # noqa: BLE001
        log.error("fatal: %s", exc)
        if args.verbose:
            log.exception("traceback")
        return 1


if __name__ == "__main__":
    sys.exit(main())