package cn.lypi.contracts.session;

import cn.lypi.contracts.model.ModelSelection;
import java.time.Instant;

public record ModelChangeEntry(
    String id,
    String parentId,
    ModelSelection model,
    String reason,
    Instant timestamp
) implements SessionEntry {}

