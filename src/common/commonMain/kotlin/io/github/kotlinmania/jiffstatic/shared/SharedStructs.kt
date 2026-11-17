package io.github.kotlinmania.jiffstatic.shared

import io.github.kotlinmania.rustlang.core.cmp.max
import io.github.kotlinmania.rustlang.core.mem.AlignedTypeLayout
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TransparentTypeLayout
import io.github.kotlinmania.rustlang.core.mem.TypeLayout
import io.github.kotlinmania.rustlang.core.mem.UnresolvedTypeLayout
import io.github.kotlinmania.rustlang.core.mem.alignUpBytes
import io.github.kotlinmania.rustlang.core.mem.allocateNativeCell
import io.github.kotlinmania.rustlang.core.ptr.MutPtr
import io.github.kotlinmania.rustlang.core.ptr.writeBytes

public open class Tzif<Str, Abbrev, Types, Timestamps, Starts, Ends, Infos>(
    public var fixed: TzifFixed<Str, Abbrev>,
    public var types: Types,
    public var transitions: TzifTransitions<Timestamps, Starts, Ends, Infos>,
) {
    public open fun clone(): Tzif<Str, Abbrev, Types, Timestamps, Starts, Ends, Infos> = Tzif(fixed, types, transitions)
}

public open class TzifFixed<Str, Abbrev>(
    public var name: Str?,
    /**
     * An ASCII byte corresponding to the version number. So, 0x50 is '2'.
     *
     * This is unused. It's only used in `test` compilation for emitting
     * diagnostic data about TZif files. If we really need to use this, we
     * should probably just convert it to an actual integer.
     */
    public var version: UByte,
    public var checksum: UInt,
    public var designations: Str,
    public var posixTz: PosixTimeZone<Abbrev>?,
) {
    public open fun clone(): TzifFixed<Str, Abbrev> = TzifFixed(name, version, checksum, designations, posixTz)
}

public open class TzifLocalTimeType(
    public var offset: Int,
    public var isDst: Boolean,
    public var designation: Pair<UByte, UByte>,
    // inclusive..exclusive
    public var indicator: TzifIndicator,
) {
    public open fun clone(): TzifLocalTimeType = TzifLocalTimeType(offset, isDst, designation, indicator)
}

/**
 * The set of transitions in TZif data, laid out in column orientation.
 *
 * The column orientation is used to make TZ lookups faster. Specifically,
 * for finding an offset for a timestamp, we do a binary search on
 * `timestamps`. For finding an offset for a local datetime, we do a binary
 * search on `civilStarts`. By making these two distinct sequences with
 * nothing else in them, we make them as small as possible and thus improve
 * cache locality.
 *
 * All sequences in this type are in correspondence with one another. They
 * are all guaranteed to have the same length.
 */
