package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WebUrlPolicyTest {
    @Test
    void allowsPublicHttpAndHttpsUrls() {
        WebUrlPolicy.CheckedUrl https = WebUrlPolicy.check("https://Docs.Example.com/a?q=1");
        WebUrlPolicy.CheckedUrl http = WebUrlPolicy.check("http://example.com");

        assertEquals("docs.example.com", https.host());
        assertEquals("https://Docs.Example.com/a?q=1", https.uri().toString());
        assertEquals("example.com", http.host());
    }

    @Test
    void rejectsUnsupportedSchemesAndCredentials() {
        assertRejected("file:///tmp/a", "scheme");
        assertRejected("ftp://example.com/a", "scheme");
        assertRejected("https://user:pass@example.com/a", "credential");
        assertRejected("https:///missing-host", "host");
    }

    @Test
    void rejectsLoopbackPrivateAndLinkLocalHosts() {
        assertRejected("https://localhost/a", "local");
        assertRejected("https://127.0.0.1/a", "local");
        assertRejected("https://[::1]/a", "local");
        assertRejected("https://10.0.0.1/a", "private");
        assertRejected("https://172.16.0.1/a", "private");
        assertRejected("https://192.168.0.1/a", "private");
        assertRejected("https://169.254.1.1/a", "link-local");
        assertRejected("https://[fe80::1]/a", "link-local");
    }

    private void assertRejected(String url, String expectedMessage) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> WebUrlPolicy.check(url)
        );
        assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
    }
}
