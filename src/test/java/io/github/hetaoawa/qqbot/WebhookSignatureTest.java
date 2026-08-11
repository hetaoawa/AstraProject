package io.github.hetaoawa.qqbot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureTest {
    @Test
    void producesDeterministicEd25519HexSignature() throws Exception {
        String first = WebhookServer.signValidation("DG5g3B4j9X2KOErG", "1725442341", "Arq0D5A61EgUu4OxUvOp");
        String second = WebhookServer.signValidation("DG5g3B4j9X2KOErG", "1725442341", "Arq0D5A61EgUu4OxUvOp");
        assertEquals(first, second);
        assertEquals(128, first.length());
        assertTrue(first.matches("[0-9a-f]+"));
        assertNotEquals(first, WebhookServer.signValidation("another-secret", "1725442341", "Arq0D5A61EgUu4OxUvOp"));
    }

    @Test
    void signsAndVerifiesOfficialPayloadShape() {
        String secret = "naOC0ocQE3shWLAfffVLB1rhYPG7";
        String body = "{ \"op\": 0,\"d\": {}, \"t\": \"GATEWAY_EVENT_NAME\"}";
        String signature = WebhookServer.signPayload(secret, "1725442341", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(128, signature.length());
        assertTrue(WebhookServer.verifyPayload(secret, "1725442341", body.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.util.HexFormat.of().parseHex(signature)));
        assertFalse(WebhookServer.verifyPayload("another-secret", "1725442341", body.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.util.HexFormat.of().parseHex(signature)));
    }
}
