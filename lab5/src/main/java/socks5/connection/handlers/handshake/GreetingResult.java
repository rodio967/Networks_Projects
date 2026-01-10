package socks5.connection.handlers.handshake;

public enum GreetingResult {
    NEED_MORE_DATA,
    OK,
    NO_ACCEPTABLE_METHODS,
    INVALID_VER,
    CLIENT_CLOSED
}
