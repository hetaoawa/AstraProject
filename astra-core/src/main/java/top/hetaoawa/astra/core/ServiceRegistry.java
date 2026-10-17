package top.hetaoawa.astra.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import top.hetaoawa.astra.api.*;

public final class ServiceRegistry implements Services {
  private final Map<Class<?>, Owned> services = new ConcurrentHashMap<>();
  private final Map<String, PermissionNode> nodes = new ConcurrentHashMap<>();

  private record Owned(String owner, Object value) {}

  public Services owned(String owner) {
    return new Services() {
      public <T> void register(Class<T> type, T value) {
        Objects.requireNonNull(value);
        if (!type.isInstance(value)) throw new IllegalArgumentException("Wrong service type");
        if (services.putIfAbsent(type, new Owned(owner, value)) != null)
          throw new IllegalStateException("Service already registered: " + type.getName());
      }

      public <T> Optional<T> find(Class<T> type) {
        return ServiceRegistry.this.find(type);
      }

      public void registerPermission(PermissionNode n) {
        if (!owner.equals(n.ownerPlugin()))
          throw new IllegalArgumentException("Node owner mismatch");
        ServiceRegistry.this.registerPermission(n);
      }

      public List<PermissionNode> permissions() {
        return ServiceRegistry.this.permissions();
      }
    };
  }

  public void remove(String owner) {
    services.entrySet().removeIf(e -> e.getValue().owner.equals(owner));
    nodes.entrySet().removeIf(e -> e.getValue().ownerPlugin().equals(owner));
  }

  public <T> void register(Class<T> type, T service) {
    owned("framework").register(type, service);
  }

  public <T> Optional<T> find(Class<T> type) {
    Owned owned = services.get(type);
    return owned == null ? Optional.empty() : Optional.of(type.cast(owned.value));
  }

  public void registerPermission(PermissionNode node) {
    if (nodes.putIfAbsent(node.name(), node) != null)
      throw new IllegalStateException("Permission node already registered: " + node.name());
  }

  public List<PermissionNode> permissions() {
    return nodes.values().stream().sorted(Comparator.comparing(PermissionNode::name)).toList();
  }
}
