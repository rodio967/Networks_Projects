package socks5;

import socks5.Dns.DnsAttachment;
import socks5.Dns.DnsResolver;
import socks5.connection.Conn;
import socks5.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.*;
import java.util.Iterator;

public class SocksServer {
    private final Selector selector;
    private final ServerSocketChannel server;
    private final DnsResolver dnsResolver;

    public SocksServer(int port) throws IOException {
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress(port));
        server.register(selector, SelectionKey.OP_ACCEPT);

        dnsResolver = new DnsResolver(selector);

        Log.log("Listening on port %d; DNS server %s", port, dnsResolver.getDnsServer());
    }

    public void run() throws IOException {
        while (true) {
            selector.select();

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) continue;

                try {
                    if (key.isAcceptable()) {
                        handleAccept();
                        continue;
                    }

                    Object att = key.attachment();
                    if (att instanceof DnsAttachment) {
                        if (key.isReadable()) {
                            dnsResolver.handleDnsReadable();
                        }
                    } else if (att instanceof Conn c) {
                        if (key.isConnectable()) c.onConnectable();
                        if (key.isReadable()) c.onReadable(key);
                        if (key.isWritable()) c.onWritable(key);
                    }
                } catch (CancelledKeyException | SocketException ignored) {
                    closeKey(key);
                } catch (IOException e) {
                    String msg = e.getMessage();
                    if (msg == null || (!msg.contains("Broken pipe") && !msg.contains("Connection reset"))) {
                        Log.log("IO error: %s", e.getMessage());
                    }
                    closeKey(key);
                } catch (Throwable t) {
                    Log.log("Error: %s", t.getMessage());
                    closeKey(key);
                }
            }
        }
    }

    private void handleAccept() throws IOException {
        SocketChannel ch = server.accept();
        if (ch == null) return;
        ch.configureBlocking(false);
        SelectionKey k = ch.register(selector, SelectionKey.OP_READ);
        Conn c = new Conn(k, ch, selector, dnsResolver);
        k.attach(c);
    }

    private static void closeKey(SelectionKey k) {
        Object att = k.attachment();
        if (att instanceof Conn c) {
            c.close();
        } else {
            try { k.cancel(); } catch (Exception ignored) {}
            try { k.channel().close(); } catch (Exception ignored) {}
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Usage: java SocksServer <port>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        new SocksServer(port).run();
    }
}