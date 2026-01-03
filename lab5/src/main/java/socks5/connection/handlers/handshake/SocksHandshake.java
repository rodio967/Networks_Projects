package socks5.connection.handlers.handshake;

import socks5.connection.context.ConnectionContext;
import socks5.protocol.SocksProtocolWriter;
import socks5.selector.SelectorHelper;
import socks5.util.State;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import static socks5.protocol.SocksProtocol.*;

public class SocksHandshake {
    private final ConnectionContext ctx;
    private final ByteBuffer buf;
    private final SocksProtocolWriter writer;

    public SocksHandshake(ConnectionContext ctx, SocksProtocolWriter writer) {
        this.ctx = ctx;
        this.buf = ByteBuffer.allocate(1024);
        this.writer = writer;
    }


    public boolean sendMethodSelection(boolean ok) throws IOException {
        ByteBuffer resp = ByteBuffer.allocate(2);
        resp.put(VER)
                .put(ok ? METHOD_NO_AUTH : METHOD_REJECT)
                .flip();
        ctx.getClient().write(resp);

        if (!ok) {
            return false;
        }

        ctx.setState(State.REQUEST);
        SelectorHelper.setInterests(ctx.getClientKey(), true, false, false);

        return true;
    }


    public boolean readGreeting() throws IOException {
        SocketChannel client = ctx.getClient();
        int n = client.read(buf);
        if (n == -1) return false;
        if (n == 0) return true;

        buf.flip();
        if (buf.remaining() < 2) {
            buf.compact();
            return true;
        }

        byte ver = buf.get();
        int nMethods = buf.get() & 0xFF;
        if (ver != VER) {
            return false;
        }

        if (buf.remaining() < nMethods) {
            buf.position(buf.position()-2);
            buf.compact();
            return true;
        }


        boolean ok = false;
        for (int i = 0; i < nMethods; i++){
            if (buf.get() == METHOD_NO_AUTH){
                ok = true;
            }
        }
        buf.clear();

        return sendMethodSelection(ok);
    }

    public ConnectionRequest readRequest() throws IOException {
        SocketChannel client = ctx.getClient();
        int n = client.read(buf);
        if (n == -1) {
            return new ConnectionRequest(null, null, 0, REP_GEN_FAIL);
        }
        if (n == 0) return null;

        buf.flip();
        if (buf.remaining() < 4) {
            buf.compact();
            return null;
        }

        byte ver = buf.get();
        byte cmd = buf.get();
        buf.get();
        byte atyp = buf.get();

        if (ver != VER) {
            return new ConnectionRequest(null, null, 0, REP_GEN_FAIL);
        }

        if (cmd != CMD_CONNECT) {
            return new ConnectionRequest(null, null, 0, REP_CMD_NOT_SUP);
        }

        return parseRequestAddress(atyp);
    }

    public ConnectionRequest parseRequestAddress(byte atyp) throws IOException {
        InetAddress dstAddr = null;
        String domain = null;
        if (atyp == ATYP_IPV4) {
            if (buf.remaining() < 4 + 2) {
                buf.position(buf.position() - 4);
                buf.compact();
                return null;
            }

            byte[] addr = new byte[4];
            buf.get(addr);
            try {
                dstAddr = InetAddress.getByAddress(addr);
            } catch (Exception e) {
                return new ConnectionRequest(null, null, 0, REP_ADDR_NOT_SUP);
            }

        } else if (atyp == ATYP_IPV6) {
            if (buf.remaining() < 16 + 2) {
                buf.position(buf.position() - 4);
                buf.compact();
                return null;
            }

            byte[] a = new byte[16];
            buf.get(a);
            try {
                dstAddr = InetAddress.getByAddress(a);
            } catch (Exception e) {
                return new ConnectionRequest(null, null, 0, REP_ADDR_NOT_SUP);
            }

        } else if (atyp == ATYP_DOMAIN) {
            if (buf.remaining() < 1) {
                buf.position(buf.position()-4);
                buf.compact();
                return null;
            }

            int len = buf.get() & 0xFF;
            if (buf.remaining() < len + 2) {
                buf.position(buf.position()-5);
                buf.compact();
                return null;
            }

            byte[] name = new byte[len];
            buf.get(name);
            domain = new String(name, StandardCharsets.US_ASCII);
        } else {
            return new ConnectionRequest(null, null, 0, REP_ADDR_NOT_SUP);
        }

        if (buf.remaining() < 2) {
            buf.compact();
            return null;
        }

        int port = readPort();
        buf.clear();

        return new ConnectionRequest(domain, dstAddr, port, (byte) 0);
    }

    private int readPort() {
        int port = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        ctx.setPendingPort(port);
        return port;
    }


}
