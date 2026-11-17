// port-lint: source shared/util/escape.rs
@file:OptIn(ExperimentalUnsignedTypes::class)

package io.github.kotlinmania.jiffstatic.shared.util.escape

import io.github.kotlinmania.jiffstatic.shared.util.utf8.decode
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout
import io.github.kotlinmania.rustlang.core.ptr.writeBytes
import io.github.kotlinmania.rustlang.core.result.Result

// Provides convenience routines for escaping raw bytes.
//
// This was copied from `regex - automata` with a few light edits.

/**
 * Provides a convenient `Debug` implementation for a `UByte`.
 *
 * The `Debug` impl treats the byte as an ASCII, and emits a human
 * readable representation of it. If the byte isn't ASCII, then it's
 * emitted as a hex escape sequence.
 */
public open class Byte(
    public var value: UByte,
) {
    public var field0: UByte
        get() = value
        set(v) {
            value = v
        }

    public open fun clone(): Byte = Byte(value)

    public open fun fmt(f: StringBuilder) {
        if (this.value == 32u) {
            return f.append(" ")
        }
        // 10 bytes is enough for any output from ascii.escapeDefault.
        val bytes = UByteArray(10) { 0u.toUByte() }
        var len = 0
        for ((i, b) in escapeDefault(this.value).mapIndexed { index, item -> Pair(index, item) }) {
            // capitalize \xab to \xAB
            if (i >= 2 && 97u <= b && b <= 102u) {
                b -= 32
            }
            bytes[len] = b
            len += 1
        }
        f.append("${String.fromUtf8(((bytes).sliceArray(0 until len))).unwrap()}")
    }

    public open fun fmtDebug(f: StringBuilder) {
        f.append("\"")
        f.append((this).toString())
        f.append("\"")
    }

    public companion object {
        public fun typeLayout(): TypeLayout<Byte> =
            object : TypeLayout<Byte> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 1L
                override fun readNative(addr: Long): Byte =
                    Byte(
                        (PrimitiveLayouts.U8).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: Byte) {
                    (PrimitiveLayouts.U8).writeNative(addr, value.value)
                }
                override fun readBytes(buf: ByteArray, offset: Int): Byte =
                    Byte(
                        (PrimitiveLayouts.U8).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: Byte) {
                    (PrimitiveLayouts.U8).writeBytes(buf, offset, value.value)
                }
            }
    }
}

/**
 * Provides a convenient `Debug` implementation for `[UByte]`.
 *
 * This generally works best when the bytes are presumed to be mostly
 * UTF-8, but will work for anything. For any bytes that aren't UTF-8,
 * they are emitted as hex escape sequences.
 */
public open class Bytes(
    public var value: List<UByte>,
) {
    public var field0: List<UByte>
        get() = value
        set(v) {
            value = v
        }

    public open fun clone(): Bytes = Bytes(value)

    public open fun fmt(f: StringBuilder) {
        // This is a sad re-implementation of a similar impl found in bstr.
        var bytes = this.value
        while (true) {
            val matchVal = decode(bytes)
            if (matchVal == null) break
            val result = matchVal
            val ch1 =
                when (result) { is Result.Ok<*, *> -> {
                    val ch = result.okValue()
                    ch
                }; is Result.Err<*, *> -> {
                    val byte = result.errValue()
                    f.append("\\x$byte")
                    bytes = ((bytes).subList(1, (bytes).size))
                    continue
                } }
            bytes = ((bytes).subList(ch1.lenUtf8(), (bytes).size))
            when (ch1) {
                '\u0000' -> { f.append("\\0") }
                in '\u0001'..'\u007f' -> {
                    f.append("${(ch1.toUByte()).escapeAscii()}")
                }
                else -> f.append("${ch1.escapeDebug()}")
            }
        }
    }

    public open fun fmtDebug(f: StringBuilder) {
        f.append("\"")
        f.append((this).toString())
        f.append("\"")
    }
}
