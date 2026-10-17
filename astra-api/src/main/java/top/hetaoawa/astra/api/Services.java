package top.hetaoawa.astra.api;

import java.util.List;
import java.util.Optional;

public interface Services {
  <T> void register(Class<T> type, T service);

  <T> Optional<T> find(Class<T> type);

  void registerPermission(PermissionNode node);

  List<PermissionNode> permissions();
}
