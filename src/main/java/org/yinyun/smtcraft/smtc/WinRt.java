package org.yinyun.smtcraft.smtc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WinRT / COM plumbing through the JDK 25 Foreign Function &amp; Memory API.
 *
 * <p>No JNA, no JNI, no helper DLL, no PowerShell: only {@code combase.dll} and
 * {@code shcore.dll} exports plus vtable dispatch on the interface pointers
 * Windows itself returns. Minecraft 26.3 runs on Java 25, so the Panama linker
 * is simply available.</p>
 */
public final class WinRt {
    private WinRt() {}

    public static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    public static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;

    private static final Linker LINKER = Linker.nativeLinker();

    private static final FunctionDescriptor D0 = FunctionDescriptor.of(I32, PTR);
    private static final FunctionDescriptor D1 = FunctionDescriptor.of(I32, PTR, PTR);
    private static final FunctionDescriptor D2 = FunctionDescriptor.of(I32, PTR, PTR, PTR);
    /** IStream::Read(void* self, void* buffer, uint32 len, uint32* read) */
    private static final FunctionDescriptor D_READ = FunctionDescriptor.of(I32, PTR, PTR, I32, PTR);

    private static final ConcurrentHashMap<Long, MethodHandle> H0 = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, MethodHandle> H1 = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, MethodHandle> H2 = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, MethodHandle> HR = new ConcurrentHashMap<>();

    private static MethodHandle cached(ConcurrentHashMap<Long, MethodHandle> cache, long address,
                                      FunctionDescriptor descriptor) {
        MethodHandle mh = cache.get(address);
        if (mh != null) return mh;
        try {
            mh = LINKER.downcallHandle(MemorySegment.ofAddress(address), descriptor);
        } catch (Throwable t) {
            throw new IllegalStateException("downcallHandle 0x" + Long.toHexString(address), t);
        }
        cache.put(address, mh);
        return mh;
    }

    private static SymbolLookup lookup(String dll) {
        try {
            return SymbolLookup.libraryLookup(dll, Arena.global());
        } catch (Throwable t) {
            return null;
        }
    }

    private static MethodHandle export(String dll, String name, FunctionDescriptor descriptor) {
        SymbolLookup lib = lookup(dll);
        if (lib == null) return null;
        return lib.find(name).map(segment -> {
            try {
                return LINKER.downcallHandle(segment, descriptor);
            } catch (Throwable t) {
                return null;
            }
        }).orElse(null);
    }

    private static final MethodHandle RO_INITIALIZE =
            export("combase.dll", "RoInitialize", FunctionDescriptor.of(I32, I32));
    private static final MethodHandle RO_GET_FACTORY =
            export("combase.dll", "RoGetActivationFactory", FunctionDescriptor.of(I32, PTR, PTR, PTR));
    private static final MethodHandle CREATE_HSTRING =
            export("combase.dll", "WindowsCreateString", FunctionDescriptor.of(I32, PTR, I32, PTR));
    private static final MethodHandle DELETE_HSTRING =
            export("combase.dll", "WindowsDeleteString", FunctionDescriptor.of(I32, PTR));
    private static final MethodHandle HSTRING_RAW =
            export("combase.dll", "WindowsGetStringRawBuffer", FunctionDescriptor.of(PTR, PTR, PTR));
    private static final MethodHandle CREATE_STREAM_OVER_RAS =
            export("shcore.dll", "CreateStreamOverRandomAccessStream", FunctionDescriptor.of(I32, PTR, PTR, PTR));

    public static boolean available() {
        return RO_INITIALIZE != null && RO_GET_FACTORY != null && CREATE_HSTRING != null
                && DELETE_HSTRING != null && HSTRING_RAW != null;
    }

    public static boolean hasStreamInterop() {
        return CREATE_STREAM_OVER_RAS != null;
    }

    // --- apartment ---------------------------------------------------------

