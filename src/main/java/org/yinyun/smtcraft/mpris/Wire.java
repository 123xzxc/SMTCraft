package org.yinyun.smtcraft.mpris;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The value shapes of a D-Bus reply, decoded from a cursor instead of from
 * libdbus directly.
 *
 * <p>The FFI side only knows how to walk a {@code DBusMessageIter}; everything
 * about how a reply becomes a Java collection lives here, which also means it
 * can be tested without a session bus. That distinction is not academic: a
 * missing dict-entry branch turned every {@code Metadata} map into a list of
 * nulls, and from the outside that only looked like "the player reports no
 * title".</p>
 */
public final class Wire {

    /** Iterator end marker. */
    public static final int DONE = 0;
    public static final int T_INT32 = 'i';
    public static final int T_UINT32 = 'u';
    public static final int T_INT64 = 'x';
    public static final int T_UINT64 = 't';
    public static final int T_DOUBLE = 'd';
    public static final int T_BOOLEAN = 'b';
    public static final int T_STRING = 's';
    public static final int T_OBJECT_PATH = 'o';
    public static final int T_SIGNATURE = 'g';
    public static final int T_ARRAY = 'a';
    public static final int T_VARIANT = 'v';
    public static final int T_DICT_ENTRY = 'e';
    public static final int T_STRUCT = 'r';

    private Wire() {}

    /** One position in a message. Implemented over libdbus, faked in tests. */
    public interface Cursor {
        /** Current type code, {@link #DONE} when the container is exhausted. */
        int type();

        /** Steps into an array, struct or dict entry (a fresh cursor). */
        Cursor recurse();

        /** Advances to the next sibling; false when that was the last one. */
        boolean next();

        /** Basic value of a string, object path or signature. */
        String text();

        /** Basic value of any integer type. */
        long integral();

        /** Basic value of a double. */
        double real();

        /** Basic value of a boolean. */
        boolean bool();

        /** {@link #DONE} cursor, used when a reply cannot be walked at all. */
        static Cursor none() {
            return new Cursor() {
                @Override public int type() { return DONE; }
                @Override public Cursor recurse() { return this; }
                @Override public boolean next() { return false; }
                @Override public String text() { return ""; }
                @Override public long integral() { return 0L; }
                @Override public double real() { return 0.0d; }
                @Override public boolean bool() { return false; }
            };
        }
    }

    /** Decodes the value at {@code cursor}, unwrapping variants and dict entries. */
    public static Object value(Cursor cursor) {
        switch (cursor.type()) {
            case T_VARIANT:
                return value(cursor.recurse());
            case T_STRING:
            case T_OBJECT_PATH:
            case T_SIGNATURE:
                return cursor.text();
            case T_INT64:
            case T_UINT64:
            case T_INT32:
            case T_UINT32:
                return cursor.integral();
            case T_DOUBLE:
                return cursor.real();
            case T_BOOLEAN:
                return cursor.bool();
            case T_ARRAY:
                return array(cursor.recurse());
            case T_STRUCT:
                return items(cursor.recurse());
            case T_DICT_ENTRY:
                return entry(cursor);
            default:
                return null;
        }
    }

    /**
     * Arrays of dict entries become a map, anything else a list. {@code a{sv}}
     * nested inside a variant is exactly this shape, and it is the one the
     * Metadata property arrives in.
     */
    private static Object array(Cursor items) {
        Map<String, Object> dict = null;
        List<Object> list = new ArrayList<>();
        while (items.type() != DONE) {
            if (items.type() == T_DICT_ENTRY) {
                if (dict == null) dict = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : entry(items).entrySet()) {
                    // two non-string keys of the same type share one synthesized
                    // name, and LinkedHashMap.put dropped the earlier payload in
                    // silence. The ordinal belongs to the dict, not to the entry:
                    // numbering inside entry() restarted at zero for every element
                    // and collided again (R7-4)
                    String key = e.getKey();
                    String unique = key;
                    for (int n = dict.size(); dict.containsKey(unique); n++) {
                        unique = key + "#" + n;
                    }
                    dict.put(unique, e.getValue());
                }
            } else {
                list.add(value(items));
            }
            if (!items.next()) break;
        }
        return dict != null ? dict : list;
    }

    private static List<Object> items(Cursor cursor) {
        List<Object> out = new ArrayList<>();
        while (cursor.type() != DONE) {
            out.add(value(cursor));
            if (!cursor.next()) break;
        }
        return out;
    }

    private static Map<String, Object> entry(Cursor cursor) {
        Map<String, Object> out = new LinkedHashMap<>(2);
        Cursor inner = cursor.recurse();
        if (inner.type() == T_STRING) {
            String key = inner.text();
            if (inner.next()) out.put(key, value(inner));
        } else {
            // non-string keys are legal D-Bus but never used by MPRIS. The type
            // has to be read before next(), which moves the cursor onto the
            // value, or the key ends up naming the value's type instead (R6-5)
            // the type has to be read before next(), which moves the cursor onto the
            // value; the name is disambiguated when the dict merges the entries
            int keyType = inner.type();
            if (inner.next()) out.put("#" + keyType, value(inner));
        }
        return out;
    }

    /** {@code a{sv}} reply, as {@code Properties.GetAll} returns it. */
    public static Map<String, Object> properties(Cursor message) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object decoded = value(message);
        if (decoded instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    /** Array of strings, as {@code ListNames} returns it. */
    public static List<String> strings(Cursor message) {
        List<String> out = new ArrayList<>();
        Object decoded = value(message);
        if (decoded instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof CharSequence text) out.add(text.toString());
            }
        }
        return out;
    }

    /** A single property, as {@code Properties.Get} returns it (one variant). */
    public static Object single(Cursor message) {
        return value(message);
    }
}
