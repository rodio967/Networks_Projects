package socks5.connection;

import socks5.Dns.DnsResolver;
import socks5.util.Log;
import socks5.util.State;
import socks5.connection.TcpConnect.ConnectionManager;
import socks5.connection.handshake.SocksHandshake;
import socks5.connection.relay.RelayManager;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;

import static socks5.connection.SocksProtocol.*;

public class Conn {
    public final Selector selector;
    public final DnsResolver dnsResolver;

    public final SelectionKey clientKey;
    public final SocketChannel client;
    public SocketChannel remote;
    public SelectionKey remoteKey;

    public State state = State.GREETING;
    public String pendingHost;
    public int pendingPort;

    public final SocksHandshake handshake = new SocksHandshake(this);
    public final ConnectionManager connectionManager = new ConnectionManager(this);
    public final RelayManager relayManager = new RelayManager(this);

    public Conn(SelectionKey clientKey, SocketChannel client, Selector selector, DnsResolver dnsResolver) {
        this.selector = selector;
        this.dnsResolver = dnsResolver;

        this.clientKey = clientKey;
        this.client = client;
    }



    public void setInterests(SelectionKey k, boolean read, boolean write, boolean connect) {
        int ops = 0;
        if (read) ops |= SelectionKey.OP_READ;
        if (write) ops |= SelectionKey.OP_WRITE;
        if (connect) ops |= SelectionKey.OP_CONNECT;
        try {
            k.interestOps(ops);
        } catch (CancelledKeyException ignored) {}
    }

    public void startRelay() {
        relayManager.updateInterestsRelay();
    }


    public void onConnectable() throws IOException {
        connectionManager.onConnectable();
    }

    public void onReadable() throws IOException {
        switch (state) {
            case GREETING -> handshake.readGreeting();
            case REQUEST -> handshake.readRequest();
            case RELAY -> relayManager.onReadable();
            default -> {}
        }
    }

    public void onWritable() throws IOException {
        if (state == State.RELAY) {
            relayManager.onWritable();
        } else {
            setInterests(clientKey, true, false, false);
        }
    }

    public int readFromClient(ByteBuffer buf) throws IOException {
        int n = client.read(buf);
        if (n == -1) {
            closeAll();
        }
        return n;
    }


    public void onResolved(InetAddress ip) throws IOException {
        connectionManager.startConnect(new InetSocketAddress(ip, pendingPort));
    }

    public void sendReply(byte rep, SocketAddress bind) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(10);
        b.put(VER)
                .put(rep)
                .put((byte)0x00);
        byte[] addr = {0,0,0,0};
        int port = 0;

        if (bind instanceof InetSocketAddress isa) {
            InetAddress a = isa.getAddress();
            if (a instanceof Inet4Address) addr = a.getAddress();
            port = isa.getPort();
        }
        b.put(ATYP_IPV4).put(addr).putShort((short)(port & 0xFFFF));
        b.flip();
        client.write(b);
    }

    public void fail(byte rep, String why) throws IOException {
        sendReply(rep, new InetSocketAddress("0.0.0.0", 0));
        Log.log("Fail: %s", why);
        closeAll();
    }

    public void closeAll() {
        state = State.CLOSED;
        dnsResolver.clearDns(this);

        try {
            clientKey.cancel();
        } catch (Exception ignored) {}
        try {
            client.close();
        } catch (Exception ignored) {}
        if (remoteKey != null) {
            try {
                remoteKey.cancel();
            } catch (Exception ignored) {}
        }
        if (remote != null) {
            try {
                remote.close();
            } catch (Exception ignored) {}
        }
    }
}