public open class TzifTransitions<Timestamps, Starts, Ends, Infos>(
    /**
     * The timestamp at which this transition begins.
     */
    public var timestamps: Timestamps,
    /**
     * The wall clock time for when a transition begins.
     */
    public var civilStarts: Starts,
    /**
     * The wall clock time for when a transition ends.
     *
     * This is only non-zero when the transition kind is a gap or a fold.
     */
    public var civilEnds: Ends,
    /**
     * Any other relevant data about a transition, such as its local type
     * index and the transition kind.
     */
    public var infos: Infos,
) {
    public open fun clone(): TzifTransitions<Timestamps, Starts, Ends, Infos> = TzifTransitions(timestamps, civilStarts, civilEnds, infos)

    public companion object {
        public fun <Timestamps, Starts, Ends, Infos> typeLayout(typeLayoutTimestamps: TypeLayout<Timestamps>, typeLayoutStarts: TypeLayout<Starts>, typeLayoutEnds: TypeLayout<Ends>, typeLayoutInfos: TypeLayout<Infos>): TypeLayout<TzifTransitions<Timestamps, Starts, Ends, Infos>> =
            object : TypeLayout<TzifTransitions<Timestamps, Starts, Ends, Infos>> {
                override val alignBytes: Long = listOf(1L, typeLayoutTimestamps.alignBytes, typeLayoutStarts.alignBytes, typeLayoutEnds.alignBytes, typeLayoutInfos.alignBytes).max()
                private val off1: Long = alignUpBytes(typeLayoutTimestamps.sizeBytes, typeLayoutStarts.alignBytes)
                private val off2: Long = alignUpBytes((off1 + typeLayoutStarts.sizeBytes), typeLayoutEnds.alignBytes)
                private val off3: Long = alignUpBytes((off2 + typeLayoutEnds.sizeBytes), typeLayoutInfos.alignBytes)
                override val sizeBytes: Long = alignUpBytes((off3 + typeLayoutInfos.sizeBytes), alignBytes)
                override fun readNative(addr: Long): TzifTransitions<Timestamps, Starts, Ends, Infos> =
                    TzifTransitions(
                        (typeLayoutTimestamps).readNative(addr),
                        (typeLayoutStarts).readNative(addr + off1),
                        (typeLayoutEnds).readNative(addr + off2),
                        (typeLayoutInfos).readNative(addr + off3),
                    )
                override fun writeNative(addr: Long, value: TzifTransitions<Timestamps, Starts, Ends, Infos>) {
                    (typeLayoutTimestamps).writeNative(addr, value.timestamps)
                    (typeLayoutStarts).writeNative(addr + off1, value.civilStarts)
                    (typeLayoutEnds).writeNative(addr + off2, value.civilEnds)
                    (typeLayoutInfos).writeNative(addr + off3, value.infos)
                }
                override fun readBytes(buf: ByteArray, offset: Int): TzifTransitions<Timestamps, Starts, Ends, Infos> =
                    TzifTransitions(
                        (typeLayoutTimestamps).readBytes(buf, offset),
                        (typeLayoutStarts).readBytes(buf, offset + off1.toInt()),
                        (typeLayoutEnds).readBytes(buf, offset + off2.toInt()),
                        (typeLayoutInfos).readBytes(buf, offset + off3.toInt()),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: TzifTransitions<Timestamps, Starts, Ends, Infos>) {
                    (typeLayoutTimestamps).writeBytes(buf, offset, value.timestamps)
                    (typeLayoutStarts).writeBytes(buf, offset + off1.toInt(), value.civilStarts)
                    (typeLayoutEnds).writeBytes(buf, offset + off2.toInt(), value.civilEnds)
                    (typeLayoutInfos).writeBytes(buf, offset + off3.toInt(), value.infos)
                }
            }
    }
}

/**
 * TZif transition info beyond the timestamp and civil datetime.
 *
 * For example, this contains a transition's "local type index," which in
 * turn gives access to the offset (among other metadata) for that transition.
 */
public open class TzifTransitionInfo(
    /**
     * The index into the sequence of local time type records. This is what
     * provides the correct offset (from UTC) that is active beginning at
     * this transition.
     */
    public var typeIndex: UByte,
    /**
     * The boundary condition for quickly determining if a given wall clock
     * time is ambiguous (i.e., falls in a gap or a fold).
     */
    public var kind: TzifTransitionKind,
) {
    public open fun clone(): TzifTransitionInfo = TzifTransitionInfo(typeIndex, kind)
}

/**
 * The representation we use to represent a civil datetime.
 *
 * We don't use `shared.util.itime.IDateTime` here because we specifically
 * do not need to represent fractional seconds. This lets us easily represent
 * what we need in 8 bytes instead of the 12 bytes used by `IDateTime`.
 *
 * Moreover, we pack the fields into a single `Long` to make comparisons
 * extremely cheap. This is especially useful since we do a binary search on
 * `[TzifDateTime]` when doing a TZ lookup for a civil datetime.
 */
