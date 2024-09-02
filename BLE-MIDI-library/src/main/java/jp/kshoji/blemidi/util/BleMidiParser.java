package jp.kshoji.blemidi.util;

import static jp.kshoji.blemidi.util.MIDIStatus.*;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.SparseIntArray;

import java.io.IOException;
import java.util.concurrent.LinkedTransferQueue;

import jp.kshoji.blemidi.device.MidiInputDevice;
import jp.kshoji.blemidi.listener.OnMidiInputEventListener;

/**
 * BLE MIDI Parser<br />
 * The protocol compatible with Apple's `MIDI over Bluetooth LE` specification.<br />
 * One BleMidiParser instance belongs to one MidiInputDevice instance.
 *
 * @author K.Shoji
 */
public final class BleMidiParser {
    // MIDI event message
    private int midiEventKind = 0;
    private int midiEventNote = 0;
    private int midiEventVelocity = 0;

    // for RPN/NRPN messages
    private static final int RPN_STATUS_NONE = 0;
    private static final int RPN_STATUS_RPN = 1;
    private static final int RPN_STATUS_NRPN = 2;
    private int rpnNrpnFunction;
    private int rpnNrpnValueMsb;
    private int rpnNrpnValueLsb;
    private int rpnStatus = RPN_STATUS_NONE;
    private int rpnFunctionMsb = 0x7f;
    private int rpnFunctionLsb = 0x7f;
    private int nrpnFunctionMsb = 0x7f;
    private int nrpnFunctionLsb = 0x7f;

    private final SparseIntArray rpnCacheMsb = new SparseIntArray();
    private final SparseIntArray rpnCacheLsb = new SparseIntArray();
    private final SparseIntArray nrpnCacheMsb = new SparseIntArray();
    private final SparseIntArray nrpnCacheLsb = new SparseIntArray();

    // for SysEx messages
    private final Object systemExclusiveLock = new Object();
    private final ReusableByteArrayOutputStream systemExclusiveStream = new ReusableByteArrayOutputStream();
    private final ReusableByteArrayOutputStream systemExclusiveRecoveryStream = new ReusableByteArrayOutputStream();

    // states
    private static final int MIDI_STATE_TIMESTAMP = 0;
    private static final int MIDI_STATE_WAIT = 1;
    private static final int MIDI_STATE_SIGNAL_2BYTES_2 = 21;
    private static final int MIDI_STATE_SIGNAL_3BYTES_2 = 31;
    private static final int MIDI_STATE_SIGNAL_3BYTES_3 = 32;
    private static final int MIDI_STATE_SIGNAL_SYSEX = 41;
    private int midiState = MIDI_STATE_TIMESTAMP;

    // for Timestamp
    private int timestamp = 0;

    private OnMidiInputEventListener midiInputEventListener = null;
    private final MidiInputDevice sender;

    private volatile boolean isRunning = false;
    private volatile boolean isTerminated = false;

    /**
     * Constructor
     *
     * @param sender the sender
     */
    public BleMidiParser(@NonNull final MidiInputDevice sender) {
        this.sender = sender;

        midiState = MIDI_STATE_TIMESTAMP;
        midiEventKind = 0;
        midiEventNote = 0;
        midiEventVelocity = 0;

        transferMessageThread.start();
    }

    /**
     * Sets {@link jp.kshoji.blemidi.listener.OnMidiInputEventListener}
     *
     * @param midiInputEventListener the listener for MIDI events
     */
    public void setMidiInputEventListener(@Nullable OnMidiInputEventListener midiInputEventListener) {
        this.midiInputEventListener = midiInputEventListener;
    }

    /**
     * Stops the internal Thread
     */
    public void start() {
        if (isTerminated) {
            return;
        }
        isRunning = true;
        if (!transferMessageThread.isAlive()) {
            transferMessageThread.start();
        }
    }

    /**
     * Stops the internal Thread
     */
    public void stop() {
        if (isTerminated) {
            return;
        }
        isRunning = false;
        transferMessageThread.interrupt();
    }

    /**
     * Stops the internal Thread
     */
    public void terminate() {
        if (isTerminated) {
            return;
        }
        isTerminated = true;
        isRunning = false;
        transferMessageThread.interrupt();
    }

