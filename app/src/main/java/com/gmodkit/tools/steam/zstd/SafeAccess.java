/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gmodkit.tools.steam.zstd;

/**
 * Android-safe, pure-Java replacement for the original {@code UnsafeUtil}.
 *
 * <p>Memory is always a {@code byte[]} in this decoder. Addresses are plain array
 * indices because {@link Util#ARRAY_BYTE_BASE_OFFSET} is defined as {@code 0}, so the
 * {@code (int) index} truncation below is exact for all valid inputs.
 *
 * <p>Multi-byte accesses are little-endian and unaligned, matching the ZSTD wire format.
 * The upstream decoder reached them through {@code VarHandle} byte-array views, which are
 * Android API 33 and would drop every older device, so they are assembled by hand here.
 */
final class SafeAccess {
    private SafeAccess() {
    }

    static byte getByte(Object base, long index) {
        return ((byte[]) base)[(int) index];
    }

    static short getShort(Object base, long index) {
        byte[] arr = (byte[]) base;
        int i = (int) index;
        return (short) ((arr[i] & 0xFF) | (arr[i + 1] & 0xFF) << 8);
    }

    static int getInt(Object base, long index) {
        byte[] arr = (byte[]) base;
        int i = (int) index;
        return (arr[i] & 0xFF)
            | (arr[i + 1] & 0xFF) << 8
            | (arr[i + 2] & 0xFF) << 16
            | (arr[i + 3] & 0xFF) << 24;
    }

    static long getLong(Object base, long index) {
        return (getInt(base, index + 4) & 0xFFFFFFFFL) << 32 | getInt(base, index) & 0xFFFFFFFFL;
    }

    static void putByte(Object base, long index, byte value) {
        ((byte[]) base)[(int) index] = value;
    }

    static void putShort(Object base, long index, short value) {
        byte[] arr = (byte[]) base;
        int i = (int) index;
        arr[i] = (byte) value;
        arr[i + 1] = (byte) (value >>> 8);
    }

    static void putInt(Object base, long index, int value) {
        byte[] arr = (byte[]) base;
        int i = (int) index;
        arr[i] = (byte) value;
        arr[i + 1] = (byte) (value >>> 8);
        arr[i + 2] = (byte) (value >>> 16);
        arr[i + 3] = (byte) (value >>> 24);
    }

    static void putLong(Object base, long index, long value) {
        putInt(base, index, (int) value);
        putInt(base, index + 4, (int) (value >>> 32));
    }

    static void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long size) {
        // srcBase/destBase are always byte[] at runtime (the whole API threads the
        // buffer as Object). arraycopy takes Object, so no cast is needed; the int
        // casts on the offsets/size are required.
        //noinspection SuspiciousSystemArraycopy
        System.arraycopy(srcBase, (int) srcOffset, destBase, (int) destOffset, (int) size);
    }
}
