package socks5.connection.handlers.handshake;

public enum AuthResult {
    NEED_MORE_DATA,
    SUCCESS,
    FAILURE,
    INVALID,
    CLIENT_CLOSED
}
