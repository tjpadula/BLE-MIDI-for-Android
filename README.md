BLE MIDI for Android
====================
[![Build Status](https://travis-ci.com/kshoji/BLE-MIDI-for-Android.svg?branch=develop)](https://travis-ci.com/kshoji/BLE-MIDI-for-Android)

MIDI over Bluetooth LE library for Android `API Level 18`(4.3, JellyBean) or later

- Protocol compatible with [Apple Bluetooth Low Energy MIDI Specification](https://developer.apple.com/bluetooth/Apple-Bluetooth-Low-Energy-MIDI-Specification.pdf).
    - The app can be connected with iOS 8 / OS X Yosemite MIDI apps, and BLE MIDI devices.
- BLE Central function
    - `Central` means `BLE MIDI Device's client`.
- BLE Peripheral function
    - `Peripheral` means `BLE MIDI Device`.

Requirements
------------

- BLE Central function needs:
    - Bluetooth LE(4.0) support
    - `API Level 18`(4.3, JellyBean) or above
        - Bluetooth Pairing function needs `API Level 19`(4.4, KitKat) or above
- BLE Peripheral function needs:
    - Bluetooth LE(4.0) support
    - Bluetooth LE Peripheral support(Nexus 5 with custom ROM, Nexus 6, Nexus 9, etc.)
    - `API Level 21`(5.0, Lollipop) or above

Repository Overview
-------------------

- Library Project: `library`
- Sample Project: `sample`
    - Includes `BleMidiCentralActivity`, and `BleMidiPeripheralActivity` examples.

-------------------

Reasons for this fork:

- Properly trigger writes to BLE hardware and respect buffer sizes. Allow sending of BLE only when ready.
- Fix sysex sending so it properly breaks up messages.
- Sending packet timestamps work according to spec now.
- Receiving packets from BLE hardware keeps them in the proper order now.
- Apply timestamps to each message as it comes in, rather than when BLE is ready for them.
- Update build to API 34.
- Add older onCharacteristicXxxxxx calls so the library works on 32 and below.
- Don't stall the request queue if there is a failure.

To use this library, put this in your app's build.gradle dependencies:

implementation 'com.github.tjpadula:BLE-MIDI-library:v0.0.16-alpha'

...and ensure that you have this reference to jitpack line in settings.gradle:

dependencyResolutionManagement {
repositories {
maven { url 'https://jitpack.io' }
}
}

-------------------

Usage of the library
--------------------

For the detail, see the [wiki](https://github.com/kshoji/BLE-MIDI-for-Android/wiki).

LICENSE
=======
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