public open class TzifDateTime internal constructor(
    public val fieldStorage: MutPtr,
    public val fieldLayout: TypeLayout<Long> = UnresolvedTypeLayout(),
) {
    public var bits: Long
        get() = fieldLayout.readNative(fieldStorage.rawAddress)
        set(value) { fieldLayout.writeNative(fieldStorage.rawAddress, value) }

    internal constructor(bits: Long, fieldLayout: TypeLayout<Long> = UnresolvedTypeLayout()) : this(allocateNativeCell(bits, AlignedTypeLayout(fieldLayout, 8L)), AlignedTypeLayout(fieldLayout, 8L))

    public fun pointerLayout(): TypeLayout<TzifDateTime> = TransparentTypeLayout(fieldLayout, { ptr, layout -> TzifDateTime(ptr, layout) }, { it.bits })

    public open fun clone(): TzifDateTime = TzifDateTime(bits, fieldLayout)

    public open fun year(): Short = ((this.bits.toULong() shr 48).toUShort()).toShort()

    public open fun month(): Byte = ((this.bits.toULong() shr 40).toUByte()).toByte()

    public open fun day(): Byte = ((this.bits.toULong() shr 32).toUByte()).toByte()

    public open fun hour(): Byte = ((this.bits.toULong() shr 24).toUByte()).toByte()

    public open fun minute(): Byte = ((this.bits.toULong() shr 16).toUByte()).toByte()

    public open fun second(): Byte = ((this.bits.toULong() shr 8).toUByte()).toByte()

    public companion object {
        public operator fun invoke(bits: Long, fieldLayout: TypeLayout<Long> = UnresolvedTypeLayout()): TzifDateTime = TzifDateTime(bits, fieldLayout)

        public fun fromFieldStorage(fieldStorage: MutPtr, fieldLayout: TypeLayout<Long> = UnresolvedTypeLayout()): TzifDateTime = TzifDateTime(fieldStorage, fieldLayout)

        public val ZERO: TzifDateTime = TzifDateTime.new(0, 0, 0, 0, 0, 0, fieldLayout = PrimitiveLayouts.I64)

        public fun new(year: Short, month: Byte, day: Byte, hour: Byte, minute: Byte, second: Byte, fieldLayout: TypeLayout<Long> = UnresolvedTypeLayout()): TzifDateTime {
            // TzifDateTime ( year, month, day, hour, minute, second )
            var bits = (year.toULong()) shl 48
            bits = bits or ((month.toULong()) shl 40)
            bits = bits or ((day.toULong()) shl 32)
            bits = bits or ((hour.toULong()) shl 24)
            bits = bits or ((minute.toULong()) shl 16)
            bits = bits or ((second.toULong()) shl 8)
            // The least significant 8 bits remain 0.
            return TzifDateTime(bits = bits.toLong(), fieldLayout = fieldLayout)
        }
    }
}

public open class PosixTimeZone<Abbrev>(
    public var stdAbbrev: Abbrev,
    public var stdOffset: PosixOffset,
    public var dst: PosixDst<Abbrev>?,
) {
    public open fun clone(): PosixTimeZone<Abbrev> = PosixTimeZone(stdAbbrev, stdOffset, dst)
}

public open class PosixDst<Abbrev>(
    public var abbrev: Abbrev,
    public var offset: PosixOffset,
    public var rule: PosixRule,
) {
    public open fun clone(): PosixDst<Abbrev> = PosixDst(abbrev, offset, rule)
}

public open class PosixRule(
    public var start: PosixDayTime,
    public var end: PosixDayTime,
) {
    public open fun clone(): PosixRule = PosixRule(start, end)
}

public open class PosixDayTime(
    public var date: PosixDay,
    public var time: PosixTime,
) {
    public open fun clone(): PosixDayTime = PosixDayTime(date, time)
}

public open class PosixTime(
    public var second: Int,
) {
    public open fun clone(): PosixTime = PosixTime(second)

    public companion object {
        public fun typeLayout(): TypeLayout<PosixTime> =
            object : TypeLayout<PosixTime> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 4L
                override fun readNative(addr: Long): PosixTime =
                    PosixTime(
                        (PrimitiveLayouts.I32).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: PosixTime) {
                    (PrimitiveLayouts.I32).writeNative(addr, value.second)
                }
                override fun readBytes(buf: ByteArray, offset: Int): PosixTime =
                    PosixTime(
                        (PrimitiveLayouts.I32).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: PosixTime) {
                    (PrimitiveLayouts.I32).writeBytes(buf, offset, value.second)
                }
            }
    }
}

public open class PosixOffset(
    public var second: Int,
) {
    public open fun clone(): PosixOffset = PosixOffset(second)

    public companion object {
        public fun typeLayout(): TypeLayout<PosixOffset> =
            object : TypeLayout<PosixOffset> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 4L
                override fun readNative(addr: Long): PosixOffset =
                    PosixOffset(
                        (PrimitiveLayouts.I32).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: PosixOffset) {
                    (PrimitiveLayouts.I32).writeNative(addr, value.second)
                }
                override fun readBytes(buf: ByteArray, offset: Int): PosixOffset =
                    PosixOffset(
                        (PrimitiveLayouts.I32).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: PosixOffset) {
                    (PrimitiveLayouts.I32).writeBytes(buf, offset, value.second)
                }
            }
    }
}