    /**
     * {@link Runnable} with MIDI event data, and firing timing
     */
    private abstract class MidiEvent implements Runnable {
        private static final int INVALID = -1;

        private final int arg1;
        private final int arg2;
        private final int arg3;
        private final byte[] array;
        
        private MidiEvent(int arg1, int arg2, int arg3, byte[] array) {
            this.arg1 = arg1;
            this.arg2 = arg2;
            this.arg3 = arg3;
            this.array = array;
        }

        /**
         * Constructor with no arguments
         *
         */
        MidiEvent() {
            this(INVALID, INVALID, INVALID, null);
        }

        /**
         * Constructor with 1 argument
         *
         * @param arg1 argument 1
         */
        MidiEvent(int arg1) {
            this(arg1, INVALID, INVALID, null);
        }

        /**
         * Constructor with 2 arguments
         *
         * @param arg1 argument 1
         * @param arg2 argument 2
         */
        MidiEvent(int arg1, int arg2) {
            this(arg1, arg2, INVALID, null);
        }

        /**
         * Constructor with 3 arguments
         *
         * @param arg1 argument 1
         * @param arg2 argument 2
         * @param arg3 argument 3
         */
        MidiEvent(int arg1, int arg2, int arg3) {
            this (arg1, arg2, arg3, null);
        }

        /**
         * Constructor with array
         *
         * @param array data
         */
        MidiEvent(@NonNull byte[] array) {
            this(INVALID, INVALID, INVALID, array);
        }
        
        public int getArg1() {
            return arg1;
        }

        public int getArg2() {
            return arg2;
        }

        public int getArg3() {
            return arg3;
        }

        public byte[] getArray() {
            return array;
        }
    }

