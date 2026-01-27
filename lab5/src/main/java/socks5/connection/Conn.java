package socks5.connection;

import socks5.Dns.DnsResolver;
import socks5.auth.AuthConfig;
import socks5.connection.context.ConnectionContext;
import socks5.connection.handlers.handshake.AuthResult;
import socks5.connection.handlers.handshake.ConnectionRequest;
import socks5.connection.handlers.handshake.GreetingResult;
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

    private final AuthConfig authConfig;

    public Conn(SelectionKey clientKey, SocketChannel client, Selector selector, DnsResolver dnsResolver, AuthConfig authConfig) {
        this.ctx = new ConnectionContext(clientKey, client);

        this.selector = selector;
        this.dnsResolver = dnsResolver;
        this.writer = new SocksProtocolWriter(client);

        this.authConfig = authConfig;

        this.handshake = new SocksHandshake(ctx, authConfig);
        this.connectionManager = new ConnectionManager(ctx, writer);
        this.relayManager = new RelayManager(ctx);
    }

    public void onConnectable() throws IOException {
        try {
            connectionManager.onConnectable();
        } catch (ConnectException e) {
            logConnectionFailure("Error: Connection refused", e);
            failQuietly(REP_CONN_REFUSED);
        } catch (SocketTimeoutException e) {
            logConnectionFailure("Error: Connection timeout", e);
            failQuietly(REP_NET_UNREACH);
        } catch (NoRouteToHostException e) {
            logConnectionFailure("Error: No route to host", e);
            failQuietly(REP_HOST_UNREACH);
        } catch (UnresolvedAddressException e) {
            logConnectionFailure("Error: Unresolved address", e);
            failQuietly(REP_HOST_UNREACH);
        } catch (IOException ioe) {
            logConnectionFailure("Error: Network error", ioe);
            failQuietly(REP_NET_UNREACH);
        }
    }

    public void onReadable(SelectionKey key) throws IOException {
        switch (ctx.getState()) {
            case GREETING -> {
                GreetingResult result = handshake.readGreeting();
                switch (result) {
                    case CLIENT_CLOSED, INVALID_VER -> {
                        Log.log("Handshake Greeting error: %s", result);
                        close();
                    }
                    case OK -> {
                        handshake.sendMethodSelection(true);

                        if (authConfig.isEnabled()) {
                            handshake.enterState(State.AUTH);
                        } else {
                            handshake.enterState(State.REQUEST);
                        }

                    }
                    case NO_ACCEPTABLE_METHODS -> {
                        handshake.sendMethodSelection(false);
                        close();
                    }
                }
            }
            case AUTH -> {
                AuthResult result = handshake.readAuth();
                switch (result) {
                    case CLIENT_CLOSED, INVALID -> {
                        Log.log("Handshake Auth error: %s", result);
                        close();
                    }
                    case SUCCESS -> {
                        handshake.sendAuthResponse(true);
                        handshake.enterState(State.REQUEST);
                    }
                    case FAILURE -> {
                        Log.log("Auth failed: invalid credentials");
                        handshake.sendAuthResponse(false);
                        close();
                    }
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

    private void logConnectionFailure(String reason, Exception e) {
        String host = ctx.getPendingHost();
        int port = ctx.getPendingPort();

        if (host != null) {
            Log.log("%s: %s:%d - %s", reason, host, port, e.getMessage());
        } else {
            Log.log("%s: %s", reason, e.getMessage());
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