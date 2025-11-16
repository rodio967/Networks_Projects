package socks5.selector;

import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;

public class SelectorHelper {
    public static void setInterests(SelectionKey k, boolean read, boolean write, boolean connect) {
        int ops = 0;
        if (read) ops |= SelectionKey.OP_READ;
        if (write) ops |= SelectionKey.OP_WRITE;
        if (connect) ops |= SelectionKey.OP_CONNECT;
        try {
            k.interestOps(ops);
        } catch (CancelledKeyException ignored) {}
    }

    public static void setReadOnly(SelectionKey key) {
        setInterests(key, true, false, false);
    }

    public static void setWriteOnly(SelectionKey key) {
        setInterests(key, false, true, false);
    }

    public void setReadWrite(SelectionKey key) {
        setInterests(key, true, true, false);
    }

}
