package socks5.connection.relay;

import java.nio.ByteBuffer;

public class Pipe {
    public final ByteBuffer buf = ByteBuffer.allocateDirect(64 * 1024);
    public boolean srcEof = false;
    public boolean sinkShutdown = false;

    public boolean hasData() {
        return buf.position() > 0 || buf.flip().hasRemaining();
    }
}
