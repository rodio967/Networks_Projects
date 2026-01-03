package socks5.connection.handlers.TcpConnect;

import socks5.connection.context.ConnectionContext;
import socks5.error.ConnectionErrorHandler;
import socks5.protocol.SocksProtocolWriter;
import socks5.selector.SelectorHelper;
import socks5.util.Log;
import socks5.util.State;
import socks5.connection.Conn;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import static socks5.protocol.SocksProtocol.*;

public class ConnectionManager {
    private final ConnectionContext ctx;
    private final SocksProtocolWriter writer;
    private final ConnectionErrorHandler errorHandler;

    public ConnectionManager(ConnectionContext ctx, SocksProtocolWriter writer, ConnectionErrorHandler errorHandler) {
        this.ctx = ctx;
        this.writer = writer;
        this.errorHandler = errorHandler;
    }

    public void startConnect(Conn connection, InetSocketAddress dst) throws IOException {
        if (ctx.getRemote() != null && ctx.getRemote().isOpen()) return;

        Log.log("CONNECT %s:%d", dst.getHostString(), dst.getPort());

        SocketChannel remote = SocketChannel.open();
        remote.configureBlocking(false);

        SelectionKey remoteKey = remote.register(ctx.getSelector(), SelectionKey.OP_CONNECT);
        remoteKey.attach(connection);

        ctx.setRemote(remote);
        ctx.setRemoteKey(remoteKey);
        ctx.setState(State.CONNECTING);

        if (remote.connect(dst)) {
            onConnected(dst);
        }
    }

    public void onConnected(SocketAddress dst) throws IOException {
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
    }

    public void onConnectable() throws IOException {
        SocketChannel remote = ctx.getRemote();
        SelectionKey remoteKey = ctx.getRemoteKey();

        if (remote != null && remoteKey != null && remoteKey.isConnectable()) {
            try {
                if (remote.finishConnect()) {
                    onConnected(remote.getRemoteAddress());
                }
            } catch (ConnectException ce) {
                Log.log("Connection refused: %s", ctx.getPendingHost());
                errorHandler.fail(REP_CONN_REFUSED, "Connect refused");
            } catch (NoRouteToHostException | UnresolvedAddressException e) {
                Log.log("Host unreachable: %s", ctx.getPendingHost());
                errorHandler.fail(REP_HOST_UNREACH, "Host unreachable");
            } catch (IOException ioe) {
                Log.log("Network error: %s", ioe.getMessage());
                errorHandler.fail(REP_NET_UNREACH, "Network error");
            }
        }
    }
}