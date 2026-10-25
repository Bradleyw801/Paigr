package com.textrelay.relay;

import org.json.JSONException;
import org.json.JSONObject;

/** org.json declares checked exceptions on put(); they can't happen with these literal keys. */
final class Json {
    private Json() {}

    static JSONObject obj(Object... keyValues) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                o.put((String) keyValues[i], keyValues[i + 1] == null ? JSONObject.NULL : keyValues[i + 1]);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }
}
