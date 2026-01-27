package socks5;

import socks5.Dns.DnsAttachment;
import socks5.Dns.DnsResolver;
import socks5.auth.AuthConfig;
import socks5.connection.Conn;
import socks5.error.ErrorHandler;
import socks5.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.*;
import java.util.Iterator;

public class SocksServer {
    private final Selector selector;
    private final ServerSocketChannel server;
    private final DnsResolver dnsResolver;
    private final ErrorHandler errorHandler = new ErrorHandler();
    private final AuthConfig authConfig;

    public SocksServer(int port, String filename) throws IOException {
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress(port));
        server.register(selector, SelectionKey.OP_ACCEPT);

        dnsResolver = new DnsResolver(selector);
        authConfig = new AuthConfig(filename);

        Log.log("Listening on port %d; DNS server %s; Auth: %s",
                port, dnsResolver.getDnsServer(), authConfig.isEnabled() ? "enabled" : "disabled");
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
                } catch (CancelledKeyException e) {
                    closeKey(key);
                } catch (SocketException e) {
                    errorHandler.logConnectionError(key, e);
                    closeKey(key);
                }
                catch (IOException e) {
                    if (!errorHandler.isExpectedIOError(e)) {
                        errorHandler.logIOError(key, e);
                    }
                    closeKey(key);
                } catch (Throwable t) {
                    errorHandler.logCriticalError(key, t);
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
        Conn c = new Conn(k, ch, selector, dnsResolver, authConfig);
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

    public static void main(String[] args) {
        try {
            if (args.length != 2) {
                System.out.println("Usage: java SocksServer <port> <filename>");
                System.exit(1);
            }
            int port = Integer.parseInt(args[0]);
            String filename = args[1];

            if (port < 1 || port > 65535) {
                System.err.println("Error: Port must be between 1 and 65535");
                System.exit(1);
            }

            new SocksServer(port, filename).run();
        } catch (FileNotFoundException e) {
            System.err.println("Error: Auth config file not found: " + e.getMessage());
            System.err.println("Create the file or check the path");
            System.exit(1);
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid port number: " + args[0]);
            System.exit(1);
        } catch (IllegalStateException e) {
            System.err.println("Error: Invalid configuration - " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: Failed to start server - " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}