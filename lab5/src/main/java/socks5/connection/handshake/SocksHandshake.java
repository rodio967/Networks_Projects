package socks5.connection.handshake;

import socks5.util.State;
import socks5.connection.Conn;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static socks5.connection.SocksProtocol.*;

public class SocksHandshake {
    private final Conn conn;
    private final ByteBuffer ctrl;

    public SocksHandshake(Conn conn) {
        this.conn = conn;
        this.ctrl = ByteBuffer.allocate(1024);
    }


    public void sendMethodSelection(boolean ok) throws IOException {
        ByteBuffer resp = ByteBuffer.allocate(2);
        resp.put(VER)
                .put(ok ? METHOD_NO_AUTH : METHOD_REJECT)
                .flip();
        conn.client.write(resp);

        if (!ok) {
            conn.closeAll();
            return;
        }

        conn.state = State.REQUEST;
        conn.setInterests(conn.clientKey, true, false, false);
    }


    public void readGreeting() throws IOException {
        int n = conn.readFromClient(ctrl);
        if (n <= 0) return;

        ctrl.flip();
        if (ctrl.remaining() < 2) {
            ctrl.compact();
            return;
        }

        byte ver = ctrl.get();
        int nMethods = ctrl.get() & 0xFF;
        if (ver != VER) {
            conn.closeAll();
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

    public void readRequest() throws IOException {
        int n = conn.readFromClient(ctrl);
        if (n <= 0) return;

        ctrl.flip();
        if (ctrl.remaining() < 4) {
            ctrl.compact();
            return;
        }

        byte ver = ctrl.get();
        byte cmd = ctrl.get();
        ctrl.get();
        byte atyp = ctrl.get();
        if (ver != VER || cmd != CMD_CONNECT) {
            conn.sendReply(REP_CMD_NOT_SUP, new InetSocketAddress("0.0.0.0", 0));
            conn.closeAll();
            return;
        }

        parseRequestAddress(atyp);
    }

    public void parseRequestAddress(byte atyp) throws IOException {
        InetAddress dstAddr = null;
        String domain = null;
        if (atyp == ATYP_IPV4) {
            if (ctrl.remaining() < 4 + 2) {
                ctrl.position(ctrl.position() - 4);
                ctrl.compact();
                return;
            }

            byte[] a = new byte[4];
            ctrl.get(a);
            try {
                dstAddr = InetAddress.getByAddress(a);
            } catch (Exception e) {
                conn.fail(REP_ADDR_NOT_SUP, "bad ipv4");
                return;
            }

        } else if (atyp == ATYP_DOMAIN) {
            if (ctrl.remaining() < 1) {
                ctrl.position(ctrl.position()-4);
                ctrl.compact();
                return;
            }

            int len = ctrl.get() & 0xFF;
            if (ctrl.remaining() < len + 2) {
                ctrl.position(ctrl.position()-5);
                ctrl.compact();
                return;
            }

            byte[] name = new byte[len];
            ctrl.get(name);
            domain = new String(name, StandardCharsets.US_ASCII);
        } else {
            conn.sendReply(REP_ADDR_NOT_SUP, new InetSocketAddress("0.0.0.0", 0));
            conn.closeAll();
            return;
        }

        if (ctrl.remaining() < 2) {
            ctrl.compact();
            return;
        }

        conn.pendingPort = ((ctrl.get() & 0xFF) << 8) | (ctrl.get() & 0xFF);
        ctrl.clear();

        if (domain != null) {
            conn.pendingHost = domain;
            conn.dnsResolver.sendDnsQuery(domain, conn);
        }
        else {
            conn.connectionManager.startConnect(new InetSocketAddress(dstAddr, conn.pendingPort));
        }
    }


}
