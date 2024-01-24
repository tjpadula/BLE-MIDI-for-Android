package jp.kshoji.blemidi.device;

import static jp.kshoji.blemidi.util.ControlChange.*;
import static jp.kshoji.blemidi.util.MIDIStatus.*;

import android.content.BroadcastReceiver;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.Semaphore;

/**
 * Represents BLE MIDI Output Device
 *
 * @author K.Shoji
 */
public abstract class MidiOutputDevice {

    public static final int MAX_TIMESTAMP = 8192;

    public final Semaphore writeToBTSemaphore = new Semaphore(1);

    private final LinkedTransferQueue<MessageWithTimestamp> midiTransferQueue = new LinkedTransferQueue<MessageWithTimestamp>();

    /**
     * Transfer data
     *
     * @param writeBuffer byte array to write
     */
    protected abstract void transferData(@NonNull byte[] writeBuffer);

    /**
     * Obtains the device name
     *
     * @return device name
     */
    @NonNull
    public abstract String getDeviceName();

    /**
     * Obtains the manufacturer name
     *
     * @return manufacturer name
     */
    @NonNull
    public abstract String getManufacturer();

    /**
     * Obtains the model name
     *
     * @return model name
     */
    @NonNull
    public abstract String getModel();

    /**
     * Obtains the device address
     *
     * @return device address
     */
    @NonNull
    public abstract String getDeviceAddress();

    /**
     * Obtains buffer size
     * @return buffer size
     */
    public abstract int getBufferSize();

    @NonNull
    @Override
    public final String toString() {
        return getDeviceName();
    }

