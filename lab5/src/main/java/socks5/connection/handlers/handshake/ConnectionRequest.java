package socks5.connection.handlers.handshake;

import java.net.InetAddress;

public record ConnectionRequest(String domain, InetAddress address, int port) {

    public boolean isDomain() {
        return domain != null;
    }

    public boolean isAddress() {
        return address != null;
    }

}
