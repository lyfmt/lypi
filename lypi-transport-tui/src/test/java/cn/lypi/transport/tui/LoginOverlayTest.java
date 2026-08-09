package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoginOverlayTest {
    @Test
    void collectsBaseUrlThenMasksAuthKeyAndRedactsSubmissionString() {
        String authKey = "test-secret";
        LoginOverlay overlay = new LoginOverlay();

        overlay.append("https://api.example.test/v1");

        assertTrue(overlay.accept().isEmpty());
        overlay.append(authKey);

        assertEquals(List.of(
            "Base URL: https://api.example.test/v1",
            "Auth key: ***********"
        ), overlay.lines());
        assertFalse(String.join("\n", overlay.lines()).contains(authKey));

        LoginOverlay.Submission submission = overlay.accept().orElseThrow();

        assertEquals("https://api.example.test/v1", submission.baseUrl());
        assertTrue(authKey.equals(submission.authKey()));
        assertFalse(submission.toString().contains(authKey));
    }

    @Test
    void supportsBackspaceAndClearWithoutRetainingMaskedInput() {
        LoginOverlay overlay = new LoginOverlay();
        overlay.append("https://api.example.test/v1");
        overlay.accept();
        overlay.append("secret");
        overlay.backspace();

        assertEquals(List.of(
            "Base URL: https://api.example.test/v1",
            "Auth key: *****"
        ), overlay.lines());

        overlay.clear();

        assertEquals(List.of(), overlay.lines());
        assertTrue(overlay.accept().isEmpty());
    }
}
