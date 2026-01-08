package socks5.connection.handlers.TcpConnect;

import socks5.connection.context.ConnectionContext;
import socks5.protocol.SocksProtocolWriter;
import socks5.selector.SelectorHelper;
import socks5.util.Log;
import socks5.util.State;
import socks5.connection.Conn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import static socks5.protocol.SocksProtocol.*;

public class ConnectionManager {
    private final ConnectionContext ctx;
    private final SocksProtocolWriter writer;

    public ConnectionManager(ConnectionContext ctx, SocksProtocolWriter writer) {
        this.ctx = ctx;
        this.writer = writer;
    }

    public void startConnect(Conn connection, InetSocketAddress dst, Selector selector) throws IOException {
        if (ctx.getRemote() != null && ctx.getRemote().isOpen()) return;

        Log.log("CONNECT %s:%d", dst.getHostString(), dst.getPort());

        SocketChannel remote = SocketChannel.open();
        remote.configureBlocking(false);
        ctx.setRemote(remote);
        ctx.setState(State.CONNECTING);

        boolean connected = remote.connect(dst);

        int ops = connected ? 0 : SelectionKey.OP_CONNECT;
        SelectionKey remoteKey = remote.register(selector, ops);
        remoteKey.attach(connection);
        ctx.setRemoteKey(remoteKey);

        if (connected) {
            onConnected(dst);
        }
    }

    public void onConnectable() throws IOException {
        if (ctx.getState() != State.CONNECTING) return;

        SocketChannel remote = ctx.getRemote();
        SelectionKey remoteKey = ctx.getRemoteKey();

        if (remote != null && remoteKey != null && remoteKey.isConnectable()) {
            if (remote.finishConnect()) {
                onConnected(remote.getRemoteAddress());
            }
        }
    }

    private void onConnected(SocketAddress dst) throws IOException {
        writer.sendReply(REP_SUCCEEDED, ctx.getRemote().getLocalAddress());
        ctx.setState(State.RELAY);

        SelectionKey remoteKey = ctx.getRemoteKey();
        SelectionKey clientKey = ctx.getClientKey();

        if (remoteKey != null && remoteKey.isValid()) {
            SelectorHelper.setInterests(remoteKey, true, false, false);
        }
        if (clientKey != null && clientKey.isValid()) {
            SelectorHelper.setInterests(clientKey, true, false, false);
        }

        Log.log("TCP Connected: %s", dst);
    }
}