    private static final ThreadLocal<Boolean> APARTMENT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Join the multi-threaded apartment once per calling thread. */
    public static void joinMta() {
        if (Boolean.TRUE.equals(APARTMENT.get())) return;
        if (RO_INITIALIZE == null) throw new IllegalStateException("combase.dll 不可用");
        try {
            int hr = (int) RO_INITIALIZE.invokeExact(1);
            // 0x80010106 = RPC_E_CHANGED_MODE: the thread is already in an apartment, fine for us
            if (hr < 0 && hr != 0x80010106) {
                throw new IllegalStateException("RoInitialize 0x" + Integer.toHexString(hr));
            }
            APARTMENT.set(Boolean.TRUE);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    // --- HSTRING -----------------------------------------------------------

    public static MemorySegment hstring(Arena arena, String value) {
        if (CREATE_HSTRING == null) return MemorySegment.NULL;
        MemorySegment buf = arena.allocate((long) value.length() * 2L + 2L);
        for (int i = 0; i < value.length(); i++) {
            buf.set(ValueLayout.JAVA_CHAR_UNALIGNED, (long) i * 2L, value.charAt(i));
        }
        MemorySegment out = arena.allocate(PTR);
        try {
            int hr = (int) CREATE_HSTRING.invokeExact(buf, (int) value.length(), out);
            return hr >= 0 ? out.get(PTR, 0L) : MemorySegment.NULL;
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    public static void deleteHString(MemorySegment hstr) {
        if (isNull(hstr) || DELETE_HSTRING == null) return;
        try {
            // WindowsDeleteString returns HRESULT: the invokeExact call site must
            // declare the int return type, otherwise the signature-polymorphic
            // call site resolves to (MemorySegment)void and the linker throws
            // WrongMethodTypeException -- which the old catch swallowed, so every
            // HSTRING the reader produced stayed alive for the whole session.
            int hr = (int) DELETE_HSTRING.invokeExact(hstr);
            if (hr < 0) {
                // nothing sensible to do: the string belongs to someone else
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    public static String readString(MemorySegment hstr, Arena scratch) {
        if (isNull(hstr) || HSTRING_RAW == null) return "";
        try {
            MemorySegment lengthOut = scratch.allocate(I32);
            MemorySegment raw = (MemorySegment) HSTRING_RAW.invokeExact(hstr, lengthOut);
            int length = lengthOut.get(I32, 0L);
            if (length <= 0) return "";
            // A length this large means the raw-buffer call answered with
            // garbage; returning a marker string only put it on the HUD as the
            // song title, so treat it as "no string".
            if (length > 4096) return "";
            // the returned pointer is a zero-length segment: give it a real bound before reading
            raw = raw.reinterpret((long) length * 2L + 2L);
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(raw.get(ValueLayout.JAVA_CHAR_UNALIGNED, (long) i * 2L));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Read a WinRT string and free it: callers receive an owned HSTRING out-param. */
    public static String readAndFree(MemorySegment hstr, Arena scratch) {
        String value = readString(hstr, scratch);
        deleteHString(hstr);
        return value;
    }

    // --- activation --------------------------------------------------------

    public static MemorySegment activationFactory(Arena arena, String runtimeClass, Guid iid) {
        if (RO_GET_FACTORY == null) return MemorySegment.NULL;
        MemorySegment classId = hstring(arena, runtimeClass);
        if (isNull(classId)) return MemorySegment.NULL;
        try {
            MemorySegment out = arena.allocate(PTR);
            int hr = (int) RO_GET_FACTORY.invokeExact(classId, iid.segment(arena), out);
            return hr >= 0 ? out.get(PTR, 0L) : MemorySegment.NULL;
        } catch (Throwable t) {
            return MemorySegment.NULL;
        } finally {
            deleteHString(classId);
        }
    }

    // --- vtable dispatch ---------------------------------------------------

    private static long slotAddress(MemorySegment obj, int slot) {
        if (obj == null || obj.address() == 0L) return 0L;
        MemorySegment vtable = obj.reinterpret(Long.MAX_VALUE).get(PTR, 0L);
        if (vtable.address() == 0L) return 0L;
        return vtable.reinterpret(Long.MAX_VALUE).get(PTR, slot * 8L).address();
    }

    /** {@code HRESULT f(self)}. */
    public static int call0(MemorySegment obj, int slot) {
        try {
            return (int) cached(H0, slotAddress(obj, slot), D0).invokeExact(obj);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** {@code HRESULT f(self, void* arg)} -- every getter with one out pointer. */
    public static int call1(MemorySegment obj, int slot, MemorySegment arg) {
        try {
            return (int) cached(H1, slotAddress(obj, slot), D1).invokeExact(obj, arg);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int call2(MemorySegment obj, int slot, MemorySegment first, MemorySegment second) {
        try {
            return (int) cached(H2, slotAddress(obj, slot), D2).invokeExact(obj, first, second);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** IStream::Read(buffer, length, readOut) -- vtable slot 3. */
    public static int streamRead(MemorySegment stream, MemorySegment buffer, int length, MemorySegment readOut) {
        try {
            return (int) cached(HR, slotAddress(stream, 3), D_READ)
                    .invokeExact(stream, buffer, length, readOut);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Wrap a WinRT IRandomAccessStream as a classic IStream so we can just Read() it. */
    public static MemorySegment streamOverRandomAccess(MemorySegment ras, Guid iidStream, Arena arena) {
        if (CREATE_STREAM_OVER_RAS == null) return MemorySegment.NULL;
        try {
            MemorySegment out = arena.allocate(PTR);
            int hr = (int) CREATE_STREAM_OVER_RAS.invokeExact(ras, iidStream.segment(arena), out);
            return hr >= 0 ? out.get(PTR, 0L) : MemorySegment.NULL;
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    public static MemorySegment queryInterface(MemorySegment obj, Guid iid, Arena arena) {
        MemorySegment out = arena.allocate(PTR);
        int hr = call2(obj, 0, iid.segment(arena), out);
        return hr >= 0 ? out.get(PTR, 0L) : MemorySegment.NULL;
    }

    public static void release(MemorySegment obj) {
        if (isNull(obj)) return;
        call0(obj, 2);
    }

    public static boolean isNull(MemorySegment segment) {
        return segment == null || segment.address() == 0L;
    }

    /** 16-byte little-endian GUID. */
    public record Guid(int data1, int data2, int data3, byte[] data4) {
        public static Guid parse(String text) {
            String[] parts = text.split("-");
            byte[] rest = new byte[8];
            String tail = parts[3] + parts[4];
            for (int i = 0; i < 8; i++) {
                rest[i] = (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16);
            }
            return new Guid((int) Long.parseLong(parts[0], 16), Integer.parseInt(parts[1], 16),
                    Integer.parseInt(parts[2], 16), rest);
        }

        public MemorySegment segment(Arena arena) {
            MemorySegment segment = arena.allocate(16);
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, 0L, data1);
            segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4L, (short) data2);
            segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6L, (short) data3);
            MemorySegment.copy(data4, 0, segment, ValueLayout.JAVA_BYTE, 8L, 8);
            return segment;
        }
    }
}
