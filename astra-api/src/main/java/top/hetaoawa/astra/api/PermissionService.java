package top.hetaoawa.astra.api;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public interface PermissionService {
  CompletionStage<Decision> check(Identity subject, String node, Map<String, Set<String>> contexts);

  enum Decision {
    ALLOW,
    DENY,
    UNDEFINED
  }
}
