package socks5.connection;

import socks5.Dns.DnsResolver;
import socks5.connection.context.ConnectionContext;
import socks5.connection.handlers.handshake.ConnectionRequest;
import socks5.protocol.SocksProtocolWriter;
import socks5.selector.SelectorHelper;
import socks5.util.Log;
import socks5.util.State;
import socks5.connection.handlers.TcpConnect.ConnectionManager;
import socks5.connection.handlers.handshake.SocksHandshake;
import socks5.connection.handlers.relay.RelayManager;

import java.io.IOException;
import java.net.*;
import java.nio.channels.*;

import static socks5.protocol.SocksProtocol.*;

public class Conn {
    private final ConnectionContext ctx;
    private final Selector selector;
    private final DnsResolver dnsResolver;

    private final SocksHandshake handshake;
    private final ConnectionManager connectionManager;
    private final RelayManager relayManager;

    private final SocksProtocolWriter writer;

    public Conn(SelectionKey clientKey, SocketChannel client, Selector selector, DnsResolver dnsResolver) {
        this.ctx = new ConnectionContext(clientKey, client);

        this.selector = selector;
        this.dnsResolver = dnsResolver;
        this.writer = new SocksProtocolWriter(client);

        this.handshake = new SocksHandshake(ctx);
        this.connectionManager = new ConnectionManager(ctx, writer);
        this.relayManager = new RelayManager(ctx);
    }

    public void onConnectable() throws IOException {
        try {
            connectionManager.onConnectable();
        } catch (ConnectException e) {
            failQuietly(REP_CONN_REFUSED);
        } catch (NoRouteToHostException | UnresolvedAddressException e) {
            failQuietly(REP_HOST_UNREACH);
        } catch (IOException ioe) {
            failQuietly(REP_NET_UNREACH);
        }
    }

    public void onReadable(SelectionKey key) throws IOException {
        switch (ctx.getState()) {
            case GREETING -> {
                if (!handshake.readGreeting()) {
                    close();
                }
            }
            case REQUEST -> {
                ConnectionRequest request = handshake.readRequest();
                if (request != null) {
                    if (request.isError()) {
                        failQuietly(request.errorCode());
                    } else {
                        handleConnectionRequest(request);
                    }
                }

            }
            case RELAY -> {
                if (relayManager.onReadable(key)) {
                    close();
                }
            }
            default -> {}
        }
    }

    public void onWritable(SelectionKey key) throws IOException {
        if (ctx.getState() == State.RELAY) {
            if (relayManager.onWritable(key)) {
                close();
            }
        } else {
            SelectorHelper.setInterests(ctx.getClientKey(), true, false, false);
        }
    }

    public void onResolved(InetAddress ip) throws IOException {
        connectionManager.startConnect(this, new InetSocketAddress(ip, ctx.getPendingPort()), selector);
    }

    public void onDnsFailed(String reason) {
        String host = ctx.getPendingHost();
        Log.log("DNS failed for %s: %s", host != null ? host : "unknown", reason);
        failQuietly(REP_HOST_UNREACH);
    }

    public ConnectionContext getConnectionContext() {
        return ctx;
    }


    private void handleConnectionRequest(ConnectionRequest request) throws IOException {
        if (request.isDomain()) {
            ctx.setPendingHost(request.domain());
            dnsResolver.sendDnsQuery(request.domain(), this);
        } else {
            connectionManager.startConnect(this, new InetSocketAddress(request.address(), request.port()), selector);
        }
    }

    public void failQuietly(byte errorCode) {
        try {
            writer.sendErrorReply(errorCode);
        } catch (IOException ignored) {}
        close();
    }

    public void close() {
        if (ctx.getState() == State.CLOSED) return;
        dnsResolver.clearDns(this);
        ctx.closeAll();
    }
}