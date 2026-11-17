package top.hetaoawa.astra.api;

import java.util.Locale;

/** Requests for reusable Markdown images; binary upload uses PlatformApi.uploadPart. */
public final class MediaImagePayload {
  private MediaImagePayload() {}

  public record Prepare(String fileName, long fileSize, String contentType, String md5, String sha1)
      implements RequestPayload {
    public Prepare {
      if (fileName == null
          || fileName.lastIndexOf('.') < 1
          || fileSize < 1
          || fileSize > 10L * 1024 * 1024)
        throw new IllegalArgumentException("Image size must be between 1 byte and 10 MiB");
      String suffix = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
      String expected =
          switch (suffix) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "";
          };
      if (expected.isEmpty() || !expected.equals(contentType))
        throw new IllegalArgumentException("Image extension and Content-Type must match");
      hash(md5, 32);
      hash(sha1, 40);
    }
  }

  public record Complete(String uploadId, String md5) implements RequestPayload {
    public Complete {
      if (uploadId == null || uploadId.isBlank())
        throw new IllegalArgumentException("Image upload ID required");
      hash(md5, 32);
    }
  }

  private static void hash(String value, int length) {
    if (value == null || !value.matches("[0-9a-fA-F]{" + length + "}"))
      throw new IllegalArgumentException("Expected " + length + " hexadecimal hash characters");
  }
}
