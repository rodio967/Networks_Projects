package socks5.connection.handlers.handshake;

import socks5.connection.context.ConnectionContext;
import socks5.error.ConnectionErrorHandler;
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
    private final ByteBuffer ctrl;
    private final SocksProtocolWriter writer;
    private final ConnectionErrorHandler errorHandler;

    public SocksHandshake(ConnectionContext ctx, SocksProtocolWriter writer, ConnectionErrorHandler errorHandler) {
        this.ctx = ctx;
        this.ctrl = ByteBuffer.allocate(1024);
        this.writer = writer;
        this.errorHandler = errorHandler;
    }


    public void sendMethodSelection(boolean ok) throws IOException {
        ByteBuffer resp = ByteBuffer.allocate(2);
        resp.put(VER)
                .put(ok ? METHOD_NO_AUTH : METHOD_REJECT)
                .flip();
        ctx.getClient().write(resp);

        if (!ok) {
            ctx.closeAll();
            return;
        }

        ctx.setState(State.REQUEST);
        SelectorHelper.setInterests(ctx.getClientKey(), true, false, false);
    }


    public void readGreeting() throws IOException {
        SocketChannel client = ctx.getClient();
        int n = client.read(ctrl);
        if (n == -1) {
            ctx.closeAll();
            return;
        }
        if (n == 0) return;

        ctrl.flip();
        if (ctrl.remaining() < 2) {
            ctrl.compact();
            return;
        }

        byte ver = ctrl.get();
        int nMethods = ctrl.get() & 0xFF;
        if (ver != VER) {
            ctx.closeAll();
            return;
        }
        if (ctrl.remaining() < nMethods) {
            ctrl.position(ctrl.position()-2);
            ctrl.compact();
            return;
        }


        boolean ok = false;
        for (int i = 0; i < nMethods; i++){
            if (ctrl.get() == METHOD_NO_AUTH){
                ok = true;
            }
        }
        ctrl.clear();

        sendMethodSelection(ok);
    }

    public ConnectionRequest readRequest() throws IOException {
        SocketChannel client = ctx.getClient();
        int n = client.read(ctrl);
        if (n == -1) {
            ctx.closeAll();
            return null;
        }
        if (n == 0) return null;

        ctrl.flip();
        if (ctrl.remaining() < 4) {
            ctrl.compact();
            return null;
        }

        byte ver = ctrl.get();
        byte cmd = ctrl.get();
        ctrl.get();
        byte atyp = ctrl.get();
        if (ver != VER || cmd != CMD_CONNECT) {
            writer.sendReply(REP_CMD_NOT_SUP, new InetSocketAddress("0.0.0.0", 0));
            ctx.closeAll();
            return null;
        }

        return parseRequestAddress(atyp);
    }

    public ConnectionRequest parseRequestAddress(byte atyp) throws IOException {
        InetAddress dstAddr = null;
        String domain = null;
        if (atyp == ATYP_IPV4) {
            if (ctrl.remaining() < 4 + 2) {
                ctrl.position(ctrl.position() - 4);
                ctrl.compact();
                return null;
            }

            byte[] a = new byte[4];
            ctrl.get(a);
            try {
                dstAddr = InetAddress.getByAddress(a);
            } catch (Exception e) {
                errorHandler.fail(REP_ADDR_NOT_SUP, "bad ipv4");
                return null;
            }

        } else if (atyp == ATYP_DOMAIN) {
            if (ctrl.remaining() < 1) {
                ctrl.position(ctrl.position()-4);
                ctrl.compact();
                return null;
            }

            int len = ctrl.get() & 0xFF;
            if (ctrl.remaining() < len + 2) {
                ctrl.position(ctrl.position()-5);
                ctrl.compact();
                return null;
            }

            byte[] name = new byte[len];
            ctrl.get(name);
            domain = new String(name, StandardCharsets.US_ASCII);
        } else {
            writer.sendReply(REP_ADDR_NOT_SUP, new InetSocketAddress("0.0.0.0", 0));
            ctx.closeAll();
            return null;
        }

        if (ctrl.remaining() < 2) {
            ctrl.compact();
            return null;
        }

        int port = ((ctrl.get() & 0xFF) << 8) | (ctrl.get() & 0xFF);
        ctx.setPendingPort(port);
        ctrl.clear();

        return new ConnectionRequest(domain, dstAddr, port);
    }


}
