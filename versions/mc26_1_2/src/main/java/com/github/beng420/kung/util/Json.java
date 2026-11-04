package com.github.beng420.kung.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Null-safe readers for network and file JSON: a missing, null or mistyped member gives the fallback, never an exception. */
public final class Json {
    private Json() { }

    /** The member as an object, or null when {@code parent} is null or the member is missing or not an object. */
    public static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent == null ? null : parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    public static String string(JsonObject object, String name, String fallback) {
        JsonPrimitive value = primitive(object, name);
        return value == null ? fallback : value.getAsString();
    }

    public static double number(JsonObject object, String name, double fallback) {
        JsonPrimitive value = primitive(object, name);
        try {
            return value == null ? fallback : value.getAsDouble();
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static int intValue(JsonObject object, String name, int fallback) {
        JsonPrimitive value = primitive(object, name);
        try {
            return value == null ? fallback : value.getAsInt();
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static long longValue(JsonObject object, String name, long fallback) {
        JsonPrimitive value = primitive(object, name);
        try {
            return value == null ? fallback : value.getAsLong();
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Only a real JSON boolean counts; anything else gives the fallback. */
    public static boolean bool(JsonObject object, String name, boolean fallback) {
        JsonPrimitive value = primitive(object, name);
        return value != null && value.isBoolean() ? value.getAsBoolean() : fallback;
    }

    private static JsonPrimitive primitive(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;
    }
}
