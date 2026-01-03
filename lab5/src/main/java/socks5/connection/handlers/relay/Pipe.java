package socks5.connection.handlers.relay;

import java.nio.ByteBuffer;

public class Pipe {
    public final ByteBuffer buf = ByteBuffer.allocateDirect(64 * 1024);
    public boolean srcEof = false;
    public boolean sinkShutdown = false;

    public boolean hasDataToWrite() {
        return buf.position() > 0;
    }

    public boolean hasSpaceForRead() {
        return buf.hasRemaining();
    }
}