package cn.lypi.resource;

record PrioritizedResource<T>(
    T value,
    int priority,
    ResourceLocation location
) {}
