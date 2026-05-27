package com.matchmaking.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

public class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static String write(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(InputStream is) {
        try {
            if (is == null) return Collections.emptyMap();
            return MAPPER.readValue(is, Map.class);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }
}
