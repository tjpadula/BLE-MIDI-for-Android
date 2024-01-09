package jp.kshoji.blemidi.device;

import static jp.kshoji.blemidi.util.MIDIStatus.MIDIStatus_SysExEnd;
import static jp.kshoji.blemidi.util.MIDIStatus.MIDIStatus_SysExStart;

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

    private final LinkedTransferQueue<byte[]> midiTransferQueue = new LinkedTransferQueue<byte[]>();

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
     * This method does not have any way to prevent it from firehosing the BT system. If
     * a transfer takes more than 10 mSec to be sent, then this call could either block (bad)
     * or clobber outgoing data (worse). Turns out calling transferData() before the hardware
     * is ready clobbers data.
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

        private byte[] takeFirstMessage() {
            byte[] aMessage = null;
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
            final ByteArrayOutputStream packetDataStream = new ByteArrayOutputStream();
            boolean aContinuingSysex = false;
            byte[] aWriteMessage = new byte[0];
            do {
                packetDataStream.reset();
                if (aContinuingSysex) {
                    // Continuing packets have only the header byte.
                    packetDataStream.write(inTimestampHi);
                    aBytesUsed = 1;
                } else {
                    // The first sysex packet has both header and timestamp lo.
                    packetDataStream.write(inTimestampHi);
                    packetDataStream.write(inTimestampLo);
                    aBytesUsed = 2;
                }

                // The +1 here is for the timestamp before the end-sysex status byte.
                if (inMessage.length < (kMaxPacketBufferSize - (aBytesUsed + 1))) {
                    // The remainder will fit. Copy all but the end-sysex status byte so
                    // we can insert the timestamp lo byte before end-sysex.
                    System.arraycopy(inMessage,     // source
                            0,                      // source start location
                            aWriteMessage,          // destination
                            0,                      // destination start location
                            inMessage.length - 1);  // num to copy
                    try {
                        packetDataStream.write(aWriteMessage);
                    } catch (IOException e) {
                        transferDataThreadAlive = false;        // bail
                        Log.d("NSLOG", "Interrupted packetDataStream.write()");
                        break;
                    }
                    packetDataStream.write(inTimestampLo);
                    packetDataStream.write(inMessage[inMessage.length - 1]);  // end sysex
                    inMessage = new byte[0];     // all done.
                } else {
                    // The remainder of the message won't fit. Insert what we can.
                    aWriteMessage = Arrays.copyOf(inMessage, (kMaxPacketBufferSize - (aBytesUsed + 1)));
                    try {
                        packetDataStream.write(aWriteMessage);
                    } catch (IOException e) {
                        transferDataThreadAlive = false;        // bail
                        Log.d("NSLOG", "Interrupted packetDataStream.write()");
                        break;
                    }
                    // Copy the rest for next time around.
                    inMessage = Arrays.copyOfRange(inMessage, (kMaxPacketBufferSize - (aBytesUsed + 1)), inMessage.length);
                    aContinuingSysex = true;        // Indicate we need a following packet.
                }

                Log.d("NSLOG", "transferDataThread.run: writing byte count: " + writtenDataCount);
                byte[] aPrintArray = packetDataStream.toByteArray();
                transferData(packetDataStream.toByteArray());
                packetDataStream.reset();

                printPacket(aPrintArray);

            } while (inMessage.length > 0);
        }

        // Returns number of bytes in the packet that were used for this message including timestamps.
        private int sendStandardMessageWithFirstHeader(byte[] inMessage, byte inTimestampHi, byte inTimestampLo, ByteArrayOutputStream inPacketDataStream) {
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
        private int sendStandardMessageWithFollowingHeader(byte[] inMessage, byte inTimestampLo, ByteArrayOutputStream inPacketDataStream) {
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
            final ByteArrayOutputStream packetDataStream = new ByteArrayOutputStream();

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
                byte[] aMessage = this.takeFirstMessage();

                // We have the first message for the packet. What time is it?
                long timestamp = System.currentTimeMillis() % MAX_TIMESTAMP;
                byte aTimestampHi = (byte) (0x80 | ((timestamp >> 7) & 0x3f));
                byte aTimestampLo = (byte) (0x80 | (timestamp & 0x7f));

                // What kind of message do we have?
                if (aMessage[0] == MIDIStatus_SysExStart.value) {
                    if (aMessage[aMessage.length - 1] != MIDIStatus_SysExEnd.value) {
                        Log.d("NSLOG", "Sysex message does not end with sysex end status byte: " + aMessage[aMessage.length - 1]);
                        printPacket(aMessage);
                        continue;
                    }
                    this.sendSysexMessage(aMessage, aTimestampHi, aTimestampLo);

                    // Yes, conceivably another message from the queue could fit after the
                    // last sysex message. Start fresh anyway.
                    continue;

                } else if (aMessage.length < (kMaxPacketBufferSize - aBytesUsed)) { // Will the message fit?
                    aBytesUsed += this.sendStandardMessageWithFirstHeader(aMessage, aTimestampHi, aTimestampLo, packetDataStream);
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
                byte[] aNextMessage = midiTransferQueue.peek();     // can't block
                while (aNextMessage != null) {
                    if (aMessage[0] == MIDIStatus_SysExStart.value) {
                        // It's sysex, loop around and send it as a new packet.
                        break;
                    }
                    // Ok, it's a regular message, will it fit? The +1 here is for the timestamp lo.
                    if (aNextMessage.length >= (kMaxPacketBufferSize - (aBytesUsed + 1))) {
                        // The message won't fit, leave it in the queue and send
                        // what we have.
                        break;
                    }
                    // The message will fit. Go take it off the queue and append it to the packet.
                    aMessage = this.takeFirstMessage();
                    aBytesUsed += this.sendStandardMessageWithFollowingHeader(aMessage, aTimestampLo, packetDataStream);
                    aNextMessage = midiTransferQueue.peek();     // can't block
                }
                if (!transferDataThreadAlive || !isRunning) {
                    break;
                }

                // Either the queue is empty or the packet stream is full. Send it out the door.
                Log.d("NSLOG", "transferDataThread.run: writing byte count: " + writtenDataCount);
                byte[] aPrintArray = packetDataStream.toByteArray();
                transferData(packetDataStream.toByteArray());
                packetDataStream.reset();

                printPacket(aPrintArray);
            }
        }
    });

    protected void printPacket(byte[] inPrintArray) {
        // Dump the packet data as hex. For each byte with the high bit set,
        // we start a new line for clarity.
        Log.d("NSLOG", "MIDI data written: ");
        StringBuilder sb = new StringBuilder();
        for (byte aByte : inPrintArray) {
            if ((aByte & 0x80) == 0x80) {       // If this is a status byte...
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

    /**
     * This is broken in several ways:
     *      It has no flow control, that is, it can firehose the BT hardware.
     *      It also does not check for excessive size of transferDataStream, which
     *          can typically handle 20 bytes max, and break up packets as needed. Multiple
     *          calls in a short time can overwhelm packet limits (such as an RPN call).
     *      It does not properly deal with running status when starting a new packet.
     *      It does not handle sysex properly.
     *      It does not handle real-time system messages properly, that is, it does not
     *          de-interleave them from non-sysex messages.
     *      Timestamps are not guaranteed to be monotonically increasing, the same low timestamp
     *          could be re-sent if multiple calls are made within the same millisecond.
     *
     * The timestamps are actually the big issue. We should buffer up messages as they come in
     * without any time info and then create a proper packet of limited size and send it
     * when BT indicates that it is ready. This keeps us from violating the monotonically
     * increasing requirement while also preventing firehosing. It also keeps us from having
     * to worry about violating the 'timestamps in the future' prohibition. A three-stream
     * pattern of data messages can do this efficiently.
     *
     */
    transient int writtenDataCount;
    private void storeTransferData(byte[] data) {

        // We will not create packets here. Instead, we add the incoming byte array
        // (a complete MIDI message) to our queue, which the transfer thread pulls
        // from. It is responsible for forming the packets.

        midiTransferQueue.add(data);

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

        midiTransferQueue.add(systemExclusive);
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
        sendMidiMessage(0x80 | (channel & 0xf), note, velocity);
    }

    /**
     * Note-on
     *
     * @param channel 0-15
     * @param note 0-127
     * @param velocity 0-127
     */
    public final void sendMidiNoteOn(int channel, int note, int velocity) {
        sendMidiMessage(0x90 | (channel & 0xf), note, velocity);
    }

    /**
     * Poly-KeyPress
     *
     * @param channel 0-15
     * @param note 0-127
     * @param pressure 0-127
     */
    public final void sendMidiPolyphonicAftertouch(int channel, int note, int pressure) {
        sendMidiMessage(0xa0 | (channel & 0xf), note, pressure);
    }

    /**
     * Control Change
     *
     * @param channel 0-15
     * @param function 0-127
     * @param value 0-127
     */
    public final void sendMidiControlChange(int channel, int function, int value) {
        sendMidiMessage(0xb0 | (channel & 0xf), function, value);
    }

    /**
     * Program Change
     *
     * @param channel 0-15
     * @param program 0-127
     */
    public final void sendMidiProgramChange(int channel, int program) {
        sendMidiMessage(0xc0 | (channel & 0xf), program);
    }

    /**
     * Channel Pressure
     *
     * @param channel 0-15
     * @param pressure 0-127
     */
    public final void sendMidiChannelAftertouch(int channel, int pressure) {
        sendMidiMessage(0xd0 | (channel & 0xf), pressure);
    }

    /**
     * PitchBend Change
     *
     * @param channel 0-15
     * @param amount 0(low)-8192(center)-16383(high)
     */
    public final void sendMidiPitchWheel(int channel, int amount) {
        sendMidiMessage(0xe0 | (channel & 0xf), amount & 0x7f, (amount >> 7) & 0x7f);
    }

    /**
     * MIDI Time Code(MTC) Quarter Frame
     *
     * @param timing 0-127
     */
    public final void sendMidiTimeCodeQuarterFrame(int timing) {
        sendMidiMessage(0xf1, timing & 0x7f);
    }

    /**
     * Song Select
     *
     * @param song 0-127
     */
    public final void sendMidiSongSelect(int song) {
        sendMidiMessage(0xf3, song & 0x7f);
    }

    /**
     * Song Position Pointer
     *
     * @param position 0-16383
     */
    public final void sendMidiSongPositionPointer(int position) {
        sendMidiMessage(0xf2, position & 0x7f, (position >> 7) & 0x7f);
    }

    /**
     * Tune Request
     */
    public final void sendMidiTuneRequest() {
        sendMidiMessage(0xf6);
    }

    /**
     * Timing Clock
     */
    public final void sendMidiTimingClock() {
        sendMidiMessage(0xf8);
    }

    /**
     * Start Playing
     */
    public final void sendMidiStart() {
        sendMidiMessage(0xfa);
    }

    /**
     * Continue Playing
     */
    public final void sendMidiContinue() {
        sendMidiMessage(0xfb);
    }

    /**
     * Stop Playing
     */
    public final void sendMidiStop() {
        sendMidiMessage(0xfc);
    }

    /**
     * Active Sensing
     */
    public final void sendMidiActiveSensing() {
        sendMidiMessage(0xfe);
    }

    /**
     * Reset Device
     */
    public final void sendMidiReset() {
        sendMidiMessage(0xff);
    }

    /**
     * RPN message
     *
     * @param channel 0-15
     * @param function 14bits
     * @param value 7bits or 14bits
     */
    public final void sendRPNMessage(int channel, int function, int value) {
        sendRPNMessage(channel, (function >> 7) & 0x7f, function & 0x7f, value);
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
        sendMidiControlChange(channel, 101, functionMSB & 0x7f);
        sendMidiControlChange(channel, 100, functionLSB & 0x7f);

        // send the value
        if ((value >> 7) > 0) {
            sendMidiControlChange(channel, 6, (value >> 7) & 0x7f);
            sendMidiControlChange(channel, 38, value & 0x7f);
        } else {
            sendMidiControlChange(channel, 6, value & 0x7f);
        }

        // send the NULL function
        sendMidiControlChange(channel, 101, 0x7f);
        sendMidiControlChange(channel, 100, 0x7f);
    }

    /**
     * NRPN message
     *
     * @param channel 0-15
     * @param function 14bits
     * @param value 7bits or 14bits
     */
    public final void sendNRPNMessage(int channel, int function, int value) {
        sendNRPNMessage(channel, (function >> 7) & 0x7f, function & 0x7f, value);
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
        sendMidiControlChange(channel, 99, functionMSB & 0x7f);
        sendMidiControlChange(channel, 98, functionLSB & 0x7f);

        // send the value
        if ((value >> 7) > 0) {
            sendMidiControlChange(channel, 6, (value >> 7) & 0x7f);
            sendMidiControlChange(channel, 38, value & 0x7f);
        } else {
            sendMidiControlChange(channel, 6, value & 0x7f);
        }

        // send the NULL function
        sendMidiControlChange(channel, 101, 0x7f);
        sendMidiControlChange(channel, 100, 0x7f);
    }
}
