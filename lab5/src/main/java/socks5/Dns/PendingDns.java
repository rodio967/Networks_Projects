package socks5.Dns;

import socks5.connection.Conn;

import java.util.concurrent.ScheduledFuture;

public class PendingDns {
    public final String qname;
    public final Conn requester;
    public final int id;
    public byte[] data;
    public int attempts = 0;
    public ScheduledFuture<?> retryFuture;

    public PendingDns(String q, Conn r, int id, byte[] data) {
        qname = q;
        requester = r;
        this.id = id;
        this.data = data;
    }
}
