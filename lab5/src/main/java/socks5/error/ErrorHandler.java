package socks5.error;

import socks5.connection.Conn;
import socks5.connection.context.ConnectionContext;
import socks5.protocol.SocksProtocolWriter;
import socks5.util.Log;
import socks5.util.State;

import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.SelectionKey;


public class ErrorHandler {


    public boolean isExpectedIOError(IOException e) {
        String msg = e.getMessage();
        if (msg == null) return false;

        return msg.contains("Connection reset") ||
                msg.contains("Broken pipe") ||
                msg.contains("Connection reset by peer") ||
                msg.contains("Software caused connection abort");
    }

    public void logConnectionError(SelectionKey key, SocketException e) {
        String context = getConnectionContext(key);

        String msg = e.getMessage();
        if (msg != null && msg.contains("Connection reset")) {
            return;
        }

        Log.log("Connection error %s: %s", context, e.getMessage());
    }

    public void logIOError(SelectionKey key, IOException e) {
        String context = getConnectionContext(key);
        String errorType = e.getClass().getSimpleName();

        Log.log("I/O error %s [%s]: %s", context, errorType, e.getMessage());
    }

    public void logCriticalError(SelectionKey key, Throwable t) {
        String context = getConnectionContext(key);

        Log.log("CRITICAL ERROR %s [%s]: %s",
                context,
                t.getClass().getSimpleName(),
                t.getMessage());

        t.printStackTrace();
    }

    public String getConnectionContext(SelectionKey key) {
        Object att = key.attachment();

        if (att instanceof Conn c) {
            State state = c.getConnectionContext().getState();
            String host = c.getConnectionContext().getPendingHost();

            if (host != null) {
                return String.format(" [%s, %s]", state, host);
            }
            return String.format(" [%s]", state);
        }

        return "";
    }


}