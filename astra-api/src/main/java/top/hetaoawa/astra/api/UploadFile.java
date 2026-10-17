package top.hetaoawa.astra.api;

import java.util.Objects;

public record UploadFile(String name, String contentType, byte[] data) {
  public UploadFile {
    Objects.requireNonNull(name);
    Objects.requireNonNull(contentType);
    data = data.clone();
    if (name.contains("\r")
        || name.contains("\n")
        || name.contains("\"")
        || contentType.contains("\r")
        || contentType.contains("\n"))
      throw new IllegalArgumentException("Invalid upload metadata");
  }

  @Override
  public byte[] data() {
    return data.clone();
  }
}
