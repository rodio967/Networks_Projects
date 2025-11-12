package socks5.connection.relay;

import socks5.connection.Conn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class RelayManager {
    private final Conn conn;
    private final Pipe c2r = new Pipe();
    private final Pipe r2c = new Pipe();

    public RelayManager(Conn conn) {
        this.conn = conn;
    }

    public void onReadable() throws IOException {
        SelectionKey clientKey = conn.clientKey;
        SelectionKey remoteKey = conn.remoteKey;

        if (clientKey.isReadable()) {
            pump(conn.client, conn.remote, c2r);
        }

        if (remoteKey != null && remoteKey.isReadable()) {
            pump(conn.remote, conn.client, r2c);
        }

        updateInterestsRelay();
        maybeCloseAfterRelay();
    }

    public void onWritable() throws IOException {
        SelectionKey clientKey = conn.clientKey;
        SelectionKey remoteKey = conn.remoteKey;

        if (clientKey.isWritable()) {
            flush(conn.client, r2c);
        }

        if (remoteKey != null && remoteKey.isWritable()) {
            flush(conn.remote, c2r);
        }

        updateInterestsRelay();
        maybeCloseAfterRelay();

    }

    public void pump(SocketChannel src, SocketChannel dst, Pipe pipe) throws IOException {
        if (src == null || dst == null) return;
        ByteBuffer buf = pipe.buf;
        if (!buf.hasRemaining()) return;
        int n = src.read(buf);
        if (n == -1) {
            pipe.srcEof = true;
            return;
        }
        if (n == 0) return;
        buf.flip();
        int wrote = dst.write(buf);
        buf.compact();
    }

    public void flush(SocketChannel dst, Pipe pipe) throws IOException {
        if (dst == null) return;
        ByteBuffer buf = pipe.buf;
        buf.flip();
        if (!buf.hasRemaining()) {
            buf.compact();
            return;
        }
        int n = dst.write(buf);
        buf.compact();
        if (pipe.srcEof && n >= 0 && buf.position() == 0 && !pipe.sinkShutdown) {
            try {
                dst.shutdownOutput();
            } catch (IOException ignored) {}
            pipe.sinkShutdown = true;
        }
    }


    public void updateInterestsRelay() {
        boolean clientRead = c2r.buf.hasRemaining() && !c2r.srcEof;
        boolean clientWrite = r2c.buf.position() > 0 || (r2c.buf.flip().hasRemaining());
        r2c.buf.compact();

        boolean remoteRead = r2c.buf.hasRemaining() && !r2c.srcEof;
        boolean remoteWrite = c2r.buf.position() > 0 || (c2r.buf.flip().hasRemaining());
        c2r.buf.compact();

        conn.setInterests(conn.clientKey, clientRead, clientWrite, false);
        if (conn.remoteKey != null) {
            conn.setInterests(conn.remoteKey, remoteRead, remoteWrite, false);
        }
    }

    public void maybeCloseAfterRelay() {
        boolean clientDone = (!conn.client.isOpen()) || (c2r.srcEof && c2r.sinkShutdown);
        boolean remoteDone = (conn.remote == null) || (!conn.remote.isOpen()) || (r2c.srcEof && r2c.sinkShutdown);
        if (clientDone && remoteDone) conn.closeAll();
    }
}
