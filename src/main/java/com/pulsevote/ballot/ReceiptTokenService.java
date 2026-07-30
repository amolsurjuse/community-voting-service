package com.pulsevote.ballot;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReceiptTokenService {
    private final byte[] key;

    public ReceiptTokenService(@Value("${voting.receipt-token-key}") String encodedKey) {
        this.key = Base64.getDecoder().decode(encodedKey);
        if (key.length < 32) throw new IllegalArgumentException("Receipt token key must be at least 256 bits");
    }

    public String tokenFor(UUID receiptId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(receiptId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Receipt token generation failed", e);
        }
    }
}

