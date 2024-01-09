package jp.kshoji.blemidi.util;//
//  ControlChange.java
//  BLE-MIDI-for-Android-develop
//
//  Created by Tom Padula on 1/7/24.
//  Copyright (c) 2024 Humble Earth Productions, Inc. All rights reserved.
//

// Control Change Control Numbers:
// Might want to fill in the rest of these someday, just for completeness:
// https://www.midi.org/specifications-old/item/table-3-control-change-messages-data-bytes-2
public enum ControlChange {

    ControlChange_DataEntryMSB((byte) 0x06),                        // data: MSB value
    ControlChange_DataEntryLSB((byte) 0x26),                        // data: LSB value
    ControlChange_RegisteredParameterNumberLSB((byte) 0x64),        // data: Parameter number LSB, parameter value LSB
    ControlChange_RegisteredParameterNumberMSB((byte) 0x65),        // data: Parameter number MSB, parameter value MSB

    ControlChange_RPNLSB((byte) 0x64),      // Alias for ControlChange_RegisteredParameterNumberLSB
    ControlChange_RPNMSB((byte) 0x65);      // Alias for ControlChange_RegisteredParameterNumberMSB

    public final byte value;

    ControlChange(byte inValue) {
        this.value = inValue;
    }
}
