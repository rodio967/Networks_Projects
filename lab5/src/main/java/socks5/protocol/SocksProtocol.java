package socks5.protocol;

public class SocksProtocol {
    public static final byte VER = 0x05;
    public static final byte METHOD_NO_AUTH = 0x00;
    public static final byte METHOD_USERNAME_PASSWORD = 0x02;
    public static final byte METHOD_REJECT = (byte) 0xFF;

    public static final byte AUTH_VER = 0x01;
    public static final byte AUTH_SUCCESS = 0x00;
    public static final byte AUTH_FAILURE = 0x01;


    public static final byte CMD_CONNECT = 0x01;

    public static final byte ATYP_IPV4 = 0x01;
    public static final byte ATYP_IPV6 = 0x04;
    public static final byte ATYP_DOMAIN = 0x03;

    public static final byte REP_SUCCEEDED = 0x00;
    public static final byte REP_GEN_FAIL = 0x01;
    public static final byte REP_RULES_FAIL = 0x02;
    public static final byte REP_NET_UNREACH = 0x03;
    public static final byte REP_HOST_UNREACH = 0x04;
    public static final byte REP_CONN_REFUSED = 0x05;
    public static final byte REP_CMD_NOT_SUP = 0x07;
    public static final byte REP_ADDR_NOT_SUP = 0x08;
}
