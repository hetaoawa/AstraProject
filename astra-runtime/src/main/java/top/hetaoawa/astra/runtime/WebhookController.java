package top.hetaoawa.astra.runtime;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.qq.*;

@RestController
public final class WebhookController {
  private final AstraRuntime runtime;

  public WebhookController(AstraRuntime runtime) {
    this.runtime = runtime;
  }

  @PostMapping(
      value = "/qq/webhook/{environment}/{appId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> receive(
      @PathVariable String environment, @PathVariable String appId, HttpServletRequest request) {
    try {
      var id = new BotId(appId, environment);
      var configured = runtime.bot(id);
      if (configured.isEmpty() || !configured.get().transport().equals("WEBHOOK"))
        return error(404, "BOT_NOT_CONFIGURED");
      if (!appId.equals(request.getHeader("X-Bot-Appid"))) return error(401, "BOT_HEADER_MISMATCH");
      if (request.getContentLengthLong() > 2 * 1024 * 1024) return error(413, "PAYLOAD_TOO_LARGE");
      byte[] body = request.getInputStream().readNBytes(2 * 1024 * 1024 + 1);
      if (body.length > 2 * 1024 * 1024) return error(413, "PAYLOAD_TOO_LARGE");
      var payload = QqJson.map(new String(body, StandardCharsets.UTF_8));
      long op = QqJson.number(payload.get("op"), -1);
      var signature = new WebhookSignature(configured.get().secret());
      if (op == 13) {
        var data = QqJson.object(payload.get("d"));
        String timestamp = QqJson.string(data.get("event_ts")),
            token = QqJson.string(data.get("plain_token"));
        if (timestamp.isBlank()
            || token.isBlank()
            || !timestamp.matches("[0-9]{1,16}")
            || token.length() > 1024
            || token.contains("{")
            || token.contains("[")) return error(400, "INVALID_CHALLENGE");
        runtime.capturePacket(id, "WEBHOOK", new String(body, StandardCharsets.UTF_8));
        return ResponseEntity.ok(
            Map.of("plain_token", token, "signature", signature.sign(timestamp, token)));
      }
      if (!signature.verify(
          request.getHeader("X-Signature-Timestamp"),
          request.getHeader("X-Signature-Ed25519"),
          body,
          configured.get().signatureTolerance())) return error(401, "INVALID_SIGNATURE");
      runtime.capturePacket(id, "WEBHOOK", new String(body, StandardCharsets.UTF_8));
      if (op != 0) return error(400, "UNSUPPORTED_OPCODE");
      if (!runtime.receive(runtime.normalize(id, payload))) return error(503, "QUEUE_UNAVAILABLE");
      return ResponseEntity.ok(Map.of("op", 12, "d", 0));
    } catch (IllegalArgumentException e) {
      return error(400, "INVALID_PAYLOAD");
    } catch (Exception e) {
      return error(503, "FRAMEWORK_UNAVAILABLE");
    }
  }

  private static ResponseEntity<Map<String, Object>> error(int status, String code) {
    return ResponseEntity.status(status).body(Map.of("code", code));
  }
}