    /**
     * This thread takes new MIDI events off the MIDI message transfer queue and sends them
     * to the BT hardware only when the hardware is ready. There are no spin locks, as it uses
     * a semaphore for flow control with the hardware. Data is sent on a best-effort basis.
     */
    volatile boolean transferDataThreadAlive;
    volatile boolean isRunning;
    final Thread transferDataThread = new Thread(new Runnable() {

        final int kMaxPacketBufferSize = 20;

        private void acquireBTWriteSemaphore() {
            boolean acquiredSuccessfully = false;
            do {
                try {
                    writeToBTSemaphore.acquire();       // This blocks.
                    acquiredSuccessfully = true;
                } catch (InterruptedException e) {
                    continue;       // If we were interrupted, just try again.
                }
            } while (!acquiredSuccessfully);
        }

        private MessageWithTimestamp takeFirstMessage() {
            MessageWithTimestamp aMessage = null;
            do {
                try {
                    aMessage = midiTransferQueue.take();        // This blocks.
                } catch (InterruptedException e) {
                    // shrug, just try again.
                }
            } while (aMessage == null);
            return aMessage;
        }

        private void sendSysexMessage(byte[] inMessage, byte inTimestampHi, byte inTimestampLo) {
            int aBytesUsed = 0;
            final ByteArrayOutputStream aPacketDataStream = new ByteArrayOutputStream();
            boolean aContinuingSysex = false;
            byte[] aWriteMessage = new byte[0];
            do {
                aPacketDataStream.reset();
                if (aContinuingSysex) {
                    // Continuing packets have only the header byte.
                    aPacketDataStream.write(inTimestampHi);
                    aBytesUsed = 1;
                } else {
                    // The first sysex packet has both header and timestamp lo.
                    aPacketDataStream.write(inTimestampHi);
                    aPacketDataStream.write(inTimestampLo);
                    aBytesUsed = 2;
                }

                // The +1 here is for the timestamp before the end-sysex status byte.
                if (inMessage.length < (kMaxPacketBufferSize - (aBytesUsed + 1))) {
                    // The remainder will fit. Copy all but the end-sysex status byte so
                    // we can insert the timestamp lo byte before end-sysex.
                    for (int anIndex = 0; anIndex < inMessage.length - 1; anIndex++) {      // all but final sysex
                        aPacketDataStream.write(inMessage[anIndex]);
                    }
                    aPacketDataStream.write(inTimestampLo);
                    aPacketDataStream.write(inMessage[inMessage.length - 1]);  // end sysex
                    inMessage = new byte[0];     // all done.
                } else {
                    // The remainder of the message won't fit. Insert what we can.
                    aWriteMessage = Arrays.copyOf(inMessage, (kMaxPacketBufferSize - (aBytesUsed + 1)));
                    try {
                        aPacketDataStream.write(aWriteMessage);
                    } catch (IOException e) {
                        transferDataThreadAlive = false;        // bail
                        Log.d("NSLOG", "Interrupted aPacketDataStream.write()");
                        break;
                    }
                    // Copy the rest for next time around.
                    inMessage = Arrays.copyOfRange(inMessage, (kMaxPacketBufferSize - (aBytesUsed + 1)), inMessage.length);
                    aContinuingSysex = true;        // Indicate we need a following packet.
                }

                Log.d("NSLOG", "transferDataThread.run: writing byte count: " + aPacketDataStream.size());
                byte[] aPrintArray = aPacketDataStream.toByteArray();
                transferData(aPacketDataStream.toByteArray());
                aPacketDataStream.reset();

                printPacket(aPrintArray);

            } while (inMessage.length > 0);
        }

        // Returns number of bytes in the packet that were used for this message including timestamps.
        private int addStandardMessageWithFirstHeader(byte[] inMessage, byte inTimestampHi, byte inTimestampLo, ByteArrayOutputStream inPacketDataStream) {
            // It fits.
            inPacketDataStream.write(inTimestampHi);       // header
            inPacketDataStream.write(inTimestampLo);
            try {
                inPacketDataStream.write(inMessage);
            } catch (IOException e) {
                transferDataThreadAlive = false;        // bail
                Log.d("NSLOG", "Interrupted packetDataStream.write()");
                return 0;
            }
            return 2 + inMessage.length;
        }

        // Returns number of bytes in the packet that were used for this message including timestamp.
        private int addStandardMessageWithFollowingHeader(byte[] inMessage, byte inTimestampLo, ByteArrayOutputStream inPacketDataStream) {
            try {
                inPacketDataStream.write(inTimestampLo);
                inPacketDataStream.write(inMessage);
            } catch (IOException e) {
                transferDataThreadAlive = false;        // bail
                Log.d("NSLOG", "Interrupted packetDataStream.write()");
            }
            return inMessage.length + 1;
        }

        @Override
        public void run() {
            transferDataThreadAlive = true;
            final ByteArrayOutputStream aPacketDataStream = new ByteArrayOutputStream();

            while (transferDataThreadAlive) {

                // Block until we can acquire the write-to-BT semaphore. Then we block until we
                // can take the most recent data from the queue. Then we create a proper BT packet
                // with as much as will fit, then transfer the data. Then we block until we can
                // acquire the semaphore again. The BLEMIDICallback will release the semaphore
                // when the packet has been sent to the hardware and the system is ready for
                // another packet.

                int aBytesUsed = 0;

                this.acquireBTWriteSemaphore();

                // We have the semaphore. Assemble as much data as will fit in a packet
                // and send it along.
                MessageWithTimestamp aMessageWithTimestamp = this.takeFirstMessage();
                byte[] aMessage = aMessageWithTimestamp.getMessage();

                // What kind of message do we have?
                if (aMessage[0] == MIDIStatus_SysExStart.value) {

                    if (aMessage[aMessage.length - 1] != MIDIStatus_SysExEnd.value) {   // malformed sysex message
                        Log.d("NSLOG", "Sysex message does not end with sysex end status byte: " + aMessage[aMessage.length - 1]);
                        printPacket(aMessage);
                        continue;
                    }
                    this.sendSysexMessage(aMessage,
                            aMessageWithTimestamp.getTimestampHi(),
                            aMessageWithTimestamp.getTimestampLo());

                    // Yes, conceivably another message from the queue could fit after the
                    // last sysex message. Start fresh anyway.
                    continue;

                } else if (aMessage.length < (kMaxPacketBufferSize - aBytesUsed)) { // Will the message fit?
                    aBytesUsed += this.addStandardMessageWithFirstHeader(aMessage,
                            aMessageWithTimestamp.getTimestampHi(),
                            aMessageWithTimestamp.getTimestampLo(),
                            aPacketDataStream);
                } else {
                    // This message is too long and it's not sysex --?
                    // Skip it.
                    Log.d("NSLOG", "Skipping long non-sysex message, length: " + aMessage.length);
                    printPacket(aMessage);
                    continue;
                }
                if (!transferDataThreadAlive || !isRunning) {
                    break;
                }

                // Check for the next message. If it will fit, add it, then check again.
                MessageWithTimestamp aNextMessageWithTimestamp = midiTransferQueue.peek();     // can't block
                while (aNextMessageWithTimestamp != null) {
                    byte[] aNextMessage = aNextMessageWithTimestamp.getMessage();

                    if (aNextMessage[0] == MIDIStatus_SysExStart.value) {
                        // The next message is sysex, send the current packet and then
                        // loop around and send the sysex message as a new packet.
                        break;
                    }

                    // Ok, it's a regular message, will it fit? The +1 here is for the timestamp lo.
                    if (aNextMessage.length >= (kMaxPacketBufferSize - (aBytesUsed + 1))) {
                        // The message won't fit, leave it in the queue and send
                        // what we have.
                        break;
                    }

                    // The message will fit. Go take it off the queue and append it to the packet.
                    aMessageWithTimestamp = this.takeFirstMessage();        // won't block
                    aBytesUsed += this.addStandardMessageWithFollowingHeader(aMessageWithTimestamp.getMessage(),
                            aMessageWithTimestamp.getTimestampLo(),
                            aPacketDataStream);

                    // Check the new next message.
                    aNextMessageWithTimestamp = midiTransferQueue.peek();   // can't block
                }
                if (!transferDataThreadAlive || !isRunning) {
                    break;
                }

                // Either the queue is empty, the packet stream is full, or the next message will
                // be sysex. Send it out the door.
                Log.d("NSLOG", "transferDataThread.run: writing byte count: " + aPacketDataStream.size());
                byte[] aPrintArray = aPacketDataStream.toByteArray();
                transferData(aPacketDataStream.toByteArray());
                aPacketDataStream.reset();

                printPacketLine(aPrintArray);
            }
        }
    });

