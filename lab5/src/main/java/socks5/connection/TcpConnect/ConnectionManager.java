package socks5.connection.TcpConnect;

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
import static socks5.connection.SocksProtocol.*;

public class ConnectionManager {
    private final Conn conn;

    public ConnectionManager(Conn conn) {
        this.conn = conn;
    }

    public void startConnect(InetSocketAddress dst) throws IOException {
        if (conn.remote != null && conn.remote.isOpen()) return;

        Log.log("CONNECT to %s", dst);
        conn.remote = SocketChannel.open();
        conn.remote.configureBlocking(false);
        try {
            conn.remote.bind(new InetSocketAddress(0));
        } catch (Exception ignored) {}

        conn.remoteKey = conn.remote.register(conn.selector, SelectionKey.OP_CONNECT);
        conn.remoteKey.attach(conn);
        conn.state = State.CONNECTING;

        boolean connected = conn.remote.connect(dst);
        if (connected) {
            onConnected(dst);
        }
    }

    public void onConnected(SocketAddress dst) throws IOException {
        conn.sendReply(REP_SUCCEEDED, conn.remote.getLocalAddress());
        conn.state = State.RELAY;
        Log.log("TCP connected: %s", dst);
        conn.startRelay();
    }


    public void onConnectable() throws IOException {
        if (conn.remote != null && conn.remoteKey.isConnectable()) {
            try {
                if (conn.remote.finishConnect()) {
                    onConnected(conn.remote.getRemoteAddress());
                }
            } catch (ConnectException ce) {
                conn.fail(REP_CONN_REFUSED, "Connect refused");
            } catch (NoRouteToHostException | UnresolvedAddressException e) {
                conn.fail(REP_HOST_UNREACH, "Host unreachable");
            } catch (IOException ioe) {
                conn.fail(REP_NET_UNREACH, "Network error");
            }
        }
    }
}
