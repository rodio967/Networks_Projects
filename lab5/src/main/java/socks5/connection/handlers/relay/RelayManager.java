package socks5.connection.handlers.relay;

import socks5.connection.context.ConnectionContext;
import socks5.selector.SelectorHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class RelayManager {
    private final ConnectionContext ctx;
    private final Pipe c2r = new Pipe();
    private final Pipe r2c = new Pipe();

    public RelayManager(ConnectionContext ctx) {
        this.ctx = ctx;
    }

    public void onReadable() throws IOException {
        SelectionKey clientKey = ctx.getClientKey();
        SelectionKey remoteKey = ctx.getRemoteKey();
        SocketChannel client = ctx.getClient();
        SocketChannel remote = ctx.getRemote();

        if (clientKey.isReadable()) {
            pump(client, remote, c2r);
        }

        if (remoteKey != null && remoteKey.isReadable()) {
            pump(remote, client, r2c);
        }

        updateInterestsRelay();
        maybeCloseAfterRelay();
    }

    public void onWritable() throws IOException {
        SelectionKey clientKey = ctx.getClientKey();
        SelectionKey remoteKey = ctx.getRemoteKey();

        if (clientKey.isWritable()) {
            flush(ctx.getClient(), r2c);
        }

        if (remoteKey != null && remoteKey.isWritable()) {
            flush(ctx.getRemote(), c2r);
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

        SelectorHelper.setInterests(ctx.getClientKey(), clientRead, clientWrite, false);
        if (ctx.getRemoteKey() != null) {
            SelectorHelper.setInterests(ctx.getRemoteKey(), remoteRead, remoteWrite, false);
        }
    }

    public void maybeCloseAfterRelay() {
        boolean clientDone = (!ctx.getClient().isOpen()) || (c2r.srcEof && c2r.sinkShutdown);
        boolean remoteDone = (ctx.getRemote() == null) || (!ctx.getRemote().isOpen()) || (r2c.srcEof && r2c.sinkShutdown);
        if (clientDone && remoteDone) ctx.closeAll();
    }
}