    protected void printPacket(byte[] inPrintArray) {
        // Dump the packet data as hex. For each byte with the high bit set,
        // we start a new line for clarity.
        Log.d("NSLOG", "MIDI data written: ");
        StringBuilder sb = new StringBuilder();
        for (byte aByte : inPrintArray) {
            if ((aByte & MIDIStatus_StatusBitMask.value) == MIDIStatus_StatusBit.value) {       // If this is a status byte...
                if (sb.length() > 0) {          // ...print the previous set of packet data
                    Log.d("NSLOG", "0x" + sb);
                    sb = new StringBuilder();
                }
            }
            sb.append(String.format("%02X ", aByte));
        }

        // Print the final set of packet data.
        if (sb.length() > 0) {
            Log.d("NSLOG", "0x" + sb);
        }
    }

    // Print the entire packet as a single string of bytes, ready to init a byte[] with, like this:
    //    { 0x00, 0x01, 0x02 }
    protected void printPacketLine(byte[] inPrintArray) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ ");
        for (int anIndex = 0; anIndex < inPrintArray.length; anIndex++) {
            byte aByte = inPrintArray[anIndex];
            if (anIndex == (inPrintArray.length - 1)) {
                sb.append(String.format("0x%02X }", aByte));
            } else {
                sb.append(String.format("0x%02X, ", aByte));
            }
        }
        Log.d("PACKET", String.valueOf(sb));
    }

    protected MidiOutputDevice() {
        transferDataThread.start();
    }

    /**
     * Starts using the device
     */
    public final void start() {
        if (!transferDataThreadAlive) {
            return;
        }
        isRunning = true;
        transferDataThread.interrupt();
    }

    /**
     * Stops using the device
     */
    public final void stop() {
        if (!transferDataThreadAlive) {
            return;
        }
        isRunning = false;
        transferDataThread.interrupt();
    }

    /**
     * Terminates the device instance
     */
    public final void terminate() {
        transferDataThreadAlive = false;
        isRunning = false;
        transferDataThread.interrupt();
    }

    private class MessageWithTimestamp  {

        private final byte mTimestampHi;
        private final byte mTimestampLo;
        private final byte[] mMessage;

        MessageWithTimestamp(byte[] data) {

            // What time is it?
            long aTimestamp = System.currentTimeMillis() % MAX_TIMESTAMP;
            mTimestampHi = (byte) (0x80 | ((aTimestamp >> 7) & 0x3f));
            mTimestampLo = (byte) (0x80 | (aTimestamp & 0x7f));

            mMessage = data;
        }

        byte getTimestampHi() {
            return mTimestampHi;
        }
        byte getTimestampLo() {
            return mTimestampLo;
        }
        byte[] getMessage() {
            return mMessage;
        }
    }


    private void storeTransferData(byte[] data) {

        // We will not create packets here. Instead, we add the incoming byte array
        // (a complete MIDI message) to our queue, which the transfer thread pulls
        // from. It is responsible for forming the packets.
        // Add the current hi/lo timestamps to this. Then when we pull the messages
        // from the queue they already have timestamp info ready at the moment they
        // arrived. Yes, we have to check for size and skip the timestamp hi byte
        // but this allows us to ensure that the actual timing of the MIDI messages
        // gets included at the receiving end, however useless that information
        // may be.

        midiTransferQueue.add(new MessageWithTimestamp(data));

/*
            long timestamp = System.currentTimeMillis() % MAX_TIMESTAMP;
            if (writtenDataCount == 0) {
                // Store timestamp high
                transferDataStream.write((byte) (0x80 | ((timestamp >> 7) & 0x3f)));
                writtenDataCount++;
            }
            // timestamp low
            transferDataStream.write((byte) (0x80 | (timestamp & 0x7f)));
            writtenDataCount++;
            try {
                transferDataStream.write(data);
                writtenDataCount += data.length;
            } catch (IOException ignored) {
            }

 */
        Log.d("NSLOG", "storeTransferData: added data of length: " + data.length);
    }

    /**
     * Sends MIDI message to output device.
     *
     * @param byte1 the first byte
     */
    private void sendMidiMessage(int byte1) {
        storeTransferData(new byte[] { (byte) byte1 });
    }

    /**
     * Sends MIDI message to output device.
     *
     * @param byte1 the first byte
     * @param byte2 the second byte
     */
    private void sendMidiMessage(int byte1, int byte2) {
        storeTransferData(new byte[] { (byte) byte1, (byte) byte2 });
    }

    /**
     * Sends MIDI message to output device.
     *
     * @param byte1 the first byte
     * @param byte2 the second byte
     * @param byte3 the third byte
     */
    private void sendMidiMessage(int byte1, int byte2, int byte3) {
        storeTransferData(new byte[] { (byte) byte1, (byte) byte2, (byte) byte3 });
    }

    /**
     * SysEx
     *
     * @param systemExclusive : start with 'F0', and end with 'F7'
     *
     * This does not handle the per-buffer timestamps properly.
     * Tinkering with a timestamp value to avoid breaking the parser elsewhere in
     * this module is no bueno - it could violate the monotonically increasing requirement.
     * System real-time messages are not properly inserted with sysex, as they dispatch
     * through the transfer data thread above.
     * And, as in storeTransferData(), this can firehose the BT hardware.
     *
     */
    public final void sendMidiSystemExclusive(@NonNull byte[] systemExclusive) {

        midiTransferQueue.add(new MessageWithTimestamp(systemExclusive));
/*
        byte[] timestampAddedSystemExclusive = new byte[systemExclusive.length + 2];
        System.arraycopy(systemExclusive, 0, timestampAddedSystemExclusive, 1, systemExclusive.length);

        long timestamp = System.currentTimeMillis() % MAX_TIMESTAMP;

        // extend a byte for timestamp LSB, before the last byte('F7')
        timestampAddedSystemExclusive[systemExclusive.length + 1] = systemExclusive[systemExclusive.length - 1];
        // set first byte to timestamp LSB
        timestampAddedSystemExclusive[0] = (byte) (0x80 | (timestamp & 0x7f));

        // split into bufferSize bytes. BLE can't send more than (bufferSize: MTU - 3) bytes.
        int bufferSize = getBufferSize();
        byte[] writeBuffer = new byte[bufferSize];
        for (int i = 0; i < timestampAddedSystemExclusive.length; i += (bufferSize - 1)) {
            // Don't send 0xF7 timestamp LSB inside of SysEx(MIDI parser will fail) 0x7f -> 0x7e
            timestampAddedSystemExclusive[systemExclusive.length] = (byte) (0x80 | (timestamp & 0x7e));

            if (i + (bufferSize - 1) <= timestampAddedSystemExclusive.length) {
                System.arraycopy(timestampAddedSystemExclusive, i, writeBuffer, 1, (bufferSize - 1));
            } else {
                // last message
                writeBuffer = new byte[timestampAddedSystemExclusive.length - i + 1];

                System.arraycopy(timestampAddedSystemExclusive, i, writeBuffer, 1, timestampAddedSystemExclusive.length - i);
            }

            // timestamp MSB
            writeBuffer[0] = (byte) (0x80 | ((timestamp >> 7) & 0x3f));

            // immediately transfer data
            transferData(writeBuffer);

            timestamp = System.currentTimeMillis() % MAX_TIMESTAMP;
        }

 */
    }

    /**
     * Note-off
     *
     * @param channel 0-15
     * @param note 0-127
     * @param velocity 0-127
     */
    public final void sendMidiNoteOff(int channel, int note, int velocity) {
        sendMidiMessage(MIDIStatus_NoteOff.value | (channel & MIDIStatus_ChannelMask.value), note, velocity);
    }

    /**
     * Note-on
     *
     * @param channel 0-15
     * @param note 0-127
     * @param velocity 0-127
     */
    public final void sendMidiNoteOn(int channel, int note, int velocity) {
        sendMidiMessage(MIDIStatus_NoteOn.value | (channel & MIDIStatus_ChannelMask.value), note, velocity);
    }

    /**
     * Poly-KeyPress
     *
     * @param channel 0-15
     * @param note 0-127
     * @param pressure 0-127
     */
    public final void sendMidiPolyphonicAftertouch(int channel, int note, int pressure) {
        sendMidiMessage(MIDIStatus_PolyphonicAfterTouch.value | (channel & MIDIStatus_ChannelMask.value), note, pressure);
    }

    /**
     * Control Change
     *
     * @param channel 0-15
     * @param function 0-127
     * @param value 0-127
     */
    public final void sendMidiControlChange(int channel, int function, int value) {
        sendMidiMessage(MIDIStatus_ControlChange.value | (channel & MIDIStatus_ChannelMask.value), function, value);
    }

    /**
     * Program Change
     *
     * @param channel 0-15
     * @param program 0-127
     */
    public final void sendMidiProgramChange(int channel, int program) {
        sendMidiMessage(MIDIStatus_ProgramChange.value | (channel & MIDIStatus_ChannelMask.value), program);
    }

    /**
     * Channel Pressure
     *
     * @param channel 0-15
     * @param pressure 0-127
     */
    public final void sendMidiChannelAftertouch(int channel, int pressure) {
        sendMidiMessage(MIDIStatus_ChannelAfterTouch.value | (channel & MIDIStatus_ChannelMask.value), pressure);
    }

    /**
     * PitchBend Change
     *
     * @param channel 0-15
     * @param amount 0(low)-8192(center)-16383(high)
     */
    public final void sendMidiPitchWheel(int channel, int amount) {
        sendMidiMessage(MIDIStatus_PitchWheel.value | (channel & MIDIStatus_ChannelMask.value), amount & MIDIStatus_ValueBitMask.value, (amount >> 7) & MIDIStatus_ValueBitMask.value);
    }

    /**
     * MIDI Time Code(MTC) Quarter Frame
     *
     * @param timing 0-127
     */
    public final void sendMidiTimeCodeQuarterFrame(int timing) {
        sendMidiMessage(MIDIStatus_TimeCode.value, timing & MIDIStatus_ValueBitMask.value);
    }

    /**
     * Song Select
     *
     * @param song 0-127
     */
    public final void sendMidiSongSelect(int song) {
        sendMidiMessage(MIDIStatus_SongSelect.value, song & MIDIStatus_ValueBitMask.value);
    }

    /**
     * Song Position Pointer
     *
     * @param position 0-16383
     */
    public final void sendMidiSongPositionPointer(int position) {
        sendMidiMessage(MIDIStatus_SongPositionPointer.value, position & MIDIStatus_ValueBitMask.value, (position >> 7) & MIDIStatus_ValueBitMask.value);
    }

    /**
     * Tune Request
     */
    public final void sendMidiTuneRequest() {
        sendMidiMessage(MIDIStatus_TuneRequest.value);
    }

    /**
     * Timing Clock
     */
    public final void sendMidiTimingClock() {
        sendMidiMessage(MIDIStatus_TimingClock.value);
    }

    /**
     * Start Playing
     */
    public final void sendMidiStart() {
        sendMidiMessage(MIDIStatus_Start.value);
    }

    /**
     * Continue Playing
     */
    public final void sendMidiContinue() {
        sendMidiMessage(MIDIStatus_Continue.value);
    }

    /**
     * Stop Playing
     */
    public final void sendMidiStop() {
        sendMidiMessage(MIDIStatus_Stop.value);
    }

    /**
     * Active Sensing
     */
    public final void sendMidiActiveSensing() {
        sendMidiMessage(MIDIStatus_ActiveSensing.value);
    }

    /**
     * Reset Device
     */
    public final void sendMidiReset() {
        sendMidiMessage(MIDIStatus_Reset.value);
    }

    /**
     * RPN message
     *
     * @param channel 0-15
     * @param function 14bits
     * @param value 7bits or 14bits
     */
    public final void sendRPNMessage(int channel, int function, int value) {
        sendRPNMessage(channel, (function >> 7) & MIDIStatus_ValueBitMask.value, function & MIDIStatus_ValueBitMask.value, value);
    }

    /**
     * RPN message
     *
     * @param channel 0-15
     * @param functionMSB higher 7bits
     * @param functionLSB lower 7bits
     * @param value 7bits or 14bits
     */
    public final void sendRPNMessage(int channel, int functionMSB, int functionLSB, int value) {
        // send the function
        sendMidiControlChange(channel, ControlChange_RegisteredParameterNumberMSB.value, functionMSB & MIDIStatus_ValueBitMask.value);
        sendMidiControlChange(channel, ControlChange_RegisteredParameterNumberLSB.value, functionLSB & MIDIStatus_ValueBitMask.value);

        // send the value
        if ((value >> 7) > 0) {
            sendMidiControlChange(channel, ControlChange_DataEntryMSB.value, (value >> 7) & MIDIStatus_ValueBitMask.value);
            sendMidiControlChange(channel, ControlChange_DataEntryLSB.value, value & MIDIStatus_ValueBitMask.value);
        } else {
            sendMidiControlChange(channel, ControlChange_DataEntryMSB.value, value & MIDIStatus_ValueBitMask.value);
        }

        // send the NULL function
        sendMidiControlChange(channel, ControlChange_RegisteredParameterNumberMSB.value, ControlChange_NullFunction.value);
        sendMidiControlChange(channel, ControlChange_RegisteredParameterNumberLSB.value, ControlChange_NullFunction.value);
    }

    /**
     * NRPN message
     *
     * @param channel 0-15
     * @param function 14bits
     * @param value 7bits or 14bits
     */
    public final void sendNRPNMessage(int channel, int function, int value) {
        sendNRPNMessage(channel, (function >> 7) & MIDIStatus_ValueBitMask.value, function & MIDIStatus_ValueBitMask.value, value);
    }

    /**
     * NRPN message
     *
     * @param channel 0-15
     * @param functionMSB higher 7bits
     * @param functionLSB lower 7bits
     * @param value 7bits or 14bits
     */
    public final void sendNRPNMessage(int channel, int functionMSB, int functionLSB, int value) {
        // send the function
        sendMidiControlChange(channel, ControlChange_NonRegisteredParameterNumberLSB.value, functionMSB & MIDIStatus_ValueBitMask.value);
        sendMidiControlChange(channel, ControlChange_NonRegisteredParameterNumberLSB.value, functionLSB & MIDIStatus_ValueBitMask.value);

        // send the value
        if ((value >> 7) > 0) {
            sendMidiControlChange(channel, ControlChange_DataEntryMSB.value, (value >> 7) & MIDIStatus_ValueBitMask.value);
            sendMidiControlChange(channel, ControlChange_DataEntryLSB.value, value & MIDIStatus_ValueBitMask.value);
        } else {
            sendMidiControlChange(channel, ControlChange_DataEntryMSB.value, value & MIDIStatus_ValueBitMask.value);
        }

        // send the NULL function
        sendMidiControlChange(channel, ControlChange_NonRegisteredParameterNumberLSB.value, ControlChange_NullFunction.value);
        sendMidiControlChange(channel, ControlChange_NonRegisteredParameterNumberLSB.value, ControlChange_NullFunction.value);
    }
}
