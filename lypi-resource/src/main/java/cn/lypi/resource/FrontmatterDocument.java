package cn.lypi.resource;

import java.util.Map;

record FrontmatterDocument(
    Map<String, Object> metadata,
    String body
) {}
