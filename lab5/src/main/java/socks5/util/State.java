package socks5.util;

public enum State {
    GREETING,
    REQUEST,
    RESOLVING,
    CONNECTING,
    RELAY,
    CLOSED
}
