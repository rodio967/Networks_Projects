package socks5.connection;

import socks5.Dns.DnsResolver;
import socks5.connection.context.ConnectionContext;
import socks5.connection.handlers.handshake.ConnectionRequest;
import socks5.error.ConnectionErrorHandler;
import socks5.protocol.SocksProtocolWriter;
import socks5.selector.SelectorHelper;
import socks5.util.State;
import socks5.connection.handlers.TcpConnect.ConnectionManager;
import socks5.connection.handlers.handshake.SocksHandshake;
import socks5.connection.handlers.relay.RelayManager;

import java.io.IOException;
import java.net.*;
import java.nio.channels.*;

public class Conn {
    private final ConnectionContext ctx;
    private final SocksHandshake handshake;
    private final ConnectionManager connectionManager;
    private final RelayManager relayManager;

    private final SocksProtocolWriter writer;
    private final ConnectionErrorHandler errorHandler;

    public Conn(SelectionKey clientKey, SocketChannel client, Selector selector, DnsResolver dnsResolver) {
        this.ctx = new ConnectionContext(selector, dnsResolver, clientKey, client);
        this.ctx.setOwner(this);

        this.writer = new SocksProtocolWriter(client);
        this.errorHandler = new ConnectionErrorHandler(ctx, writer);
        this.handshake = new SocksHandshake(ctx, writer, errorHandler);
        this.connectionManager = new ConnectionManager(ctx, writer, errorHandler);
        this.relayManager = new RelayManager(ctx);
    }

    public void onConnectable() throws IOException {
        connectionManager.onConnectable();
    }

    public void onReadable(SelectionKey key) throws IOException {
        switch (ctx.getState()) {
            case GREETING -> handshake.readGreeting();
            case REQUEST -> {
                ConnectionRequest request = handshake.readRequest();
                if (request != null) {
                    // можно передавать selector снаружи
                    handleConnectionRequest(request);
                }
            }
            case RELAY -> relayManager.onReadable(key);
            default -> {}
        }
    }

    public void onWritable(SelectionKey key) throws IOException {
        if (ctx.getState() == State.RELAY) {
            relayManager.onWritable(key);
        } else {
            SelectorHelper.setInterests(ctx.getClientKey(), true, false, false);
        }
    }

    public void onResolved(InetAddress ip) throws IOException {
        connectionManager.startConnect(this, new InetSocketAddress(ip, ctx.getPendingPort()));
    }

    public ConnectionContext getConnectionContext() {
        return ctx;
    }

    public ConnectionErrorHandler getErrorHandler() {
        return errorHandler;
    }

    private void handleConnectionRequest(ConnectionRequest request) throws IOException {
        if (request.isDomain()) {
            ctx.setPendingHost(request.domain());
            // можно убрать dnsResolver и тут передать его
            ctx.getDnsResolver().sendDnsQuery(request.domain(), this);
        } else {
            connectionManager.startConnect(this, new InetSocketAddress(request.address(), request.port()));
        }
    }
}