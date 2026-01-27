package socks5.connection.handlers.handshake;

import socks5.auth.AuthConfig;
import socks5.connection.context.ConnectionContext;
import socks5.selector.SelectorHelper;
import socks5.util.State;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import static socks5.protocol.SocksProtocol.*;

public class SocksHandshake {
    private final ConnectionContext ctx;
    private final ByteBuffer buf;
    private final AuthConfig authConfig;

    public SocksHandshake(ConnectionContext ctx, AuthConfig authConfig) {
        this.authConfig = authConfig;
        this.ctx = ctx;
        this.buf = ByteBuffer.allocate(1024);
    }


    public void sendMethodSelection(boolean ok) throws IOException {
        ByteBuffer resp = ByteBuffer.allocate(2);
        byte method;
        if (!ok) {
            method = METHOD_REJECT;
        } else if (authConfig.isEnabled()) {
            method = METHOD_USERNAME_PASSWORD;
        } else {
            method = METHOD_NO_AUTH;
        }

        resp.put(VER)
                .put(method)
                .flip();
        ctx.getClient().write(resp);
    }

    public void sendAuthResponse(boolean ok) throws IOException {
        ByteBuffer resp = ByteBuffer.allocate(2);
        resp.put(AUTH_VER)
                .put(ok ? AUTH_SUCCESS : AUTH_FAILURE)
                .flip();
        ctx.getClient().write(resp);
    }


    public GreetingResult readGreeting() throws IOException {
        SocketChannel client = ctx.getClient();
        int n = client.read(buf);
        if (n == -1) {
            return GreetingResult.CLIENT_CLOSED;
        }
        if (n == 0) return GreetingResult.NEED_MORE_DATA;

        buf.flip();
        if (buf.remaining() < 2) {
            buf.compact();
            return GreetingResult.NEED_MORE_DATA;
        }

        byte ver = buf.get();
        int nMethods = buf.get() & 0xFF;
        if (ver != VER) {
            return GreetingResult.INVALID_VER;
        }

        if (buf.remaining() < nMethods) {
            buf.position(buf.position() - 2);
            buf.compact();
            return GreetingResult.NEED_MORE_DATA;
        }

        boolean supportUserPass = false;
        boolean supportNoAuth = false;
        for (int i = 0; i < nMethods; i++){
            byte method = buf.get();
            if (method == METHOD_NO_AUTH) supportNoAuth = true;
            if (method == METHOD_USERNAME_PASSWORD) supportUserPass = true;
        }
        buf.clear();

        if (authConfig.isEnabled()) {
            return supportUserPass ? GreetingResult.OK : GreetingResult.NO_ACCEPTABLE_METHODS;
        } else {
            return supportNoAuth ? GreetingResult.OK : GreetingResult.NO_ACCEPTABLE_METHODS;
        }
    }

    public AuthResult readAuth() throws IOException {
        SocketChannel client = ctx.getClient();
        int n = client.read(buf);
        if (n == -1) return AuthResult.CLIENT_CLOSED;

        if (n == 0) return AuthResult.NEED_MORE_DATA;

        buf.flip();
        if (buf.remaining() < 2) {
            buf.compact();
            return AuthResult.NEED_MORE_DATA;
        }

        byte ver = buf.get();
        if (ver != AUTH_VER) {
            return AuthResult.INVALID;
        }

        int ulen = buf.get() & 0xFF;
        if (buf.remaining() < ulen + 1) {
            buf.position(buf.position() - 2);
            buf.compact();
            return AuthResult.NEED_MORE_DATA;
        }

        byte[] uname = new byte[ulen];
        buf.get(uname);
        String username = new String(uname, StandardCharsets.UTF_8);

        int plen = buf.get() & 0xFF;
        if (buf.remaining() < plen) {
            buf.position(buf.position() - ulen - 3);
            buf.compact();
            return AuthResult.NEED_MORE_DATA;
        }

        byte[] pass = new byte[plen];
        buf.get(pass);
        String password = new String(pass, StandardCharsets.UTF_8);

        buf.clear();

        boolean valid = authConfig.checkCredentials(username, password);
        return valid ? AuthResult.SUCCESS : AuthResult.FAILURE;
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

    public void enterState(State state) {
        ctx.setState(state);
        SelectorHelper.setInterests(ctx.getClientKey(), true, false, false);
    }

    private int readPort() {
        int port = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        ctx.setPendingPort(port);
        return port;
    }


}
