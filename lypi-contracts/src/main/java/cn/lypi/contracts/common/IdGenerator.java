package cn.lypi.contracts.common;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

public final class IdGenerator {
    private static final char[] CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final RandomGenerator random;

    private IdGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public static IdGenerator random() {
        return new IdGenerator(new SecureRandom());
    }

    public String sessionId() {
        return id("ses_");
    }

    public String entryId() {
        return id("entry_");
    }

    public String turnId() {
        return id("turn_");
    }

    public String messageId() {
        return id("msg_");
    }

    public String toolUseId() {
        return id("toolu_");
    }

    public String eventId() {
        return id("evt_");
    }

    public String auditId() {
        return id("aud_");
    }

    public String errorId() {
        return id("err_");
    }

    private String id(String prefix) {
        return prefix + timestampPart() + randomPart();
    }

    private String timestampPart() {
        long value = System.currentTimeMillis();
        char[] output = new char[10];
        for (int i = output.length - 1; i >= 0; i--) {
            output[i] = CROCKFORD_BASE32[(int) (value & 31)];
            value >>>= 5;
        }
        return new String(output);
    }

    private String randomPart() {
        char[] output = new char[16];
        for (int i = 0; i < output.length; i++) {
            output[i] = CROCKFORD_BASE32[random.nextInt(CROCKFORD_BASE32.length)];
        }
        return new String(output);
    }
}
