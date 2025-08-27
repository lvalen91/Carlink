package ru.bukharskii.carlink;


import java.io.IOException;
import java.nio.ByteBuffer;

public class PacketRingByteBuffer {
    public interface DirectWriteCallback {
         void write(byte[] bytes, int offset);
    }

    private byte[] buffer;
    private int readPosition = 0;
    private int writePosition = 0;

    private int lastWritePositionBeforeEnd = 0;

    private int packetCount = 0;

    private LogCallback logCallback;

//    public static void test() {
//
//        PacketRingByteBuffer buf = new PacketRingByteBuffer(20);
//
//        Log.d("RING_BUFFER_TEST", "test start");
//
//        buf.writePacket(new byte[10], 0, 10);
//        assert(buf.writePosition == 14);
//
//        buf.writePacket(new byte[10], 0, 10);
//        assert(buf.writePosition == 28);
//
//        ByteBuffer packet;
//
//        packet = buf.readPacket();
//        assert(buf.readPosition == 14 && packet.remaining() == 10);
//
//        packet = buf.readPacket();
//        assert(buf.readPosition == 28 && packet.remaining() == 10);
//
//        buf.writePacket(new byte[10], 0, 10);
//        assert(buf.writePosition == 10);
//
//        buf.writePacket(new byte[10], 0, 10);
//        assert(buf.writePosition == 24);
//
//        buf.writePacket(new byte[10], 0, 10);
//        assert(buf.readPosition == 0 && buf.writePosition == 42);
//
//        packet = buf.readPacket();
//        packet = buf.readPacket();
//        packet = buf.readPacket();
//
//        for(int i=0; i<50; i++) {
//            byte[] b = new byte[5];
//            b[0] = (byte)(i+1);
//            b[1] = (byte)(i+2);
//            b[2] = (byte)(i+3);
//            b[3] = (byte)(i+4);
//            b[4] = (byte)(i+5);
//
//            buf.writePacket(b, 0, b.length);
//        }
//
//        for(int i=0; i<50; i++) {
//            packet = buf.readPacket();
//
//            byte b1 = packet.get(packet.position());
//            byte b2 = packet.get(packet.position() + 1);
//
//            if(b1 != i+1 || b2 != i+2) {
//                Log.d("TAG", "");
//            }
//        }
//    }

    public PacketRingByteBuffer(int initialSize) {
        buffer = new byte[initialSize];
    }

    private void log(String message) {
//        logCallback.log(message);
    }

    public boolean isEmpty() {
        return packetCount == 0;
    }

    public int availablePacketsToRead() {
        return packetCount;
    }

