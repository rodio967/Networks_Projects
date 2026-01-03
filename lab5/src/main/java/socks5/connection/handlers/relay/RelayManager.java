package socks5.connection.handlers.relay;

import socks5.connection.context.ConnectionContext;
import socks5.selector.SelectorHelper;
import socks5.util.Log;

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

    public void onReadable(SelectionKey triggeredKey) throws IOException {
        SocketChannel client = ctx.getClient();
        SocketChannel remote = ctx.getRemote();


        if (triggeredKey == ctx.getClientKey() && client != null && client.isOpen()) {
            readFromSource(client, c2r);
            if (remote != null && remote.isOpen()) {
                writeToDestination(remote, c2r);
            }
        }

        if (triggeredKey == ctx.getRemoteKey() && remote != null && remote.isOpen()) {
            readFromSource(remote, r2c);
            if (client != null && client.isOpen()) {
                writeToDestination(client, r2c);
            }
        }

        updateInterests();
        maybeClose();
    }

    public void onWritable(SelectionKey triggeredKey) throws IOException {
        SocketChannel client = ctx.getClient();
        SocketChannel remote = ctx.getRemote();

        if (triggeredKey == ctx.getClientKey() && client != null && client.isOpen()) {
            writeToDestination(client, r2c);
        }

        if (triggeredKey == ctx.getRemoteKey() && remote != null && remote.isOpen()) {
            writeToDestination(remote, c2r);
        }

        updateInterests();
        maybeClose();
    }


    private void readFromSource(SocketChannel src, Pipe pipe) throws IOException {
        if (pipe.srcEof) return;

        ByteBuffer buf = pipe.buf;
        int n = src.read(buf);
        if (n == -1) {
            pipe.srcEof = true;
        }
    }


    private void writeToDestination(SocketChannel dst, Pipe pipe) throws IOException {
        ByteBuffer buf = pipe.buf;
        buf.flip();
        if (buf.hasRemaining()) {
            dst.write(buf);
        }

        if (pipe.srcEof && !buf.hasRemaining() && !pipe.sinkShutdown) {
            try {
                dst.shutdownOutput();
            } catch (IOException ignored) {}
            pipe.sinkShutdown = true;
        }

        buf.compact();
    }

    private void updateInterests() {
        SelectionKey clientKey = ctx.getClientKey();
        SelectionKey remoteKey = ctx.getRemoteKey();

        boolean clientRead = c2r.hasSpaceForRead() && !c2r.srcEof;
        boolean clientWrite = r2c.hasDataToWrite();

        boolean remoteRead = r2c.hasSpaceForRead() && !r2c.srcEof;
        boolean remoteWrite = c2r.hasDataToWrite();

        if (clientKey != null && clientKey.isValid()) {
            SelectorHelper.setInterests(clientKey, clientRead, clientWrite, false);
        }

        if (remoteKey != null && remoteKey.isValid()) {
            SelectorHelper.setInterests(remoteKey, remoteRead, remoteWrite, false);
        }
    }

    private void maybeClose() {
        SocketChannel client = ctx.getClient();
        SocketChannel remote = ctx.getRemote();

        boolean clientDone = (client == null) || !client.isOpen() ||
                (c2r.srcEof && c2r.sinkShutdown);
        boolean remoteDone = (remote == null) || !remote.isOpen() ||
                (r2c.srcEof && r2c.sinkShutdown);

        if (clientDone && remoteDone) {
            ctx.closeAll();
        }
    }
}