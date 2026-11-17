package top.hetaoawa.astra.api;

import java.util.*;

/** Common QQ send payload including passive-reply fields; Messaging.reply manages those fields. */
public record MessagePayload(
    Integer msgType,
    String content,
    Markdown markdown,
    Keyboard keyboard,
    Media media,
    Reference messageReference,
    String msgId,
    String eventId,
    Integer msgSeq,
    String image,
    Embed embed,
    Ark ark,
    Card card)
    implements RequestPayload {
  public MessagePayload {
    if (msgId != null && eventId != null)
      throw new IllegalArgumentException("msg_id and event_id are mutually exclusive");
    if (msgSeq != null && msgSeq < 1)
      throw new IllegalArgumentException("Positive reply sequence required");
    if (markdown != null && content != null && !content.isEmpty())
      throw new IllegalArgumentException("Markdown requires empty content");
    if (msgType != null
        && (msgType == 2 && markdown == null
            || msgType == 7 && media == null
            || msgType == 1 && embed == null
            || msgType == 3 && ark == null
            || msgType == 8 && card == null
            || !Set.of(0, 1, 2, 3, 7, 8).contains(msgType)))
      throw new IllegalArgumentException("Message type and content mismatch");
  }

  public MessagePayload(
      Integer msgType,
      String content,
      Markdown markdown,
      Keyboard keyboard,
      Media media,
      Reference messageReference,
      String msgId,
      String eventId,
      Integer msgSeq,
      String image,
      Embed embed,
      Ark ark) {
    this(
        msgType,
        content,
        markdown,
        keyboard,
        media,
        messageReference,
        msgId,
        eventId,
        msgSeq,
        image,
        embed,
        ark,
        null);
  }

  /** Requires group administrator permission; the platform permits one use per group per day. */
  public static final String AT_EVERYONE = "<qqbot-at-everyone />";

  public static MessagePayload card(Card card) {
    return new MessagePayload(
        8,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Objects.requireNonNull(card));
  }

  public record Card(String type, CardContent content) implements RequestPayload {
    public Card {
      if (!"tuwen".equals(type)) throw new IllegalArgumentException("Unsupported card type");
      Objects.requireNonNull(content);
    }

    public static Card tuwen(String title, String description, String url, String picUrl) {
      return new Card("tuwen", new CardContent(title, description, url, picUrl));
    }
  }

  public record CardContent(String title, String description, String url, String picUrl)
      implements RequestPayload {
    public CardContent {
      textLength(title, 50);
      textLength(description, 200);
      httpsUrl(url);
      httpsUrl(picUrl);
    }
  }

  private static void textLength(String text, int maximum) {
    if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > maximum)
      throw new IllegalArgumentException("Card text length must be between 1 and " + maximum);
  }

  private static void httpsUrl(String value) {
    textLength(value, 1024);
    var uri = java.net.URI.create(value);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
      throw new IllegalArgumentException("Card URL must use HTTPS");
  }

  public MessagePayload(
      Integer msgType,
      String content,
      Markdown markdown,
      Keyboard keyboard,
      Media media,
      Reference messageReference,
      String msgId,
      String eventId,
      Integer msgSeq) {
    this(
        msgType,
        content,
        markdown,
        keyboard,
        media,
        messageReference,
        msgId,
        eventId,
        msgSeq,
        null,
        null,
        null);
  }

  public static MessagePayload channelText(String text, String imageUrl) {
    return new MessagePayload(
        null,
        Objects.requireNonNull(text),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        imageUrl,
        null,
        null);
  }

  public static MessagePayload embed(Embed embed) {
    return new MessagePayload(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Objects.requireNonNull(embed),
        null);
  }

  public static MessagePayload ark(Ark ark) {
    return new MessagePayload(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Objects.requireNonNull(ark));
  }

  public static MessagePayload text(String text) {
    return new MessagePayload(
        0, Objects.requireNonNull(text), null, null, null, null, null, null, null);
  }

  public static MessagePayload markdown(Markdown markdown, Keyboard keyboard) {
    return new MessagePayload(
        2, null, Objects.requireNonNull(markdown), keyboard, null, null, null, null, null);
  }

  public static MessagePayload media(String fileInfo) {
    return new MessagePayload(7, null, null, null, new Media(fileInfo), null, null, null, null);
  }

  public MessagePayload reference(Reference reference) {
    return new MessagePayload(
        msgType, content, markdown, keyboard, media, reference, msgId, eventId, msgSeq, image,
        embed, ark, card);
  }

  public record Embed(String title, String prompt, Thumbnail thumbnail, List<EmbedField> fields)
      implements RequestPayload {}

  public record Thumbnail(String url) implements RequestPayload {}

  public record EmbedField(String name) implements RequestPayload {}

  public record Ark(Integer templateId, List<ArkKv> kv) implements RequestPayload {}

  public record ArkKv(String key, String value, List<ArkObject> obj) implements RequestPayload {}

  public record ArkObject(List<ArkObjectKv> objKv) implements RequestPayload {}

  public record ArkObjectKv(String key, String value) implements RequestPayload {}

  public record Markdown(
      Integer templateId,
      String customTemplateId,
      String content,
      List<Parameter> params,
      Boolean forceVerifyImageResource)
      implements RequestPayload {
    public Markdown {
      if (templateId != null && customTemplateId != null)
        throw new IllegalArgumentException("Choose one template ID");
      params = params == null ? null : List.copyOf(params);
    }

    public static Markdown content(String content) {
      return new Markdown(null, null, Objects.requireNonNull(content), null, null);
    }
  }

  public record Parameter(String key, List<String> values) implements RequestPayload {
    public Parameter {
      values = List.copyOf(values);
    }
  }

  public record Media(String fileInfo) implements RequestPayload {
    public Media {
      if (fileInfo == null || fileInfo.isBlank())
        throw new IllegalArgumentException("Media file_info required");
    }
  }

  public record Reference(String messageId, Boolean ignoreGetMessageError)
      implements RequestPayload {
    public Reference {
      if (messageId == null || messageId.isBlank())
        throw new IllegalArgumentException("Reference index or message ID required");
    }

    public static Reference index(MessageIndex index) {
      return new Reference(index.value(), null);
    }
  }

  public record Keyboard(String id, KeyboardContent content) implements RequestPayload {
    public Keyboard {
      if (id != null && content != null)
        throw new IllegalArgumentException("Choose keyboard template or content");
    }
  }

  public record KeyboardContent(List<Row> rows) implements RequestPayload {
    public KeyboardContent {
      rows = List.copyOf(rows);
    }
  }

  public record Row(List<Button> buttons) implements RequestPayload {
    public Row {
      buttons = List.copyOf(buttons);
    }
  }

  public record Button(String id, RenderData renderData, Action action, String groupId)
      implements RequestPayload {}

  public record RenderData(String label, String visitedLabel, Integer style)
      implements RequestPayload {}

  public record Action(
      Integer type,
      Permission permission,
      String data,
      Integer clickLimit,
      String unsupportTips,
      Boolean enter,
      Boolean reply,
      Integer anchor,
      Modal modal)
      implements RequestPayload {}

  public record Permission(Integer type, List<String> specifyUserIds, List<String> specifyRoleIds)
      implements RequestPayload {}

  public record Modal(String content, String confirmText, String cancelText)
      implements RequestPayload {}
}
