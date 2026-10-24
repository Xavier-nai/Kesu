package com.xavier.kesu;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The theme metadata formats read by the reference app: localized JSON or XML groups. */
final class ThemeMetadata {
    final String title;
    final String designer;

    private ThemeMetadata(String title, String designer) {
        this.title = title;
        this.designer = designer;
    }

    static ThemeMetadata parse(String text) {
        return new ThemeMetadata(value(text, "titles"), value(text, "designers"));
    }

    private static String value(String text, String group) {
        if (text == null || text.trim().isEmpty()) return "";
        Matcher object = Pattern.compile("\"" + group + "\"\\s*:\\s*\\{").matcher(text);
        if (object.find()) {
            try {
                Object parsed = new JSONTokener(text.substring(object.end() - 1)).nextValue();
                if (parsed instanceof JSONObject) {
                    JSONObject json = (JSONObject) parsed;
                    Map<String, String> values = new LinkedHashMap<>();
                    Iterator<String> keys = json.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object item = json.opt(key);
                        if (item instanceof String) values.put(key, clean((String) item));
                    }
                    return pick(values);
                }
            } catch (org.json.JSONException ignored) {
                // An unreadable JSON group may still have an XML equivalent.
            }
        }
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(text));
            Map<String, String> values = new LinkedHashMap<>();
            int depth = -1;
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && parser.getName().equals(group)) depth = parser.getDepth();
                else if (depth > 0 && event == XmlPullParser.START_TAG && parser.getDepth() == depth + 1) {
                    String language = parser.getAttributeValue(null, "locale");
                    if (language == null) language = parser.getAttributeValue(null, "language");
                    if (language == null) language = parser.getName();
                    values.put(language, clean(parser.nextText()));
                } else if (depth > 0 && event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
            }
            return pick(values);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String pick(Map<String, String> values) {
        for (String key : new String[]{"zh_CN", "fallback"}) {
            String value = values.get(key);
            if (value != null && !value.isEmpty()) return value;
        }
        for (String value : values.values()) if (!value.isEmpty()) return value;
        return "";
    }

    private static String clean(String text) {
        String value = text.trim();
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) value = value.substring(9, value.length() - 3);
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("\\/", "/").trim();
    }
}
