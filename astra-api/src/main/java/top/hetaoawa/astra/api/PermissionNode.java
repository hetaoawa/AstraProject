package top.hetaoawa.astra.api;

public record PermissionNode(String name, String description, String ownerPlugin) {
  public PermissionNode {
    if (name == null || !name.matches("[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_*-]+)+"))
      throw new IllegalArgumentException("Permission node must use dotted names");
  }
}
