package top.hetaoawa.astra.api;

import java.util.List;

public record Page<T>(List<T> items, int offset, int limit, boolean hasMore) {
  public Page {
    items = List.copyOf(items);
  }
}
