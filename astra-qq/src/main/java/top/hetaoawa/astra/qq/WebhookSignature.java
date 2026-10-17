package top.hetaoawa.astra.qq;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

public final class WebhookSignature {
  private final Ed25519PrivateKeyParameters key;

  public WebhookSignature(String secret) {
    if (secret == null || secret.isEmpty())
      throw new IllegalArgumentException("Bot secret missing");
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8), seed = new byte[32];
    for (int i = 0; i < seed.length; i++) seed[i] = bytes[i % bytes.length];
    key = new Ed25519PrivateKeyParameters(seed);
  }

  public boolean verify(String timestamp, String hex, byte[] body, Duration tolerance) {
    try {
      long t = Long.parseLong(timestamp);
      if (tolerance != null && Math.abs(Instant.now().getEpochSecond() - t) > tolerance.toSeconds())
        return false;
      byte[] signature = HexFormat.of().parseHex(hex);
      if (signature.length != 64) return false;
      Ed25519Signer signer = new Ed25519Signer();
      signer.init(false, key.generatePublicKey());
      byte[] ts = timestamp.getBytes(StandardCharsets.UTF_8);
      signer.update(ts, 0, ts.length);
      signer.update(body, 0, body.length);
      return signer.verifySignature(signature);
    } catch (Exception e) {
      return false;
    }
  }

  public String sign(String timestamp, String plainToken) {
    Ed25519Signer signer = new Ed25519Signer();
    signer.init(true, key);
    byte[] bytes = (timestamp + plainToken).getBytes(StandardCharsets.UTF_8);
    signer.update(bytes, 0, bytes.length);
    return HexFormat.of().formatHex(signer.generateSignature());
  }
}
