package cn.lycode.transport.tui;

import cn.lycode.contracts.runtime.CompactionRequest;
import cn.lycode.contracts.runtime.CompactionRuntimePort;

record CompactCommandInvocation(
    CompactionRuntimePort runtime,
    CompactionRequest request
) {}
