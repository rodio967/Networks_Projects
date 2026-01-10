package socks5.Dns;

import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import socks5.connection.Conn;
import socks5.util.Log;
import socks5.util.State;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DnsResolver {
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 3000;

    private final Object sendLock = new Object();
    private final DatagramChannel dns;
    private final InetSocketAddress dnsServer;
    private final Map<Integer, PendingDns> dnsPending = new ConcurrentHashMap<>();
    private final Random rand = new Random();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dns-retry");
        t.setDaemon(true);
        return t;
    });

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
            requester.onDnsFailed("Bad domain: " + qname);
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

        PendingDns pending = new PendingDns(qname, requester, id, wire);
        dnsPending.put(id, pending);

        scheduleRetry(id, 0);
        requester.getConnectionContext().setState(State.RESOLVING);
    }

    private void scheduleRetry(int id, int delay) {
        scheduler.schedule(() -> {
            PendingDns pending = dnsPending.get(id);
            if (pending == null) return;

            if (pending.requester.getConnectionContext().getState() == State.CLOSED) {
                dnsPending.remove(id);
                return;
            }

            if (pending.attempts < MAX_ATTEMPTS) {
                try {
                    synchronized (sendLock) {
                        dns.send(ByteBuffer.wrap(pending.data), dnsServer);
                    }
                    pending.attempts++;

                    if (pending.attempts > 1) {
                        Log.log("DNS retry #%d for %s", pending.attempts, pending.qname);
                    }

                    scheduleRetry(id, RETRY_DELAY_MS);
                } catch (IOException e) {
                    dnsPending.remove(id);
                    Log.log("DNS send failed: %s", pending.qname);
                    pending.requester.close();
                }
            } else {
                dnsPending.remove(id);
                Log.log("DNS timeout after %d attempts: %s", MAX_ATTEMPTS, pending.qname);
                pending.requester.close();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void handleDnsReadable() throws IOException {
        while (true) {
            ByteBuffer buf = ByteBuffer.allocate(1500);
            SocketAddress from = dns.receive(buf);

            if (from == null) break;

            buf.flip();
            if (buf.remaining() == 0) break;

            byte[] arr = new byte[buf.remaining()];
            buf.get(arr);

            try {
                Message resp = new Message(arr);
                int id = resp.getHeader().getID();

                PendingDns pend = dnsPending.remove(id);
                if (pend == null) {
                    continue;
                }

                if (pend.requester.getConnectionContext().getState() == State.CLOSED) {
                    continue;
                }

                InetAddress a = null;
                for (Record r : resp.getSectionArray(Section.ANSWER)) {
                    if (r.getType() == Type.A) {
                        a = ((ARecord) r).getAddress();
                        break;
                    }
                }

                if (a == null) {
                    pend.requester.onDnsFailed("No A record: " + pend.qname);
                } else {
                    pend.requester.onResolved(a);
                }
            } catch (Exception e) {
                Log.log("DNS parse error: %s (%s)", e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    public void clearDns(Conn conn) {
        dnsPending.entrySet().removeIf(e -> e.getValue().requester == conn);
    }

    public void shutdown() {
        scheduler.shutdownNow();
        try {
            dns.close();
        } catch (IOException ignored) {}
    }

    public int getSize() {
        return dnsPending.size();
    }
}