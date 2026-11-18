package com.zaza.housing.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

public final class NetworkPeer {
  private NetworkPeer() {}

  public static String bucket(String address) {
    if (address.indexOf(':') < 0) return address;
    try {
      var ip = InetAddress.ofLiteral(address);
      if (ip instanceof Inet6Address) {
        return HexFormat.of().formatHex(ip.getAddress(), 0, 8) + "/64";
      }
      return ip.getHostAddress();
    } catch (IllegalArgumentException invalidAddress) {
      return address;
    }
  }
}
