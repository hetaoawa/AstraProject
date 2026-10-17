package top.hetaoawa.astra.runtime;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public final class StatusController {
  private final AstraRuntime runtime;

  public StatusController(AstraRuntime runtime) {
    this.runtime = runtime;
  }

  @GetMapping("/astra/health")
  public ResponseEntity<Map<String, Object>> health() {
    var status = runtime.status();
    return ResponseEntity.status(status.get("status").equals("UP") ? 200 : 503).body(status);
  }
}
