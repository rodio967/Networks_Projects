package socks5.protocol;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static socks5.protocol.SocksProtocol.ATYP_IPV4;
import static socks5.protocol.SocksProtocol.VER;

public class SocksProtocolWriter {
    private final SocketChannel client;

    public SocksProtocolWriter(SocketChannel client) {
        this.client = client;
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

    public void sendErrorReply(byte rep) throws IOException {
        sendReply(rep, new InetSocketAddress("0.0.0.0", 0));
    }
}
