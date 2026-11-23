package org.yinyun.smtcraft.mpris;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * The libdbus entry points MPRIS reading needs, each with two independent
 * declarations: the {@link FunctionDescriptor} handed to the linker, and the
 * prototype from the C header written as Java types.
 *
 * <p>They are kept apart on purpose. {@code handle.type()} is whatever the
 * descriptor asked for, so comparing a handle against its own descriptor proves
 * only that one line agrees with itself — a {@code dbus_bool_t} declared as
 * {@code void} passes that check and then throws on the first real call (AUDIT-22-3).
 * The table below is checked when this class is initialised, which happens on any
 * platform: the older arrangement put the check after a successful
 * {@code libraryLookup}, so on a machine without libdbus it never ran at all
 * (AUDIT-23-3).</p>
 */
public final class DbusSymbols {

    /** One entry point: the descriptor, plus the header prototype in Java types. */
    public record Sym(String name, FunctionDescriptor descriptor, Class<?> returns,
                      Class<?>... params) {
        /** The handle type the header asks for, built from the declared Java types. */
        public MethodType type() {
            return MethodType.methodType(returns, params);
        }
    }

    private static final Class<?> PTR = MemorySegment.class;
    private static final Class<?> INT = int.class;

    private static final List<Sym> ALL = List.of(
            new Sym("dbus_bus_get_private",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    PTR, INT, PTR),
            new Sym("dbus_connection_set_exit_on_disconnect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    void.class, PTR, INT),
            new Sym("dbus_connection_close",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), void.class, PTR),
            new Sym("dbus_connection_unref",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), void.class, PTR),
            new Sym("dbus_error_init",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), void.class, PTR),
            new Sym("dbus_error_is_set",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    INT, PTR),
            new Sym("dbus_error_free",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), void.class, PTR),
            new Sym("dbus_message_new_method_call",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    PTR, PTR, PTR, PTR, PTR),
            new Sym("dbus_connection_send_with_reply_and_block",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    PTR, PTR, PTR, INT, PTR),
            new Sym("dbus_message_unref",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), void.class, PTR),
            new Sym("dbus_message_iter_init",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    INT, PTR, PTR),
            new Sym("dbus_message_iter_init_append",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    void.class, PTR, PTR),
            new Sym("dbus_message_iter_append_basic",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS),
                    INT, PTR, INT, PTR),
            new Sym("dbus_message_iter_get_arg_type",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), INT, PTR),
            new Sym("dbus_message_iter_get_basic",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    void.class, PTR, PTR),
            new Sym("dbus_message_iter_next",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), INT, PTR),
            new Sym("dbus_message_iter_recurse",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    void.class, PTR, PTR));

    static {
        checkShapes();
    }

    private DbusSymbols() {
    }

    /** Every symbol, in header order. */
    public static List<Sym> all() {
        return ALL;
    }

    /**
     * The entry with that name.
     *
     * @throws IllegalArgumentException when the name is not in the table
     */
    public static Sym get(String name) {
        for (Sym sym : ALL) {
            if (sym.name().equals(name)) return sym;
        }
        throw new IllegalArgumentException("no libdbus symbol called " + name);
    }

    /**
     * Compares every descriptor with the prototype declared next to it.
     *
     * <p>Runs unconditionally at class initialisation, so a wrong
     * {@code ofVoid}/{@code of} pair fails on any platform instead of waiting for a
     * Linux load (AUDIT-23-3). It cannot prove the header itself is right — that is what
     * {@code src/test/resources/libdbus-prototypes.txt} is for.</p>
     */
    public static void checkShapes() {
        for (Sym sym : ALL) {
            // ofVoid() carries no return layout at all, which is the one case where
            // "absent" and "void" mean the same thing.
            Class<?> returns = sym.descriptor().returnLayout()
                    .<Class<?>>map(layout -> carrier(layout, sym.name()))
                    .orElse(void.class);
            MethodType fromDescriptor = MethodType.methodType(returns,
                    sym.descriptor().argumentLayouts().stream()
                            .map(layout -> carrier(layout, sym.name()))
                            .toArray(Class<?>[]::new));
            if (!fromDescriptor.equals(sym.type())) {
                throw new IllegalStateException(sym.name() + " is declared " + sym.type()
                        + " but its descriptor says " + fromDescriptor);
            }
        }
    }

    /** The Java carrier a layout stands for, i.e. what the downcall handle will use. */
    public static Class<?> carrier(MemoryLayout layout, String where) {
        if (layout instanceof AddressLayout) return MemorySegment.class;
        if (layout.equals(ValueLayout.JAVA_INT)) return int.class;
        if (layout.equals(ValueLayout.JAVA_LONG)) return long.class;
        if (layout.equals(ValueLayout.JAVA_DOUBLE)) return double.class;
        throw new IllegalStateException(where + ": no carrier known for " + layout);
    }
}
