package cn.lycode.resource;

record PrioritizedResource<T>(
    T value,
    int priority,
    ResourceLocation location
) {}
