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
    MIDIStatus_NoteOn((byte) 0x90),                     // data: note number, velocity value
    MIDIStatus_PolyphonicKeyPressure((byte) 0xA0),      // data: note number, pressure value
    MIDIStatus_ControlChange((byte) 0xB0),              // data: control number, control value
    MIDIStatus_ProgramChange((byte) 0xC0),              // data: program value (one byte)
    MIDIStatus_ChannelPressure((byte) 0xD0),            // data: pressure value (one byte)
    MIDIStatus_PitchWheel((byte) 0xE0),                 // data: value LSB, value MSB

    // For masking the status nybble out of a status byte, leaving the channel:
    MIDIStatus_ChannelMask((byte) 0x0F),

    // Defined as an alias below:
    // For masking the channel nybble out of a status byte, leaving the status:
//    MIDIStatus_StatusMask((byte) 0xF0),

    // Defined as aliases below:
    // For masking just the high bit, that is, the bit that indicates a system status message:
//    MIDIStatus_StatusBitMask((byte) 0x80),
//    MIDIStatus_StatusBitSet((byte) 0x80),

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
    MIDIStatus_Reset((byte) 0xFF),

    MIDIStatus_Invalid((byte) 0xFF);

    // Aliases for old nomenclature:
    static final MIDIStatus MIDIStatus_PolyphonicAfterTouch = MIDIStatus_PolyphonicKeyPressure;
    static final MIDIStatus MIDIStatus_ChannelMode = MIDIStatus_ControlChange;
    static final MIDIStatus MIDIStatus_ChannelAfterTouch = MIDIStatus_ChannelPressure;

    // These are declared in this manner so they will compare for equality correctly.
    // ...but they still aren't const enough for a switch() statement, and so are useless.
    static final MIDIStatus MIDIStatus_StatusMask = MIDIStatus_SysExStart;
    static final MIDIStatus MIDIStatus_StatusBitMask = MIDIStatus_NoteOff;
    static final MIDIStatus MIDIStatus_StatusBitSet = MIDIStatus_NoteOff;
    static final MIDIStatus MIDIStatus_CommonOrRealtime = MIDIStatus_SysExStart;


    public final byte value;

    MIDIStatus(byte inValue) {
        this.value = inValue;
    }

    // We have to do this because James Gosling is too arrogant to believe that programmers
    // can do casting and operator overloading without burning themselves. I guess those techniques
    // are just too confusing for him. He should listen to Bjarne Stroustrup.
    //
    // Turns out there is simply no way to create an alias for an enum value in Java
    // that can be used in a switch() statement, no matter how inefficiently it is done.
    static public MIDIStatus fromInt(int inValue) {
        assert ((inValue & 0xFF) == inValue);

        switch (inValue) {

            case 0x80: return MIDIStatus_NoteOff;
            case 0x90: return MIDIStatus_NoteOn;
            case 0xA0: return MIDIStatus_PolyphonicKeyPressure;
            case 0xB0: return MIDIStatus_ControlChange;
            case 0xC0: return MIDIStatus_ProgramChange;
            case 0xD0: return MIDIStatus_ChannelPressure;
            case 0xE0: return MIDIStatus_PitchWheel;

            case 0x0F: return MIDIStatus_ChannelMask;

            case 0x7F: return MIDIStatus_ValueBitMask;

            case 0xF0: return MIDIStatus_SysExStart;
            case 0xF1: return MIDIStatus_TimeCode;
            case 0xF2: return MIDIStatus_SongPositionPointer;
            case 0xF3: return MIDIStatus_SongSelect;
            case 0xF6: return MIDIStatus_TuneRequest;
            case 0xF7: return MIDIStatus_SysExEnd;

            case 0xF8: return MIDIStatus_TimingClock;
            case 0xFA: return MIDIStatus_Start;
            case 0xFB: return MIDIStatus_Continue;
            case 0xFC: return MIDIStatus_Stop;
            case 0xFE: return MIDIStatus_ActiveSensing;
            case 0xFF: return MIDIStatus_Reset;

            default:
                assert (false);
                break;
        }

        return MIDIStatus_Invalid;   // we won't get here
    }
}
