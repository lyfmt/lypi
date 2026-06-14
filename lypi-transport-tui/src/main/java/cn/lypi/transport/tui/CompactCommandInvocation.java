package cn.lypi.transport.tui;

import cn.lypi.contracts.runtime.CompactionRequest;
import cn.lypi.contracts.runtime.CompactionRuntimePort;

record CompactCommandInvocation(
    CompactionRuntimePort runtime,
    CompactionRequest request
) {}
