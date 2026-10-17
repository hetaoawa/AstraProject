package top.hetaoawa.astra.api;

import java.util.List;
import java.util.Map;

public record MessageContent(
    Integer messageType,
    PlatformMember author,
    List<PlatformMember> mentions,
    List<Attachment> attachments,
    Ark ark,
    List<Element> elements,
    Reference reference,
    Merged merged,
    SceneContext sceneContext,
    Parallel parallel,
    Metadata metadata,
    List<Embed> embeds,
    MessagePayload.Ark channelArk) {
  public MessageContent {
    mentions = mentions == null ? List.of() : List.copyOf(mentions);
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
    elements = elements == null ? List.of() : List.copyOf(elements);
    embeds = embeds == null ? List.of() : List.copyOf(embeds);
  }

  public record Metadata(
      String groupId,
      String groupOpenid,
      String guildId,
      String channelId,
      java.time.Instant timestamp,
      java.time.Instant editedTimestamp,
      Boolean mentionEveryone,
      Long seq,
      String seqInChannel,
      String srcGuildId,
      Map<String, Object> raw) {}

  public record Embed(
      String title,
      String prompt,
      Thumbnail thumbnail,
      List<EmbedField> fields,
      Map<String, Object> raw) {}

  public record Thumbnail(String url, Map<String, Object> raw) {}

  public record EmbedField(String name, Map<String, Object> raw) {}

  public record Attachment(
      String url,
      String filename,
      Integer width,
      Integer height,
      Long size,
      String contentType,
      String voiceWavUrl,
      String asrReferText,
      Map<String, Object> raw) {}

  public record Ark(
      String prompt,
      String arkType,
      String arkName,
      Map<String, Object> fields,
      Map<String, Object> raw) {}

  public record SceneContext(
      String source,
      List<String> ext,
      MessageIndex messageIndex,
      MessageIndex referenceIndex,
      String authToken) {}

  public record Parallel(List<Element> msgNodes, Map<String, Object> raw) {
    public Parallel {
      msgNodes = List.copyOf(msgNodes);
    }
  }

  public record Element(
      MessageIndex index,
      PlatformMember author,
      Integer messageType,
      String content,
      List<Attachment> attachments,
      Ark ark,
      List<Element> elements,
      FormattedBody formatted,
      Map<String, Object> raw) {
    public Element {
      attachments = List.copyOf(attachments);
      elements = List.copyOf(elements);
    }

    public String displayText() {
      if (formatted != null) return formatted.displayText();
      if (elements.isEmpty()) return content;
      String nested =
          elements.stream()
              .map(Element::displayText)
              .collect(java.util.stream.Collectors.joining("\n"));
      return java.util.Objects.equals(messageType, 103)
          ? nested
                  .lines()
                  .map(line -> "> " + line)
                  .collect(java.util.stream.Collectors.joining("\n"))
              + (content.isBlank() ? "" : "\n" + content)
          : nested + (content.isBlank() ? "" : "\n" + content);
    }
  }

  public record Reference(
      MessageIndex index,
      String messageId,
      Boolean ignoreGetMessageError,
      String replyText,
      List<Element> elements,
      String formattedBody) {
    public Reference {
      elements = List.copyOf(elements);
    }

    public String displayText() {
      String quoted =
          elements.stream()
              .map(Element::displayText)
              .collect(java.util.stream.Collectors.joining("\n"));
      return quoted.isEmpty()
          ? replyText
          : quoted
                  .lines()
                  .map(line -> "> " + line)
                  .collect(java.util.stream.Collectors.joining("\n"))
              + (replyText.isBlank() ? "" : "\n" + replyText);
    }
  }

  public record Merged(List<Element> elements, FormattedBody formatted, String originalBody) {
    public Merged {
      elements = List.copyOf(elements);
    }

    public String displayText() {
      return formatted != null
          ? formatted.displayText()
          : elements.stream()
              .map(Element::displayText)
              .collect(java.util.stream.Collectors.joining("\n"));
    }
  }

  public record FormattedBody(
      String original, String title, List<FormattedEntry> entries, boolean complete) {
    public FormattedBody {
      entries = List.copyOf(entries);
    }

    public String displayText() {
      return complete
          ? entries.stream()
              .map(e -> e.ordinal() + ". " + e.displayText())
              .collect(java.util.stream.Collectors.joining("\n"))
          : original;
    }
  }

  public record FormattedEntry(
      int ordinal,
      String content,
      String senderName,
      String messageTypeLabel,
      boolean mergedRecord,
      List<FormattedEntry> related,
      String original,
      boolean complete) {
    public FormattedEntry {
      related = List.copyOf(related);
    }

    public String displayText() {
      String text = (senderName == null ? "" : senderName + ": ") + content;
      if (related.isEmpty()) return text;
      String nested =
          related.stream()
              .map(FormattedEntry::displayText)
              .collect(java.util.stream.Collectors.joining("\n"));
      return text
          + "\n"
          + nested
              .lines()
              .map(line -> "    " + line)
              .collect(java.util.stream.Collectors.joining("\n"));
    }
  }
}
