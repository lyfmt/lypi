package cn.lycode.contracts.session;

import cn.lycode.contracts.model.ModelSelection;
import java.time.Instant;

public record ModelChangeEntry(
    String id,
    String parentId,
    ModelSelection model,
    String reason,
    Instant timestamp
) implements SessionEntry {}

