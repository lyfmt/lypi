package cn.lypi.resource;

import java.nio.file.Path;

record ResourceLocation(
    ResourceLayer layer,
    Path root,
    int priority,
    String description
) {}