    private void reorganizeAndResizeIfNeeded() {

        int available = 0;
        if (writePosition > readPosition) {
            available = readPosition + buffer.length - writePosition;
        }
        else {
            available = readPosition - writePosition;
        }

        // resize
        int newLength = buffer.length;
        if (available < buffer.length / 2) {
            newLength = newLength * 2;
            log("RESIZE to:"+newLength+", read:"+readPosition+", write:"+writePosition+", length:"+buffer.length+", count:"+availablePacketsToRead());
        }

        byte[] newBuffer = new byte[newLength];

        if (writePosition < readPosition) {
            int dataAtEndLength = lastWritePositionBeforeEnd - readPosition;

            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (dataAtEndLength < 0 || dataAtEndLength > buffer.length || readPosition < 0 || readPosition + dataAtEndLength > buffer.length) {
                log("CRITICAL: Invalid end copy parameters - readPosition: " + readPosition + ", dataAtEndLength: " + dataAtEndLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            if (writePosition < 0 || writePosition > buffer.length || dataAtEndLength + writePosition > newBuffer.length) {
                log("CRITICAL: Invalid start copy parameters - writePosition: " + writePosition + ", dataAtEndLength: " + dataAtEndLength + ", newBufferLength: " + newBuffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            // copy end
            System.arraycopy(buffer, readPosition, newBuffer, 0, dataAtEndLength);

            // copy from start
            System.arraycopy(buffer, 0, newBuffer, dataAtEndLength, writePosition);

            // update positions
            readPosition = 0;
            writePosition += dataAtEndLength;
        }
        else {
            int copyLength = writePosition - readPosition;
            
            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (copyLength < 0 || readPosition < 0 || readPosition + copyLength > buffer.length || copyLength > newBuffer.length) {
                log("CRITICAL: Invalid linear copy parameters - readPosition: " + readPosition + ", writePosition: " + writePosition + ", copyLength: " + copyLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            System.arraycopy(buffer, readPosition, newBuffer, 0, copyLength);

            writePosition -= readPosition;
            readPosition = 0;
        }

        log("RESIZE done, read:"+readPosition+", write:"+writePosition+", length:"+buffer.length+", count:"+availablePacketsToRead());

        buffer = newBuffer;
    }

    private int availableSpaceAtHead() {
        if (writePosition < readPosition) {
            return readPosition - writePosition;
        }

        return buffer.length - writePosition;
    }
    private int availableSpaceAtStart() {
        if (writePosition < readPosition) {
            return 0;
        }

        return readPosition;
    }

    public void directWriteToBuffer(int length, int skipBytesCount, DirectWriteCallback callback) {
        synchronized (this) {
            // Validate input parameters to prevent corruption
            if (length < 0 || skipBytesCount < 0 || skipBytesCount > length) {
                log("CRITICAL: Invalid write parameters - length: " + length + ", skipBytesCount: " + skipBytesCount);
                return; // Abort write operation
            }
            
            // Prevent excessively large packets that could cause memory issues
            if (length > buffer.length / 2) {
                log("WARNING: Large packet size " + length + " bytes, buffer size: " + buffer.length);
                // Still allow but warn about potential issues
            }

            boolean hasSpaceToWriteLength = availableSpaceAtHead() > 4 + 4;
            boolean hasSpaceAtHead = availableSpaceAtHead() > length + 4 + 4;
            boolean hasSpaceAtStart = availableSpaceAtStart() > length + 4 + 4;

            while (!hasSpaceToWriteLength || !(hasSpaceAtStart || hasSpaceAtHead)) {
                reorganizeAndResizeIfNeeded();

                hasSpaceToWriteLength = availableSpaceAtHead() > 4 + 4;
                hasSpaceAtHead = availableSpaceAtHead() > length + 4 + 4;
                hasSpaceAtStart = availableSpaceAtStart() > length + 4 + 4;
            }

            // 1. write packet length
            writeInt(writePosition, length);
            writePosition += 4;

            // 2. write skip bytes count
            writeInt(writePosition, skipBytesCount);
            writePosition += 4;

            // 3. write data
            if (!hasSpaceAtHead && hasSpaceAtStart) {
                // mark
                lastWritePositionBeforeEnd = writePosition;

                // reset position
                writePosition = 0;
            }

            callback.write(buffer, writePosition);

            // 4. update position
            writePosition += length;

            // 5. update count
            packetCount ++;
        }
    }


    public void writePacket(byte[] source, int srcOffset, int length) {
        directWriteToBuffer(length, 0, (buf, off) -> System.arraycopy(source, srcOffset, buf, off, length));
    }

    ByteBuffer readPacket() {
        synchronized (this) {
            int length = readInt(readPosition);
            readPosition += 4;

            int skipBytes = readInt(readPosition);
            readPosition += 4;

            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                log("CRITICAL: Invalid packet parameters - length: " + length + ", skipBytes: " + skipBytes);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return ByteBuffer.allocate(0); // Return empty buffer
            }

            // reset position if on the end
            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            // Additional bounds checking
            int actualLength = length - skipBytes;
            int startPos = readPosition + skipBytes;
            
            if (actualLength < 0 || startPos < 0 || startPos + actualLength > buffer.length) {
                log("CRITICAL: Buffer bounds exceeded - startPos: " + startPos + ", actualLength: " + actualLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return ByteBuffer.allocate(0); // Return empty buffer
            }

            ByteBuffer result = ByteBuffer.wrap(buffer, startPos, actualLength);
            readPosition += length;

            packetCount --;

            return result;
        }
    }

    private void writeInt(int offset, int value) {
       buffer[offset]   = (byte) ((value & 0xFF000000) >> 24);
       buffer[offset+1] = (byte) ((value & 0x00FF0000) >> 16);
       buffer[offset+2] = (byte) ((value & 0x0000FF00) >> 8);
       buffer[offset+3] = (byte)  (value & 0x000000FF);
    }

    private int readInt(int offset) {
        return  ((buffer[offset]   << 24) & 0xFF000000) |
                ((buffer[offset+1] << 16) & 0x00FF0000) |
                ((buffer[offset+2] << 8)  & 0x0000FF00) |
                ((buffer[offset+3])       & 0x000000FF);
    }

    public void reset() {
        packetCount = 0;
        writePosition = 0;
        readPosition = 0;
    }
}
