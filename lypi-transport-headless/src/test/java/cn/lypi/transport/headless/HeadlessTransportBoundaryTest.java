package cn.lypi.transport.headless;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.transport.TransportAdapter;
import org.junit.jupiter.api.Test;

public final class HeadlessTransportBoundaryTest {
    @Test
    void headlessTransportExtendsTransportAdapter() {
        assertTrue(TransportAdapter.class.isAssignableFrom(HeadlessTransport.class));
        Class<? extends TransportAdapter> headlessTransportType = HeadlessTransport.class;
        assertSame(HeadlessTransport.class, headlessTransportType);
    }

    public HeadlessTransportBoundaryTest() {
    }
}
