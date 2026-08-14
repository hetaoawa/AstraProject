package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/**
 * Minimal JDK HTTP callback server. Put it behind an HTTPS reverse proxy in production;
 * QQ requires the configured callback endpoint to be HTTPS.
 */
public final class WebhookServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final QQBot bot;
    private final BotConfig config;
    private final AstraLogger logger;
    private HttpServer server;

    WebhookServer(QQBot bot, BotConfig config, AstraLogger logger) {
        this.bot = bot;
        this.config = config;
        this.logger = logger;
    }

    public synchronized WebhookServer start() throws IOException {
        if (server != null) {
            return this;
        }
        server = HttpServer.create(new InetSocketAddress(config.webhookHost(), config.webhookPort()), 0);
        server.createContext(config.webhookPath(), this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "qqbot-webhook");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        logger.info("WEBHOOK", "listening address=" + config.webhookHost() + ":"
                + config.webhookPort() + config.webhookPath());
        return this;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        try (exchange) {
            logger.debug("WEBHOOK", "request method=" + exchange.getRequestMethod()
                    + " path=" + exchange.getRequestURI().getPath());
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                write(exchange, 405, MAPPER.createObjectNode().put("message", "Method Not Allowed"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                write(exchange, 413, MAPPER.createObjectNode().put("message", "Payload Too Large"));
                return;
            }
            JsonNode payload = MAPPER.readTree(body);
            if (payload == null || !payload.isObject()) {
                write(exchange, 400, MAPPER.createObjectNode().put("message", "Invalid JSON payload"));
                return;
            }
            int op = payload.path("op").asInt(-1);
            if (!isSignatureValid(exchange, body, op == 13)) {
                logger.warn("WEBHOOK", "signature rejected op=" + op);
                write(exchange, 401, MAPPER.createObjectNode().put("message", "Invalid callback signature"));
                return;
            }
            if (op == 13) {
                logger.info("WEBHOOK", "validation request accepted");
                write(exchange, 200, validationResponse(payload.path("d")));
            } else {
                QQEvent event = new QQEvent(
                        payload.path("id").asText(null),
                        op,
                        payload.has("s") && !payload.get("s").isNull() ? payload.get("s").asLong() : null,
                        payload.path("t").asText(null),
                        payload.path("d"),
                        payload
                );
                ObjectNode ack = MAPPER.createObjectNode().put("op", 12);
                write(exchange, 200, ack);
                logger.debug("WEBHOOK", "event acknowledged type=" + event.type() + " id=" + event.id()
                        + " elapsedMs=" + elapsedMillis(started));
                bot.dispatch(event);
            }
        } catch (Exception error) {
            bot.reportError("WEBHOOK", error);
            if (exchange.getResponseBody() != null) {
                try {
                    write(exchange, 500, MAPPER.createObjectNode().put("message", "Internal Server Error"));
                } catch (Exception ignored) {
                    // The response may already be committed.
                }
            }
        }
    }

    private ObjectNode validationResponse(JsonNode data) {
        String plainToken = data.path("plain_token").asText();
        String eventTs = data.path("event_ts").asText();
        if (plainToken.isBlank() || eventTs.isBlank()) {
            throw new IllegalArgumentException("Webhook validation payload is missing plain_token or event_ts");
        }
        return MAPPER.createObjectNode()
                .put("plain_token", plainToken)
                .put("signature", signValidation(config.clientSecret(), eventTs, plainToken));
    }

    private boolean isSignatureValid(HttpExchange exchange, byte[] body, boolean allowUnsignedValidation) {
        String signature = exchange.getRequestHeaders().getFirst("X-Signature-Ed25519");
        String timestamp = exchange.getRequestHeaders().getFirst("X-Signature-Timestamp");
        if ((signature == null || timestamp == null) && allowUnsignedValidation) {
            return true;
        }
        if (signature == null || timestamp == null || signature.length() != 128) {
            return false;
        }
        try {
            byte[] signatureBytes = HexFormat.of().parseHex(signature);
            return verifyPayload(config.clientSecret(), timestamp, body, signatureBytes);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static String signValidation(String secret, String eventTs, String plainToken) {
        return signPayload(secret, eventTs, plainToken.getBytes(StandardCharsets.UTF_8));
    }

    static String signPayload(String secret, String timestamp, byte[] body) {
        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(seed(secret), 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        byte[] message = concat(timestamp.getBytes(StandardCharsets.UTF_8), body);
        signer.update(message, 0, message.length);
        return HexFormat.of().formatHex(signer.generateSignature());
    }

    static boolean verifyPayload(String secret, String timestamp, byte[] body, byte[] signature) {
        if (signature == null || signature.length != 64) {
            return false;
        }
        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(seed(secret), 0);
        Ed25519PublicKeyParameters publicKey = privateKey.generatePublicKey();
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);
        byte[] message = concat(timestamp.getBytes(StandardCharsets.UTF_8), body);
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }

    private static byte[] seed(String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = secretBytes[i % secretBytes.length];
        }
        return seed;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void write(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
            logger.info("WEBHOOK", "stopped");
        }
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
