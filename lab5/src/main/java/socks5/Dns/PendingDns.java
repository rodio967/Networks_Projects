package socks5.Dns;

import socks5.connection.Conn;

public class PendingDns {
    public final String qname;
    public final Conn requester;
    public PendingDns(String q, Conn r) {
        qname = q;
        requester = r;
    }
}
