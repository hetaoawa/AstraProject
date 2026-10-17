package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

class GatewayAcceptanceTest {
  @TempDir Path temp;

  @Test
  void websocketAuthenticatesHeartbeatsAndResumesOnlyAcceptedSequence() throws Exception {
    try (var sockets = new ServerSocket(0);
        var db = new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("gateway.db"), "", "");
        var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.createContext(
          "/",
          e -> {
            String body =
                e.getRequestURI().getPath().equals("/app/getAppAccessToken")
                    ? "{\"access_token\":\"token\",\"expires_in\":7200}"
                    : QqJson.write(
                        Map.of(
                            "url",
                            "ws://127.0.0.1:" + sockets.getLocalPort(),
                            "session_start_limit",
                            Map.of("remaining", 20, "reset_after", 100000)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      http.start();
      var observed = new CopyOnWriteArrayList<Event>();
      var bot = new BotId("ws", "production");
      var position = new JdbcGatewayStore(db);
      var finished = new CountDownLatch(1);
      Future<?> peer =
          worker.submit(
              () -> {
                try {
                  try (Socket first = sockets.accept()) {
                    handshake(first);
                    send(first, Map.of("op", 10, "d", Map.of("heartbeat_interval", 1000)));
                    var identify = read(first);
                    assertEquals(2, QqJson.number(identify.get("op"), -1));
                    assertEquals("QQBot token", QqJson.object(identify.get("d")).get("token"));
                    send(
                        first,
                        Map.of(
                            "op",
                            0,
                            "s",
                            1,
                            "t",
                            "READY",
                            "d",
                            Map.of("session_id", "session", "user", Map.of("id", "bot"))));
                    send(
                        first,
                        Map.of(
                            "op",
                            0,
                            "s",
                            2,
                            "t",
                            "GROUP_AT_MESSAGE_CREATE",
                            "d",
                            Map.of(
                                "id",
                                "rejected",
                                "group_openid",
                                "group",
                                "author",
                                Map.of("member_openid", "user"))));
                    Thread.sleep(100);
                  }
                  try (Socket resumed = sockets.accept()) {
                    handshake(resumed);
                    send(resumed, Map.of("op", 10, "d", Map.of("heartbeat_interval", 1000)));
                    var resume = read(resumed);
                    assertEquals(6, QqJson.number(resume.get("op"), -1));
                    assertEquals("session", QqJson.object(resume.get("d")).get("session_id"));
                    assertEquals(1, QqJson.number(QqJson.object(resume.get("d")).get("seq"), -1));
                    send(resumed, Map.of("op", 0, "s", 3, "t", "RESUMED", "d", Map.of()));
                    var beat = read(resumed);
                    assertEquals(1, QqJson.number(beat.get("op"), -1));
                    assertEquals(3, QqJson.number(beat.get("d"), -1));
                    send(resumed, Map.of("op", 11));
                    finished.countDown();
                  }
                } catch (Throwable e) {
                  throw new CompletionException(e);
                }
              });
      URI base = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
      var credentials = new BotCredentials(bot, "secret", base, Duration.ofSeconds(3));
      try (var gateway =
          new QqGateway(
              credentials,
              new DirectQqApi(credentials, new RouteCatalog()),
              position,
              new QqEventCodec(),
              event -> {
                observed.add(event);
                return !(event instanceof MessageEvent);
              },
              33554435,
              1,
              true)) {
        assertTrue(finished.await(15, TimeUnit.SECONDS));
        peer.get(1, TimeUnit.SECONDS);
        assertEquals(3, position.load(bot, 0).sequence());
        assertEquals("session", position.load(bot, 0).session());
        assertTrue(observed.stream().anyMatch(MessageEvent.class::isInstance));
      } finally {
        http.stop(0);
      }
    }
  }

  private static void handshake(Socket socket) throws Exception {
    socket.setSoTimeout(12000);
    var input = socket.getInputStream();
    var bytes = new ByteArrayOutputStream();
    int b;
    while ((b = input.read()) >= 0) {
      bytes.write(b);
      String text = bytes.toString(StandardCharsets.US_ASCII);
      if (text.endsWith("\r\n\r\n")) break;
      if (bytes.size() > 8192) throw new IOException("Header too large");
    }
    String key =
        Arrays.stream(bytes.toString(StandardCharsets.US_ASCII).split("\r\n"))
            .filter(s -> s.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
            .findFirst()
            .orElseThrow()
            .split(":", 2)[1]
            .strip();
    String accept =
        Base64.getEncoder()
            .encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                            .getBytes(StandardCharsets.US_ASCII)));
    socket
        .getOutputStream()
        .write(
            ("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: "
                    + accept
                    + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  private static void send(Socket socket, Map<String, Object> payload) throws IOException {
    byte[] data = QqJson.write(payload).getBytes(StandardCharsets.UTF_8);
    var out = socket.getOutputStream();
    out.write(0x81);
    if (data.length < 126) out.write(data.length);
    else {
      out.write(126);
      out.write(data.length >>> 8);
      out.write(data.length & 255);
    }
    out.write(data);
    out.flush();
  }

  private static Map<String, Object> read(Socket socket) throws IOException {
    var in = socket.getInputStream();
    int op = in.read(), flag = in.read();
    if (op < 0 || flag < 0) throw new EOFException();
    int length = flag & 127;
    if (length == 126) length = (in.read() << 8) | in.read();
    if (length > 65535) throw new IOException("Oversized test frame");
    byte[] mask = (flag & 128) != 0 ? in.readNBytes(4) : null;
    byte[] bytes = in.readNBytes(length);
    if (bytes.length != length) throw new EOFException();
    if (mask != null) for (int i = 0; i < length; i++) bytes[i] ^= mask[i % 4];
    return QqJson.map(new String(bytes, StandardCharsets.UTF_8));
  }
}
