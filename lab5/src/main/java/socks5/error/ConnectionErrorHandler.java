//package socks5.error;
//
//import socks5.connection.context.ConnectionContext;
//import socks5.protocol.SocksProtocolWriter;
//import socks5.util.Log;
//
//import java.io.IOException;
//
//import static socks5.protocol.SocksProtocol.*;
//
//
//public class ConnectionErrorHandler {
//    private final ConnectionContext ctx;
//    private final SocksProtocolWriter writer;
//
//    public ConnectionErrorHandler(ConnectionContext ctx, SocksProtocolWriter writer) {
//        this.ctx = ctx;
//        this.writer = writer;
//    }
//
//    public void fail(byte rep, String reason) throws IOException {
//        writer.sendErrorReply(rep);
//        Log.log("Connection failed: %s", reason);
//        ctx.closeAll();
//    }
//
//    public void failInvalidAddress(String details) throws IOException {
//        fail(REP_ADDR_NOT_SUP, "Invalid address: " + details);
//    }
//
//
//    public void failConnectionRefused() throws IOException {
//        fail(REP_CONN_REFUSED, "Connection refused");
//    }
//
//
//    public void failHostUnreachable() throws IOException {
//        fail(REP_HOST_UNREACH, "Host unreachable");
//    }
//
//
//    public void failNetworkError(String details) throws IOException {
//        fail(REP_NET_UNREACH, "Network error: " + details);
//    }
//
//
//    public void failUnsupportedCommand() throws IOException {
//        fail(REP_CMD_NOT_SUP, "Command not supported");
//    }
//
//
//    public void failUnsupportedAddressType() throws IOException {
//        fail(REP_ADDR_NOT_SUP, "Address type not supported");
//    }
//}