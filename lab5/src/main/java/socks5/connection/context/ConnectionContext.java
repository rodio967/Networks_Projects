package socks5.connection.context;

import socks5.Dns.DnsResolver;
import socks5.util.State;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class ConnectionContext {
    private final Selector selector;
    private final DnsResolver dnsResolver;

    private final SelectionKey clientKey;
    private final SocketChannel client;
    private SelectionKey remoteKey;
    private SocketChannel remote;

    private State state = State.GREETING;
    private String pendingHost;
    private int pendingPort;

    public ConnectionContext(Selector selector, DnsResolver dnsResolver, SelectionKey clientKey, SocketChannel client) {
        this.selector = selector;
        this.dnsResolver = dnsResolver;
        this.clientKey = clientKey;
        this.client = client;
    }


    public Selector getSelector() {return selector;}

    public DnsResolver getDnsResolver() {return dnsResolver;}

    public SelectionKey getClientKey() {return clientKey;}

    public SocketChannel getClient() {return client;}

    public SelectionKey getRemoteKey() {return remoteKey;}

    public SocketChannel getRemote() {return remote;}

    public State getState() {return state;}

    public String getPendingHost() {return pendingHost;}

    public int getPendingPort() {return pendingPort;}

    public void setPendingHost(String pendingHost) {this.pendingHost = pendingHost;}

    public void setPendingPort(int pendingPort) {this.pendingPort = pendingPort;}

    public void setState(State state) {this.state = state;}

    public void setRemote(SocketChannel remote) {this.remote = remote;}

    public void setRemoteKey(SelectionKey key) {this.remoteKey = key;}

    private void closeQuietly(SelectionKey key, SocketChannel channel) {
        if (key != null) {
            try {
                key.cancel();
            } catch (Exception ignored) {}
        }

        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {}
        }
    }

    public void closeAll() {
        state = State.CLOSED;

        closeQuietly(clientKey, client);
        closeQuietly(remoteKey, remote);
    }
}
