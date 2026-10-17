package top.hetaoawa.astra.api;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/**
 * Stable keys use length-delimited input; platform identifiers remain opaque strings in the models.
 */
public final class Keys {
  private Keys() {}

  public static String join(String... parts) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
