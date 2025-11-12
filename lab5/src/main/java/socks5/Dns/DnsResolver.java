package socks5.Dns;

import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import socks5.util.Log;
import socks5.util.State;
import socks5.connection.Conn;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class DnsResolver {
    private static final byte REP_HOST_UNREACH = 0x04;

    private final DatagramChannel dns;
    private final InetSocketAddress dnsServer;
    private final Map<Integer, PendingDns> dnsPending = new HashMap<>();
    private final Random rand = new Random();

    public DnsResolver(Selector selector) throws IOException {
        dnsServer = pickDnsServer();
        dns = DatagramChannel.open(StandardProtocolFamily.INET);
        dns.configureBlocking(false);
        dns.bind(new InetSocketAddress(0));
        dns.register(selector, SelectionKey.OP_READ, new DnsAttachment());
    }

    public static InetSocketAddress pickDnsServer() {
        try {
            ResolverConfig cfg = ResolverConfig.getCurrentConfig();
            if (cfg != null && cfg.servers() != null && !cfg.servers().isEmpty()) {
                InetSocketAddress isa = cfg.servers().get(0);
                int port = isa.getPort() > 0 ? isa.getPort() : 53;
                InetAddress a = isa.getAddress();
                if (a instanceof Inet4Address) {
//                    System.out.println("выбран обычный dns");
                    return new InetSocketAddress(a, port);
                }
            }
        } catch (Throwable ignored) {}
        return new InetSocketAddress("8.8.8.8", 53);
    }

    public InetSocketAddress getDnsServer() {
        return dnsServer;
    }

    public void sendDnsQuery(String qname, Conn requester) throws IOException {
        Name n;
        try {
            n = Name.fromString(qname.endsWith(".") ? qname : qname + ".");
        } catch (TextParseException e) {
            requester.fail(REP_HOST_UNREACH, "Bad domain");
            return;
        }

        int id;
        do {
            id = rand.nextInt(0x10000);
        } while (dnsPending.containsKey(id));

        Record q = Record.newRecord(n, Type.A, DClass.IN);
        Message m = Message.newQuery(q);
        m.getHeader().setID(id);
        byte[] wire = m.toWire();
        dns.send(ByteBuffer.wrap(wire), dnsServer);
        dnsPending.put(id, new PendingDns(qname, requester));
        requester.state = State.RESOLVING;
    }

    public void handleDnsReadable() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1500);
        SocketAddress from = dns.receive(buf);

        if (from == null) return;
        buf.flip();
        byte[] arr = new byte[buf.remaining()];
        buf.get(arr);

        try {
            Message resp = new Message(arr);
            int id = resp.getHeader().getID();

            PendingDns pend = dnsPending.remove(id);
            if (pend == null) return;

            InetAddress a = null;
            for (Record r : resp.getSectionArray(Section.ANSWER)) {
                if (r.getType() == Type.A) {
                    a = ((ARecord) r).getAddress();
                    break;
                }
            }

            if (a == null) {
                pend.requester.fail(REP_HOST_UNREACH, "No A record");
            } else {
                pend.requester.onResolved(a);
            }
        } catch (Exception e) {
            Log.log("DNS parse error: %s", e);
        }
    }

    public void clearDns(Conn conn) {
        dnsPending.entrySet().removeIf(e -> e.getValue().requester == conn);
    }

}