    /**
     * Parses MIDI events
     *
     * @param header the header bits
     * @param event the event byte
     */
    private void parseMidiEvent(final int header, final byte event) {
        final int midiEvent = event & 0xff;

        if (midiState == MIDI_STATE_TIMESTAMP) {
            if ((midiEvent & 0x80) == 0) {
                // running status
                midiState = MIDI_STATE_WAIT;
            }

            // Need this coercion, otherwise the sysex end gets sign extended then compared
            // as an int, which is negative, and will always be false.
            if ((byte) midiEvent == MIDIStatus.MIDIStatus_SysExEnd.value) {     // force 8-bit comparison
                // is this end of SysEx???
                synchronized (systemExclusiveLock) {
                    if (systemExclusiveRecoveryStream.size() > 0) {
                        // previous SysEx has been failed, due to timestamp was 0xF7
                        // process SysEx again

                        // last written byte is for timestamp
                        int removed = systemExclusiveRecoveryStream.replaceLastByte(midiEvent);
                        if (removed >= 0) {
                            timestamp = ((header & 0x3f) << 7) | (removed & 0x7f);

                            addEventToQueue(new MidiEvent(systemExclusiveRecoveryStream.toByteArray()) {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiSystemExclusive(sender, getArray());
                                    }
                                }
                            });
                        }

                        systemExclusiveRecoveryStream.reset();
                    }
                }

                // process next byte with state: MIDI_STATE_TIMESTAMP
                midiState = MIDI_STATE_TIMESTAMP;
                return;
            } else {
                // there is no error. reset the stream for recovery
                synchronized (systemExclusiveLock) {
                    if (systemExclusiveRecoveryStream.size() > 0) {
                        systemExclusiveRecoveryStream.reset();
                    }
                }
            }
        }

        if (midiState == MIDI_STATE_TIMESTAMP) {
            timestamp = ((header & 0x3f) << 7) | (midiEvent & 0x7f);
            midiState = MIDI_STATE_WAIT;
        } else if (midiState == MIDI_STATE_WAIT) {
            switch (midiEvent & MIDIStatus_StatusMask.value) {  // bitwise AND is 8-bits, ok
                case 0xf0: {
                    switch (midiEvent) {
                        case 0xf0:
                            synchronized (systemExclusiveLock) {
                                systemExclusiveStream.reset();
                                systemExclusiveStream.write(midiEvent);
                                systemExclusiveRecoveryStream.reset();
                            }
                            midiState = MIDI_STATE_SIGNAL_SYSEX;
                            break;

                        case 0xf1:
                        case 0xf3:
                            // 0xf1 MIDI Time Code Quarter Frame. : 2bytes
                            // 0xf3 Song Select. : 2bytes
                            midiEventKind = midiEvent;
                            midiState = MIDI_STATE_SIGNAL_2BYTES_2;
                            break;

                        case 0xf2:
                            // 0xf2 Song Position Pointer. : 3bytes
                            midiEventKind = midiEvent;
                            midiState = MIDI_STATE_SIGNAL_3BYTES_2;
                            break;

                        case 0xf6:
                            // 0xf6 Tune Request : 1byte
                            addEventToQueue(new MidiEvent() {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiTuneRequest(sender);
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                        case 0xf8:
                            // 0xf8 Timing Clock : 1byte
                            addEventToQueue(new MidiEvent() {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiTimingClock(sender);
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                        case 0xfa:
                            // 0xfa Start : 1byte
                            addEventToQueue(new MidiEvent() {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiStart(sender);
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                        case 0xfb:
                            // 0xfb Continue : 1byte
                            addEventToQueue(new MidiEvent() {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiContinue(sender);
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                        case 0xfc:
                            // 0xfc Stop : 1byte
                            addEventToQueue(new MidiEvent() {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiStop(sender);
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                        case 0xfe:
                            // 0xfe Active Sensing : 1byte
                            addEventToQueue(new MidiEvent() {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiActiveSensing(sender);
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                        case 0xff:
                            // 0xff Reset : 1byte
                            addEventToQueue(new MidiEvent() {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiReset(sender);
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;

                        default:
                            break;
                    }
                }
                break;
                case 0x80:
                case 0x90:
                case 0xa0:
                case 0xb0:
                case 0xe0:
                    // 3bytes pattern
                    midiEventKind = midiEvent;
                    midiState = MIDI_STATE_SIGNAL_3BYTES_2;
                    break;
                case 0xc0: // program change
                case 0xd0: // channel after-touch
                    // 2bytes pattern
                    midiEventKind = midiEvent;
                    midiState = MIDI_STATE_SIGNAL_2BYTES_2;
                    break;
                default:
                    // 0x00 - 0x70: running status
                    if ((midiEventKind & 0xf0) != 0xf0) {
                        // previous event kind is multi-bytes pattern
                        midiEventNote = midiEvent;
                        midiState = MIDI_STATE_SIGNAL_3BYTES_3;
                    }
                    break;
            }
        } else if (midiState == MIDI_STATE_SIGNAL_2BYTES_2) {
            switch (midiEventKind & MIDIStatus_StatusMask.value) {  // bitwise AND is 8-bits, ok
                // 2bytes pattern
                case 0xc0: // program change
                    midiEventNote = midiEvent;
                    addEventToQueue(new MidiEvent(midiEventKind, midiEventNote) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                midiInputEventListener.onMidiProgramChange(sender, getArg1() & 0xf, getArg2());
                            }
                        }
                    });
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
                case 0xd0: // channel after-touch
                    midiEventNote = midiEvent;
                    addEventToQueue(new MidiEvent(midiEventKind, midiEventNote) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                midiInputEventListener.onMidiChannelAftertouch(sender, getArg1() & 0xf, getArg2());
                            }
                        }
                    });
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
                case 0xf0: {
                    switch (midiEventKind) {
                        case 0xf1:
                            // 0xf1 MIDI Time Code Quarter Frame. : 2bytes
                            midiEventNote = midiEvent;
                            addEventToQueue(new MidiEvent(midiEventNote) {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiTimeCodeQuarterFrame(sender, getArg1());
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                        case 0xf3:
                            // 0xf3 Song Select. : 2bytes
                            midiEventNote = midiEvent;
                            addEventToQueue(new MidiEvent(midiEventNote) {
                                @Override
                                public void run() {
                                    if (midiInputEventListener != null) {
                                        midiInputEventListener.onMidiSongSelect(sender, getArg1());
                                    }
                                }
                            });
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                        default:
                            // illegal state
                            midiState = MIDI_STATE_TIMESTAMP;
                            break;
                    }
                }
                    break;
                default:
                    // illegal state
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
            }
        } else if (midiState == MIDI_STATE_SIGNAL_3BYTES_2) {
            switch (midiEventKind & MIDIStatus_StatusMask.value) {  // bitwise AND is 8-bits, ok
                case 0x80:
                case 0x90:
                case 0xa0:
                case 0xb0:
                case 0xe0:
                case 0xf0:
                    // 3bytes pattern
                    midiEventNote = midiEvent;
                    midiState = MIDI_STATE_SIGNAL_3BYTES_3;
                    break;
                default:
                    // illegal state
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
            }
        } else if (midiState == MIDI_STATE_SIGNAL_3BYTES_3) {
            switch (midiEventKind & MIDIStatus_StatusMask.value) {  // bitwise AND is 8-bits, ok
                // 3bytes pattern
                case 0x80: // note off
                    midiEventVelocity = midiEvent;
                    addEventToQueue(new MidiEvent(midiEventKind, midiEventNote, midiEventVelocity) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                midiInputEventListener.onMidiNoteOff(sender, getArg1() & 0xf, getArg2(), getArg3());
                            }
                        }
                    });
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
                case 0x90: // note on
                    midiEventVelocity = midiEvent;
                    addEventToQueue(new MidiEvent(midiEventKind, midiEventNote, midiEventVelocity) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                if (getArg3() == 0) {
                                    midiInputEventListener.onMidiNoteOff(sender, getArg1() & 0xf, getArg2(), getArg3());
                                } else {
                                    midiInputEventListener.onMidiNoteOn(sender, getArg1() & 0xf, getArg2(), getArg3());
                                }
                            }
                        }
                    });
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
                case 0xa0: // control polyphonic key pressure
                    midiEventVelocity = midiEvent;
                    addEventToQueue(new MidiEvent(midiEventKind, midiEventNote, midiEventVelocity) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                midiInputEventListener.onMidiPolyphonicAftertouch(sender, getArg1() & 0xf, getArg2(), getArg3());
                            }
                        }
                    });
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
                case 0xb0: // control change
                    midiEventVelocity = midiEvent;

                    // process RPN/NRPN messages
                    switch (midiEventNote) {
                        case 6: {
                            // RPN/NRPN value MSB
                            rpnNrpnValueMsb = midiEventVelocity & 0x7f;
                            if (rpnStatus == RPN_STATUS_RPN) {
                                rpnNrpnFunction = ((rpnFunctionMsb & 0x7f) << 7) | (rpnFunctionLsb & 0x7f);
                                rpnCacheMsb.put(rpnNrpnFunction, rpnNrpnValueMsb);
                                rpnNrpnValueLsb = rpnCacheLsb.get(rpnNrpnFunction, 0/*if not found*/);
                                addEventToQueue(new MidiEvent(midiEventKind, rpnNrpnFunction, (rpnNrpnValueMsb << 7 | rpnNrpnValueLsb)) {
                                    @Override
                                    public void run() {
                                        if (midiInputEventListener != null) {
                                            midiInputEventListener.onRPNMessage(sender, getArg1() & 0xf, getArg2() & 0x3fff, getArg3() & 0x3fff);
                                        }
                                    }
                                });
                            } else if (rpnStatus == RPN_STATUS_NRPN) {
                                rpnNrpnFunction = ((nrpnFunctionMsb & 0x7f) << 7) | (nrpnFunctionLsb & 0x7f);
                                nrpnCacheMsb.put(rpnNrpnFunction, rpnNrpnValueMsb);
                                rpnNrpnValueLsb = nrpnCacheLsb.get(rpnNrpnFunction, 0/*if not found*/);
                                addEventToQueue(new MidiEvent(midiEventKind, rpnNrpnFunction, (rpnNrpnValueMsb << 7 | rpnNrpnValueLsb)) {
                                    @Override
                                    public void run() {
                                        if (midiInputEventListener != null) {
                                            midiInputEventListener.onNRPNMessage(sender, getArg1() & 0xf, getArg2() & 0x3fff, getArg3() & 0x3fff);
                                        }
                                    }
                                });
                            }
                            break;
                        }
                        case 38: {
                            // RPN/NRPN value LSB
                            rpnNrpnValueLsb = midiEventVelocity & 0x7f;
                            if (rpnStatus == RPN_STATUS_RPN) {
                                rpnNrpnFunction = ((rpnFunctionMsb & 0x7f) << 7) | (rpnFunctionLsb & 0x7f);
                                rpnNrpnValueMsb = rpnCacheMsb.get(rpnNrpnFunction, 0/*if not found*/);
                                rpnCacheLsb.put(rpnNrpnFunction, rpnNrpnValueLsb);
                                addEventToQueue(new MidiEvent(midiEventKind, rpnNrpnFunction, (rpnNrpnValueMsb << 7 | rpnNrpnValueLsb)) {
                                    @Override
                                    public void run() {
                                        if (midiInputEventListener != null) {
                                            midiInputEventListener.onRPNMessage(sender, getArg1() & 0xf, getArg2() & 0x3fff, getArg3() & 0x3fff);
                                        }
                                    }
                                });
                            } else if (rpnStatus == RPN_STATUS_NRPN) {
                                rpnNrpnFunction = ((nrpnFunctionMsb & 0x7f) << 7) | (nrpnFunctionLsb & 0x7f);
                                rpnNrpnValueMsb = nrpnCacheMsb.get(rpnNrpnFunction, 0/*if not found*/);
                                nrpnCacheLsb.put(rpnNrpnFunction, rpnNrpnValueLsb);
                                addEventToQueue(new MidiEvent(midiEventKind, rpnNrpnFunction, (rpnNrpnValueMsb << 7 | rpnNrpnValueLsb)) {
                                    @Override
                                    public void run() {
                                        if (midiInputEventListener != null) {
                                            midiInputEventListener.onNRPNMessage(sender, getArg1() & 0xf, getArg2() & 0x3fff, getArg3() & 0x3fff);
                                        }
                                    }
                                });
                            }
                            break;
                        }
                        case 98: {
                            // NRPN parameter number LSB
                            nrpnFunctionLsb = midiEventVelocity & 0x7f;
                            rpnStatus = RPN_STATUS_NRPN;
                            break;
                        }
                        case 99: {
                            // NRPN parameter number MSB
                            nrpnFunctionMsb = midiEventVelocity & 0x7f;
                            rpnStatus = RPN_STATUS_NRPN;
                            break;
                        }
                        case 100: {
                            // RPN parameter number LSB
                            rpnFunctionLsb = midiEventVelocity & 0x7f;
                            if (rpnFunctionMsb == 0x7f && rpnFunctionLsb == 0x7f) {
                                rpnStatus = RPN_STATUS_NONE;
                            } else {
                                rpnStatus = RPN_STATUS_RPN;
                            }
                            break;
                        }
                        case 101: {
                            // RPN parameter number MSB
                            rpnFunctionMsb = midiEventVelocity & 0x7f;
                            if (rpnFunctionMsb == 0x7f && rpnFunctionLsb == 0x7f) {
                                rpnStatus = RPN_STATUS_NONE;
                            } else {
                                rpnStatus = RPN_STATUS_RPN;
                            }
                            break;
                        }
                        default:
                            // do nothing
                            break;
                    }

                    addEventToQueue(new MidiEvent(midiEventKind, midiEventNote, midiEventVelocity) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                midiInputEventListener.onMidiControlChange(sender, getArg1() & 0xf, getArg2(), getArg3());
                            }
                        }
                    });
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
                case 0xe0: // pitch bend
                    midiEventVelocity = midiEvent;
                    addEventToQueue(new MidiEvent(midiEventKind, midiEventNote, midiEventVelocity) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                midiInputEventListener.onMidiPitchWheel(sender, getArg1() & 0xf, (getArg2() & 0x7f) | ((getArg3() & 0x7f) << 7));
                            }
                        }
                    });
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
                case 0xf0: // Song Position Pointer.
                    midiEventVelocity = midiEvent;
                    addEventToQueue(new MidiEvent(midiEventNote, midiEventVelocity) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                midiInputEventListener.onMidiSongPositionPointer(sender, (getArg1() & 0x7f) | ((getArg2() & 0x7f) << 7));
                            }
                        }
                    });
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
                default:
                    // illegal state
                    midiState = MIDI_STATE_TIMESTAMP;
                    break;
            }
        } else if (midiState == MIDI_STATE_SIGNAL_SYSEX) {
            // Need this coercion, otherwise the sysex end gets sign extended then compared
            // as an int, which is negative, and will always be false.
            if ((byte) midiEvent == MIDIStatus_SysExEnd.value) {        // force 8-bit comparison
                // the end of message
                synchronized (systemExclusiveLock) {
                    // last written byte is for timestamp
                    int replacedEvent = systemExclusiveStream.replaceLastByte(midiEvent);
                    if (replacedEvent >= 0) {
                        timestamp = ((header & 0x3f) << 7) | (replacedEvent & 0x7f);
                    }
                    addEventToQueue(new MidiEvent(systemExclusiveStream.toByteArray()) {
                        @Override
                        public void run() {
                            if (midiInputEventListener != null) {
                                midiInputEventListener.onMidiSystemExclusive(sender, getArray());
                            }
                        }
                    });

                    // for error recovery
                    systemExclusiveRecoveryStream.reset();
                    try {
                        systemExclusiveStream.writeTo(systemExclusiveRecoveryStream);
                    } catch (IOException ignored) {
                    }
                    systemExclusiveRecoveryStream.replaceLastByte(replacedEvent);
                    systemExclusiveRecoveryStream.write(midiEvent);
                }
                midiState = MIDI_STATE_TIMESTAMP;
            } else {
                synchronized (systemExclusiveLock) {
                    systemExclusiveStream.write(midiEvent);
                }
            }
        }
    }
    
    /**
     * Updates incoming data
     *
     * @param data incoming data
     */
    public synchronized void parse(@NonNull byte[] data) {
        if (!isTerminated && isRunning) {
            if (data.length > 1) {
                int header = data[0] & 0xff;
                for (int i = 1; i < data.length; i++) {
                    parseMidiEvent(header, data[i]);
                }
            }
        }
    }

    private final LinkedTransferQueue<MidiEvent> eventTransferQueue = new LinkedTransferQueue<MidiEvent>();

    final Thread transferMessageThread = new Thread(new Runnable() {

        private MidiEvent takeFirstEvent() {
            MidiEvent anEvent = null;
            do {
                try {
                    anEvent = eventTransferQueue.take();        // This blocks.
                } catch (InterruptedException e) {
                    if (isTerminated || !isRunning) {
                        break;
                    }
                    // shrug, just try again.
                }
            } while (anEvent == null);
            return anEvent;
        }

        @Override
        public void run() {
            isRunning = true;

            do {
                // Pull messages off the queue as they become available and run() them.
                MidiEvent anEvent = takeFirstEvent();     // this blocks
                if (anEvent != null) {      // can be null if we are terminating
                    anEvent.run();
                }
            } while (isRunning && !isTerminated);
        }
    });
    
    /**
     * Add a event to event queue
     * @param event the MIDI Event
     *
     */
    private void addEventToQueue(MidiEvent event) {
        printEvent(event);
        eventTransferQueue.add(event);
    }

    private void printEvent(MidiEvent event) {
        // Print the entire packet as a single string of bytes, ready to init a byte[] with, like this:
        //    { 0x00, 0x01, 0x02 }
        StringBuilder sb = new StringBuilder();
        sb.append("{ ");
        if (event.getArg1() != MidiEvent.INVALID) {
            sb.append(String.format("0x%02X", event.getArg1()));
        }
        if (event.getArg2() != MidiEvent.INVALID) {
            sb.append(String.format(", 0x%02X", event.getArg2()));
        }
        if (event.getArg3() != MidiEvent.INVALID) {
            sb.append(String.format(", 0x%02X", event.getArg3()));
        }
        if ((event.getArray() != null) && (event.getArray().length > 0)) {
            for (int anIndex = 0; anIndex < event.getArray().length; anIndex++) {
                byte aByte = event.getArray()[anIndex];
                if (anIndex == (event.getArray().length - 1)) {
                    sb.append(String.format(", 0x%02X", aByte));
                } else if (anIndex == 0) {
                    if (event.getArg1() == MidiEvent.INVALID) {
                        sb.append(String.format("0x%02X", aByte));
                    } else {
                        sb.append(String.format(", 0x%02X", aByte));
                    }
                } else {
                    sb.append(String.format(", 0x%02X", aByte));
                }
            }
        }
        Log.d("EVENT", String.valueOf(sb) + " }");
    }
}
