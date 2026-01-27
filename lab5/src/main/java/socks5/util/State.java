package socks5.util;

public enum State {
    GREETING,
    AUTH,
    REQUEST,
    RESOLVING,
    CONNECTING,
    RELAY,
    CLOSED
}
