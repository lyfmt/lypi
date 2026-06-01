package cn.lypi.contracts.agent;

import java.time.Duration;

public record RetryPolicy(
    int maxAttempts,
    Duration initialDelay,
    double backoffMultiplier
) {}

