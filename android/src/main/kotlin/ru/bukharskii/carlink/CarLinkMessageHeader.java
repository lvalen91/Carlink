package ru.bukharskii.carlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class CarLinkMessageHeader {
    public final static int MESSAGE_LENGTH = 16;
    private final static int MAGIC = 0x55aa55aa;
    private int length;
    private int type;

    public boolean isVideoData() {
        return type == 6;
    }

    public CarLinkMessageHeader(int length, int type) {
        this.length = length;
        this.type = type;
    }

    public int getLength() {
        return length;
    }

    public int getType() {
        return type;
    }

    public void readFromBuffer(ByteBuffer buffer) throws Exception {

        if (buffer.remaining() != 16)
            throw new HeaderBuildException("Invalid buffer size - Expecting 16, got ${data.length}");

        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int magic = buffer.getInt();

        if (magic != MAGIC)
            throw new Exception("Invalid magic number, received " + magic);

        int length = buffer.getInt();
        int type = buffer.getInt();
        int typeCheck = buffer.getInt();
        if (typeCheck != ((type ^ -1) & 0xffffffff) >>> 0) {
            throw new HeaderBuildException("Invalid type check, received " + typeCheck);
        }

        this.type = type;
        this.length = length;
    }
}
