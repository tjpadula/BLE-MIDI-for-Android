package jp.kshoji.blemidi.util;//
//  MIDIStatus.java
//  BLE-MIDI-for-Android-develop
//
//  Created by Tom Padula on 1/7/24.
//  Copyright (c) 2024 Humble Earth Productions, Inc. All rights reserved.
//

//noinspection public
public enum MIDIStatus {
    // Channel Status Bytes, 0x80..0xEF:
    // Bottom nybble of the byte is the channel number.
    MIDIStatus_NoteOff((byte) 0x80),                    // data: note number, velocity value
    MIDIStatus_NoteOn((byte) 0x90),                        // data: note number, velocity value
    MIDIStatus_PolyphonicKeyPressure((byte) 0xA0),        // data: note number, pressure value
    MIDIStatus_ControlChange((byte) 0xB0),                // data: control number, control value
    MIDIStatus_ProgramChange((byte) 0xC0),                // data: program value (one byte)
    MIDIStatus_ChannelPressure((byte) 0xD0),            // data: pressure value (one byte)
    MIDIStatus_PitchWheel((byte) 0xE0),                    // data: value LSB, value MSB

    MIDIStatus_PolyphonicAfterTouch((byte) 0xA0),       // Alias for MIDIStatus_PolyphonicKeyPressure
    MIDIStatus_ChannelMode((byte) 0xB0),                // Alias for MIDIStatus_ControlChange: Channel Mode number, see Channel Mode Messages
    MIDIStatus_ChannelAfterTouch((byte) 0xD0),          // Alias for MIDIStatus_ChannelPressure

    // For masking the status nybble out of a status byte, leaving the channel:
    MIDIStatus_ChannelMask((byte) 0x0F),

    // For masking the channel nybble out of a status byte, leaving the status:
    MIDIStatus_StatusMask((byte) 0xF0),

    // For masking just the high bit, that is, the bit that indicates a system status message:
    MIDIStatus_StatusBitMask((byte) 0x80),
    MIDIStatus_StatusBit((byte) 0x80),

    MIDIStatus_ValueBitMask((byte)0x7F),

    // System Common Messages, 0xF0..0xF7:
    MIDIStatus_SysExStart((byte) 0xF0),                // data: one- or three-byte ID, random number data bytes until 0xF7.
    MIDIStatus_TimeCode((byte) 0xF1),                  // data: one byte, 0tttdddd - ttt = type, dddd = data
    MIDIStatus_SongPositionPointer((byte) 0xF2),       // data: LSB and MSB of 14-bit num of beats
    MIDIStatus_SongSelect((byte) 0xF3),                // data: one byte value
    MIDIStatus_TuneRequest((byte) 0xF6),               // No data.
    MIDIStatus_SysExEnd((byte) 0xF7),                  // ends SysEx. Only System Real-Time Messages can be within SysEx data.

    // System Real-Time Messages, 0xF8..0xFF, no data bytes:
    MIDIStatus_TimingClock((byte) 0xF8),
    MIDIStatus_Start((byte) 0xFA),
    MIDIStatus_Continue((byte) 0xFB),
    MIDIStatus_Stop((byte) 0xFC),
    MIDIStatus_ActiveSensing((byte) 0xFE),
    MIDIStatus_Reset((byte) 0xFF);

    public final byte value;

    MIDIStatus(byte inValue) {
        this.value = inValue;
    }
}
