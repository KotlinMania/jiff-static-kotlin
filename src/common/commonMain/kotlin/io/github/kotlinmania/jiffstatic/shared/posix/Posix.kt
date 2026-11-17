// port-lint: source shared/posix.rs
package io.github.kotlinmania.jiffstatic.shared.posix

import io.github.kotlinmania.jiffstatic.shared.PosixDay
import io.github.kotlinmania.jiffstatic.shared.PosixDayTime
import io.github.kotlinmania.jiffstatic.shared.PosixDst
import io.github.kotlinmania.jiffstatic.shared.PosixOffset
import io.github.kotlinmania.jiffstatic.shared.PosixRule
import io.github.kotlinmania.jiffstatic.shared.PosixTime
import io.github.kotlinmania.jiffstatic.shared.PosixTimeZone
import io.github.kotlinmania.jiffstatic.shared.util.arraystr.Abbreviation
import io.github.kotlinmania.jiffstatic.shared.util.error.Error
import io.github.kotlinmania.jiffstatic.shared.util.error.err
import io.github.kotlinmania.jiffstatic.shared.util.escape.Byte
import io.github.kotlinmania.jiffstatic.shared.util.escape.Bytes
import io.github.kotlinmania.jiffstatic.shared.util.itime.IAmbiguousOffset
import io.github.kotlinmania.jiffstatic.shared.util.itime.IDate
import io.github.kotlinmania.jiffstatic.shared.util.itime.IDateTime
import io.github.kotlinmania.jiffstatic.shared.util.itime.IOffset
import io.github.kotlinmania.jiffstatic.shared.util.itime.ITime
import io.github.kotlinmania.jiffstatic.shared.util.itime.ITimeSecond
import io.github.kotlinmania.jiffstatic.shared.util.itime.ITimestamp
import io.github.kotlinmania.jiffstatic.shared.util.itime.IWeekday
import io.github.kotlinmania.rustlang.core.cell.Cell
import io.github.kotlinmania.rustlang.core.char.methods.isAsciiAlphabetic
import io.github.kotlinmania.rustlang.core.char.methods.isAsciiAlphanumeric
import io.github.kotlinmania.rustlang.core.char.methods.isAsciiDigit
import io.github.kotlinmania.rustlang.core.cmp.max
import io.github.kotlinmania.rustlang.core.mem.TypeLayout
import io.github.kotlinmania.rustlang.core.mem.alignUpBytes
import io.github.kotlinmania.rustlang.core.num.checkedAdd
import io.github.kotlinmania.rustlang.core.num.checkedMul
import io.github.kotlinmania.rustlang.core.num.checkedSub
import io.github.kotlinmania.rustlang.core.num.divEuclid
import io.github.kotlinmania.rustlang.core.num.remEuclid
import io.github.kotlinmania.rustlang.core.num.saturatingNeg
import io.github.kotlinmania.rustlang.core.ptr.writeBytes
import io.github.kotlinmania.rustlang.core.result.Result
import io.github.kotlinmania.rustlang.core.result.unwrapOr

/**
 * Parse a POSIX `TZ` environment variable, assuming it's a rule and not
 * an implementation defined value, from the given bytes.
 */
public fun PosixTimeZone.Companion.parse(bytes: List<UByte>): Result<PosixTimeZone<Abbreviation>, Error> {
    // We enable the IANA v3+ extensions here. (Namely, that the time
    // specification hour value has the range `- 167 ..= 167` instead of
    // `0 ..= 24`.) Requiring strict POSIX rules doesn't seem necessary
    // since the extension is a strict superset. Plus, GNU tooling
    // seems to accept the extension.
    val parser = Parser.new(bytes).copy(ianav3plus = true)
    return parser.parse()
}

/**
 * Returns the appropriate time zone offset to use for the given
 * timestamp.
 *
 * If you need information like whether the offset is in DST or not, or
 * the time zone abbreviation, then use `PosixTimeZone.toOffsetInfo`.
 * But that API may be more expensive to use, so only use it if you need
 * the additional data.
 */
public fun PosixTimeZone<Abbrev>.toOffset(timestamp: ITimestamp): IOffset {
    val stdOffset = this.stdOffset.toIoffset()
    if (this.dst == null) {
        return stdOffset
    }
    val dt = timestamp.toDatetime(IOffset.UTC)
    return (this.dstInfoUtc(dt.date.year).filter({ dstInfo -> dstInfo.inDst(dt) })?.let({ dstInfo -> dstInfo.offset().toIoffset() }) ?: stdOffset)
}

/**
 * Returns the appropriate time zone offset to use for the given
 * timestamp.
 *
 * This also includes whether the offset returned should be considered
 * to be "DST" or not, along with the time zone abbreviation (e.g., EST
 * for standard time in New York, and EDT for DST in New York).
 */
public fun PosixTimeZone<Abbrev>.toOffsetInfo(timestamp: ITimestamp): Triple<IOffset, String, Boolean> {
    val stdOffset = this.stdOffset.toIoffset()
    if (this.dst == null) {
        return Triple(stdOffset, this.stdAbbrev.asRef(), false)
    }
    val dt = timestamp.toDatetime(IOffset.UTC)
    return (this.dstInfoUtc(dt.date.year).filter({ dstInfo -> dstInfo.inDst(dt) })?.let({ dstInfo ->
        Triple(dstInfo.offset().toIoffset(), dstInfo.dst.abbrev.asRef(), true)
    }) ?: Triple(stdOffset, this.stdAbbrev.asRef(), false))
}

/**
 * Returns a possibly ambiguous timestamp for the given civil datetime.
 *
 * The given datetime should correspond to the "wall" clock time of what
 * humans use to tell time for this time zone.
 *
 * Note that "ambiguous timestamp" is represented by the possible
 * selection of offsets that could be applied to the given datetime. In
 * general, it is only ambiguous around transitions to-and-from DST. The
 * ambiguity can arise as a "fold" (when a particular wall clock time is
 * repeated) or as a "gap" (when a particular wall clock time is skipped
 * entirely).
 */
public fun PosixTimeZone<Abbrev>.toAmbiguousKind(dt: IDateTime): IAmbiguousOffset {
    val year = dt.date.year
    val stdOffset = this.stdOffset.toIoffset()
    val dstInfo = this.dstInfoWall(year) ?: return IAmbiguousOffset.Unambiguous(offset = stdOffset)
    val dstOffset = dstInfo.offset().toIoffset()
    val diff = dstOffset.second - stdOffset.second
    // When the difference between DST and standard is positive, that means
    // STD->DST results in a gap while DST->STD results in a fold. However,
    // when the difference is negative, that means STD->DST results in a
    // fold while DST->STD results in a gap. The former is by far the most
    // common. The latter is a bit weird, but real cases do exist. For
    // example, Dublin has DST in winter (UTC+01) and STD in the summer
    // (UTC+00).
    //
    // When the difference is zero, then we have a weird POSIX time zone
    // where a DST transition rule was specified, but was set to explicitly
    // be the same as STD. In this case, there can be no ambiguity. (The
    // zero case is strictly redundant. Both the diff < 0 and diff > 0
    // cases handle the zero case correctly. But we write it out for
    // clarity.)
    return if (diff == 0) {
        check(stdOffset == dstOffset)
        IAmbiguousOffset.Unambiguous(offset = stdOffset)
    } else if (diff.isNegative()) {
        // For DST transitions that always move behind one hour, ambiguous
        // timestamps only occur when the given civil datetime falls in the
        // standard time range.
        if (dstInfo.inDst(dt)) {
            IAmbiguousOffset.Unambiguous(offset = dstOffset)
        } else {
            val foldStart = dstInfo!!.start.saturatingAddSeconds(diff)
            val gapEnd = dstInfo.end.saturatingAddSeconds(diff.saturatingNeg())
            if (foldStart <= dt && dt < dstInfo.start) {
                IAmbiguousOffset.Fold(before = stdOffset, after = dstOffset)
            } else if (dstInfo.end <= dt && dt < gapEnd) {
                IAmbiguousOffset.Gap(before = dstOffset, after = stdOffset)
            } else {
                IAmbiguousOffset.Unambiguous(offset = stdOffset)
            }
        }
    } else {
        // For DST transitions that always move ahead one hour, ambiguous
        // timestamps only occur when the given civil datetime falls in the
        // DST range.
        if (!dstInfo.inDst(dt)) {
            IAmbiguousOffset.Unambiguous(offset = stdOffset)
        } else {
            // PERF: I wonder if it makes sense to pre-compute these?
            // Probably not, because we have to do it based on year of
            // datetime given. But if we ever add a "caching" layer for
            // POSIX time zones, then it might be worth adding these to it.
            val gapEnd1 = dstInfo!!.start.saturatingAddSeconds(diff)
            val foldStart1 = dstInfo.end.saturatingAddSeconds(diff.saturatingNeg())
            if (dstInfo.start <= dt && dt < gapEnd1) {
                IAmbiguousOffset.Gap(before = stdOffset, after = dstOffset)
            } else if (foldStart1 <= dt && dt < dstInfo.end) {
                IAmbiguousOffset.Fold(before = dstOffset, after = stdOffset)
            } else {
                IAmbiguousOffset.Unambiguous(offset = dstOffset)
            }
        }
    }
}

/**
 * Returns the timestamp of the most recent time zone transition prior
 * to the timestamp given. If one doesn't exist, `None` is returned.
 */
public fun PosixTimeZone<Abbrev>.previousTransition(timestamp: ITimestamp): List<Any?>? {
    val dt = timestamp.toDatetime(IOffset.UTC)
    val dstInfo = (this.dstInfoUtc(dt.date.year) ?: return null)
    val (earlier, later) = dstInfo.ordered()
    val (prev, dstInfo2) =
        if (dt > later) {
            Pair(later, dstInfo)
        } else if (dt > earlier) {
            Pair(earlier, dstInfo)
        } else {
            val prevYear = (dt.date.prevYear().ok() ?: return null)
            val dstInfo1 = (this.dstInfoUtc(prevYear) ?: return null)
            val (_, later1) = dstInfo1.ordered()
            Pair(later1, dstInfo1)
        }
    val timestamp1 = (prev.toTimestampChecked(IOffset.UTC) ?: return null)
    val dt1 = timestamp1.toDatetime(IOffset.UTC)
    val (offset, abbrev, dst) =
        if (dstInfo2.inDst(dt1)) {
            Triple(dstInfo2.offset(), dstInfo2.dst.abbrev.asRef(), true)
        } else {
            Triple(this.stdOffset, this.stdAbbrev.asRef(), false)
        }
    return listOf(timestamp1, offset.toIoffset(), abbrev, dst)
}

/**
 * Returns the timestamp of the soonest time zone transition after the
 * timestamp given. If one doesn't exist, `None` is returned.
 */
public fun PosixTimeZone<Abbrev>.nextTransition(timestamp: ITimestamp): List<Any?>? {
    val dt = timestamp.toDatetime(IOffset.UTC)
    val dstInfo = (this.dstInfoUtc(dt.date.year) ?: return null)
    val (earlier, later) = dstInfo.ordered()
    val (next, dstInfo2) =
        if (dt < earlier) {
            Pair(earlier, dstInfo)
        } else if (dt < later) {
            Pair(later, dstInfo)
        } else {
            val nextYear = (dt.date.nextYear().ok() ?: return null)
            val dstInfo1 = (this.dstInfoUtc(nextYear) ?: return null)
            val (earlier1, _) = dstInfo1.ordered()
            Pair(earlier1, dstInfo1)
        }
    val timestamp1 = (next.toTimestampChecked(IOffset.UTC) ?: return null)
    val dt1 = timestamp1.toDatetime(IOffset.UTC)
    val (offset, abbrev, dst) =
        if (dstInfo2.inDst(dt1)) {
            Triple(dstInfo2.offset(), dstInfo2.dst.abbrev.asRef(), true)
        } else {
            Triple(this.stdOffset, this.stdAbbrev.asRef(), false)
        }
    return listOf(timestamp1, offset.toIoffset(), abbrev, dst)
}

/**
 * Returns the range in which DST occurs.
 *
 * The civil datetimes returned are in UTC. This is useful for determining
 * whether a timestamp is in DST or not.
 */
public fun PosixTimeZone<Abbrev>.dstInfoUtc(year: Short): DstInfo<String>? {
    val dst = (this.dst.asRef() ?: return null)
    // DST time starts with respect to standard time, so offset it by the
    // standard offset.
    val start = dst.rule.start.toDatetime(year, this.stdOffset.toIoffset())
    // DST time ends with respect to DST time, so offset it by the DST
    // offset.
    val end = dst.rule.end.toDatetime(year, dst.offset.toIoffset())
    return DstInfo(dst = dst, start = start, end = end)
}

/**
 * Returns the range in which DST occurs.
 *
 * The civil datetimes returned are in "wall clock time." That is, they
 * represent the transitions as they are seen from humans reading a clock
 * within the geographic location of that time zone.
 */
public fun PosixTimeZone<Abbrev>.dstInfoWall(year: Short): DstInfo<String>? {
    val dst = (this.dst.asRef() ?: return null)
    // POSIX time zones express their DST transitions in terms of wall
    // clock time. Since this method specifically is returning wall
    // clock times, we don't want to offset our datetimes at all.
    val start = dst.rule.start.toDatetime(year, IOffset.UTC)
    val end = dst.rule.end.toDatetime(year, IOffset.UTC)
    return DstInfo(dst = dst, start = start, end = end)
}

public fun PosixTimeZone<Abbrev>.fmt(f: StringBuilder) {
    f.append("${AbbreviationDisplay(this.stdAbbrev.asRef())}${this.stdOffset}")
    when (val matchVal = this.dst) {
        null -> {}
        else -> {
            val dst = matchVal
            dst.display(this.stdOffset, f)
        }
    }
}

public fun PosixDst<Abbrev>.display(stdOffset: PosixOffset, f: StringBuilder) {
    f.append("${AbbreviationDisplay(this.abbrev.asRef())}")
    // The overwhelming common case is that DST is exactly one hour ahead
    // of standard time. So common that this is the default. So don't write
    // the offset if we don't need to.
    val default = PosixOffset(second = stdOffset.second + 3600)
    if (this.offset != default) {
        f.append("${this.offset}")
    }
    f.append(",${this.rule}")
}

public fun PosixRule.fmt(f: StringBuilder) {
    f.append("${this.start},${this.end}")
}

/**
 * Turns this POSIX datetime spec into a civil datetime in the year given
 * with the given offset. The datetimes returned are offset by the given
 * offset. For wall clock time, an offset of `0` should be given. For
 * UTC time, the offset (standard or DST) corresponding to this time
 * spec should be given.
 *
 * The datetime returned is guaranteed to have a year component equal
 * to the year given. This guarantee is upheld even when the datetime
 * specification (combined with the offset) would extend past the end of
 * the year (or before the start of the year). In this case, the maximal
 * (or minimal) datetime for the given year is returned.
 */
public fun PosixDayTime.toDatetime(year: Short, offset: IOffset): IDateTime {
    val mkmin = { IDateTime(date = IDate(year = year, month = 1, day = 1), time = ITime.MIN) }
    val mkmax = { IDateTime(date = IDate(year = year, month = 12, day = 31), time = ITime.MAX) }
    val date = this.date.toDate(year) ?: return mkmax()
    // The range on `self.time` is `- 604799 ..= 604799`, and the range
    // on `offset.second` is `- 93599 ..= 93599`. Therefore, subtracting
    // them can never overflow an `Int`.
    val offset1 = this.time.second - offset.second
    // If the time goes negative or above 86400, then we might have
    // to adjust our date.
    val days = offset1.divEuclid(86400)
    val second = offset1.remEuclid(86400)
    val date1 = when (val tryResult = date.checkedAddDays(days)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return if (offset1 < 0) {
    mkmin()
} else {
    mkmax()
} }
    return if (date1.year < year) {
        mkmin()
    } else if (date1.year > year) {
        mkmax()
    } else {
        val time = ITimeSecond(second = second).toTime()
        IDateTime(date = date1, time = time)
    }
}

public fun PosixDayTime.fmt(f: StringBuilder) {
    f.append("${this.date}")
    // This is the default time, so don't write it if we
    // don't need to.
    if (this.time != PosixTime.DEFAULT) {
        f.append("/${this.time}")
    }
}

/**
 * Convert this date specification to a civil date in the year given.
 *
 * If this date specification couldn't be turned into a date in the year
 * given, then `None` is returned. This happens when `366` is given as
 * a day, but the year given is not a leap year. In this case, callers may
 * want to assume a datetime that is maximal for the year given.
 */
public fun PosixDay.toDate(year: Short): IDate? {
    return when (this) {
        is PosixDay.JulianOne -> {
            val (day) = this as PosixDay.JulianOne
            // Parsing validates that our day is 1-365 which will always
            // succeed for all possible year values. That is, every valid
            // year has a December 31.
            IDate.fromDayOfYearNoLeap(year, day).expect("Julian `J day` should be in bounds")
        }
        is PosixDay.JulianZero -> {
            val (day1) = this as PosixDay.JulianZero
            // OK because our value for `day` is validated to be `0 ..= 365`,
            // and since it is an `Short`, it is always valid to add 1.
            //
            // Also, while `day + 1` is guaranteed to be in `1 ..= 366`, it is
            // possible that `366` is invalid, for when `year` is not a
            // leap year. In this case, we throw our hands up, and ask the
            // caller to make a decision for how to deal with it. Why does
            // POSIX go out of its way to specifically not specify behavior
            // in error cases?
            IDate.fromDayOfYear(year, (day1 + 1).toShort()).ok()
        }
        is PosixDay.WeekdayOfMonth -> {
            val month = (this as PosixDay.WeekdayOfMonth).month
            val week = (this as PosixDay.WeekdayOfMonth).week
            val weekday = (this as PosixDay.WeekdayOfMonth).weekday
            val weekday1 = IWeekday.fromSundayZeroOffset(weekday)
            val first = IDate(year = year, month = month, day = 1)
            val week1 =
                if (week == 5) {
                    -1
                } else {
                    week
                }
            check(week1 == -1 || (1..4).contains(week1))
            // This is maybe non-obvious, but this will always succeed
            // because it can only fail when the week number is one of
            // (-5, 0, 5). Since we've validated that `week` is in 1..=5,
            // we know it can't be 0. Moreover, because of the conditional
            // above and since `5` actually means "last weekday of month,"
            // that case will always translate to `- 1`.
            //
            // Also, I looked at how other libraries deal with this case,
            // and almost all of them just do a bunch of inline hairy
            // arithmetic here. I suppose I could be reduced to such
            // things if perf called for it, but we have a nice civil date
            // abstraction. So use it, god damn it. (Well, we did, and now
            // we have a lower level IDate abstraction. But it's still
            // an abstraction!)
            first.nthWeekdayOfMonth(week1, weekday1).expect("nth weekday always exists")
        }
    }
}

public fun PosixDay.fmt(f: StringBuilder) {
    when (this) {
        is PosixDay.JulianOne -> {
            val (n) = this as PosixDay.JulianOne
            f.append("J$n")
        }
        is PosixDay.JulianZero -> {
            val (n1) = this as PosixDay.JulianZero
            f.append("$n")
        }
        is PosixDay.WeekdayOfMonth -> {
            val month = (this as PosixDay.WeekdayOfMonth).month
            val week = (this as PosixDay.WeekdayOfMonth).week
            val weekday = (this as PosixDay.WeekdayOfMonth).weekday
            f.append("M$month.$week.$weekday")
        }
    }
}

public val DEFAULT: PosixTime = PosixTime(second = 2 * 60 * 60)

public fun PosixTime.fmt(f: StringBuilder) {
    if (this.second.isNegative()) {
        f.append("-")
        // The default is positive, so when
        // positive, we write nothing.
    }
    val second = this.second.unsignedAbs()
    val h = second / 3600
    val m = (second / 60) % 60
    val s = second % 60
    f.append("$h")
    if (m != 0 || s != 0) {
        f.append(":$m")
        if (s != 0) {
            f.append(":$s")
        }
    }
}

public fun PosixOffset.toIoffset(): IOffset = IOffset(second = this.second)

public fun PosixOffset.fmt(f: StringBuilder) {
    // Yes, this is backwards. Blame POSIX.
    // N.B. `+` is the default, so we don't
    // need to write that out.
    if (this.second > 0) {
        f.append("-")
    }
    val second = this.second.unsignedAbs()
    val h = second / 3600
    val m = (second / 60) % 60
    val s = second % 60
    f.append("$h")
    if (m != 0 || s != 0) {
        f.append(":$m")
        if (s != 0) {
            f.append(":$s")
        }
    }
}

/**
 * A helper type for formatting a time zone abbreviation.
 *
 * Basically, this will write the `<` and `>` quotes if necessary, and
 * otherwise write out the abbreviation in its unquoted form.
 */
public open class AbbreviationDisplay<S>(
    public var value: S,
) {
    public var field0: S
        get() = value
        set(v) {
            value = v
        }

    public companion object {
        public fun <S> typeLayout(typeLayoutS: TypeLayout<S>): TypeLayout<AbbreviationDisplay<S>> =
            object : TypeLayout<AbbreviationDisplay<S>> {
                override val alignBytes: Long = listOf(1L, typeLayoutS.alignBytes).max()
                override val sizeBytes: Long = alignUpBytes(typeLayoutS.sizeBytes, alignBytes)
                override fun readNative(addr: Long): AbbreviationDisplay<S> =
                    AbbreviationDisplay(
                        (typeLayoutS).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: AbbreviationDisplay<S>) {
                    (typeLayoutS).writeNative(addr, value.value)
                }
                override fun readBytes(buf: ByteArray, offset: Int): AbbreviationDisplay<S> =
                    AbbreviationDisplay(
                        (typeLayoutS).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: AbbreviationDisplay<S>) {
                    (typeLayoutS).writeBytes(buf, offset, value.value)
                }
            }
    }
}

public fun AbbreviationDisplay<S>.fmt(f: StringBuilder) {
    val s = this.value
    if (s.chars().any({ ch -> ch == '+' || ch == '-' })) {
        f.append("<$s>")
    } else {
        f.append("$s")
    }
}

/**
 * The daylight saving time (DST) info for a POSIX time zone in a particular
 * year.
 */
public open class DstInfo<Abbrev>(
    /**
     * The DST transition rule that generated this info.
     */
    public var dst: PosixDst<Abbrev>,
    /**
     * The start time (inclusive) that DST begins.
     *
     * Note that this may be greater than `end`. This tends to happen in the
     * southern hemisphere.
     *
     * Note also that this may be in UTC or in wall clock civil
     * time. It depends on whether `PosixTimeZone.dstInfoUtc` or
     * `PosixTimeZone.dstInfoWall` was used.
     */
    public var start: IDateTime,
    /**
     * The end time (exclusive) that DST ends.
     *
     * Note that this may be less than `start`. This tends to happen in the
     * southern hemisphere.
     *
     * Note also that this may be in UTC or in wall clock civil
     * time. It depends on whether `PosixTimeZone.dstInfoUtc` or
     * `PosixTimeZone.dstInfoWall` was used.
     */
    public var end: IDateTime,
) {
    /**
     * Returns true if and only if the given civil datetime ought to be
     * considered in DST.
     */
    public open fun inDst(utcDt: IDateTime): Boolean =
        if (this.start <= this.end) {
            this.start <= utcDt && utcDt < this.end
        } else {
            !(this.end <= utcDt && utcDt < this.start)
        }

    /**
     * Returns the earlier and later times for this DST info.
     */
    public open fun ordered(): Pair<IDateTime, IDateTime> =
        if (this.start <= this.end) {
            Pair(this.start, this.end)
        } else {
            Pair(this.end, this.start)
        }

    /**
     * Returns the DST offset.
     */
    public open fun offset(): PosixOffset = this.dst.offset
}

/**
 * A parser for POSIX time zones.
 */
public open class Parser internal constructor(
    /**
     * The `TZ` string that we're parsing.
     */
    public var tz: List<UByte>,
    posCtorArg: Cell<Int>,
    /**
     * Whether to use IANA rules, i.e., when parsing a TZ string in a TZif
     * file of version 3 or greater. From `tzfile(5)`:
     *
     * > First, the hours part of its transition times may be signed and range
     * > from `- 167` through `167` instead of the POSIX-required unsigned
     * > values from `0` through `24`. Second, DST is in effect all year if
     * > it starts January 1 at 00:00 and ends December 31 at 24:00 plus the
     * > difference between daylight saving and standard time.
     *
     * At time of writing, I don't think I understand the significance of
     * the second part above. (RFC 8536 elaborates that it is meant to be an
     * explicit clarification of something that POSIX itself implies.) But the
     * first part is clear: it permits the hours to be a bigger range.
     */
    public var ianav3plus: Boolean,
) {
    /**
     * The parser's current position in `tz`.
     */
    public var pos: Cell<Int> = posCtorArg
        internal set

    /**
     * Parses a POSIX time zone from the current position of the parser and
     * ensures that the entire TZ string corresponds to a single valid POSIX
     * time zone.
     */
    public open fun parse(): Result<PosixTimeZone<Abbreviation>, Error> {
        val (timeZone, remaining) = when (val tryResult = this.parsePrefix()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTimeZone<Abbreviation>, Error>(tryResult.errValue()) }
        if (!remaining.isEmpty()) {
            return Result.Err<PosixTimeZone<Abbreviation>, Error>(err("expected entire TZ string to be a valid POSIX time zone, but found `{}` after what would otherwise be a valid POSIX TZ string", Bytes(remaining)))
        }
        return Result.Ok<PosixTimeZone<Abbreviation>, Error>(timeZone)
    }

    /**
     * Parses a POSIX time zone from the current position of the parser and
     * returns the remaining input.
     */
    public open fun parsePrefix(): Result<Pair<PosixTimeZone<Abbreviation>, List<UByte>>, Error> {
        val timeZone = when (val tryResult = this.parsePosixTimeZone()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<PosixTimeZone<Abbreviation>, List<UByte>>, Error>(tryResult.errValue()) }
        return Result.Ok<Pair<PosixTimeZone<Abbreviation>, List<UByte>>, Error>(Pair(timeZone, this.remaining()))
    }

    /**
     * Parse a POSIX time zone from the current position of the parser.
     *
     * Upon success, the parser will be positioned immediately following the
     * TZ string.
     */
    public open fun parsePosixTimeZone(): Result<PosixTimeZone<Abbreviation>, Error> {
        val stdAbbrev = when (val tryResult = this.parseAbbreviation().mapErr({ e -> err("failed to parse standard abbreviation: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTimeZone<Abbreviation>, Error>(tryResult.errValue()) }
        val stdOffset = when (val tryResult = this.parsePosixOffset().mapErr({ e -> err("failed to parse standard offset: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTimeZone<Abbreviation>, Error>(tryResult.errValue()) }
        var dst = null
        if (!this.isDone() && (this.byte().isAsciiAlphabetic() || this.byte() == 60u)) {
            dst = when (val tryResult = this.parsePosixDst(stdOffset)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTimeZone<Abbreviation>, Error>(tryResult.errValue()) }
        }
        return Result.Ok<PosixTimeZone<Abbreviation>, Error>(PosixTimeZone(stdAbbrev = stdAbbrev, stdOffset = stdOffset, dst = dst))
    }

    /**
     * Parse a DST zone with an optional explicit transition rule.
     *
     * This assumes the parser is positioned at the first byte of the DST
     * abbreviation.
     *
     * Upon success, the parser will be positioned immediately after the end
     * of the DST transition rule (which might just be the abbreviation, but
     * might also include explicit start/end datetime specifications).
     */
    public open fun parsePosixDst(stdOffset: PosixOffset): Result<PosixDst<Abbreviation>, Error> {
        val abbrev = when (val tryResult = this.parseAbbreviation().mapErr({ e -> err("failed to parse DST abbreviation: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixDst<Abbreviation>, Error>(tryResult.errValue()) }
        if (this.isDone()) {
            return Result.Err<PosixDst<Abbreviation>, Error>(err("found DST abbreviation `{abbrev}`, but no transition rule (this is technically allowed by POSIX, but has unspecified behavior)"))
        }
        // This is the default: one hour ahead of standard time. We may
        // override this if the DST portion specifies an offset. (But it
        // usually doesn't.)
        var offset = PosixOffset(second = stdOffset.second + 3600)
        if (this.byte() != 44u) {
            offset = when (val tryResult = this.parsePosixOffset().mapErr({ e -> err("failed to parse DST offset: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixDst<Abbreviation>, Error>(tryResult.errValue()) }
            if (this.isDone()) {
                return Result.Err<PosixDst<Abbreviation>, Error>(err("found DST abbreviation `{abbrev}` and offset `{offset}s`, but no transition rule (this is technically allowed by POSIX, but has unspecified behavior)", offset = offset.second))
            }
        }
        if (this.byte() != 44u) {
            return Result.Err<PosixDst<Abbreviation>, Error>(err("after parsing DST offset in POSIX time zone string, found `{}` but expected a ','", Byte(this.byte())))
        }
        if (!this.bump()) {
            return Result.Err<PosixDst<Abbreviation>, Error>(err("after parsing DST offset in POSIX time zone string, found end of string after a trailing ','"))
        }
        val rule = when (val tryResult = this.parseRule()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixDst<Abbreviation>, Error>(tryResult.errValue()) }
        return Result.Ok<PosixDst<Abbreviation>, Error>(PosixDst(abbrev = abbrev, offset = offset, rule = rule))
    }

    /**
     * Parse a time zone abbreviation.
     *
     * This assumes the parser is positioned at the first byte of
     * the abbreviation. This is either the first character in the
     * abbreviation, or the opening quote of a quoted abbreviation.
     *
     * Upon success, the parser will be positioned immediately following
     * the abbreviation name.
     *
     * The string returned is guaranteed to be no more than 30 bytes.
     * (This restriction is somewhat arbitrary, but it's so we can put
     * the abbreviation in a fixed capacity array.)
     */
    public open fun parseAbbreviation(): Result<Abbreviation, Error> {
        return if (this.byte() == 60u) {
            if (!this.bump()) {
                return Result.Err<Abbreviation, Error>(err("found opening '<' quote for abbreviation in POSIX time zone string, and expected a name following it, but found the end of string instead"))
            }
            this.parseQuotedAbbreviation()
        } else {
            this.parseUnquotedAbbreviation()
        }
    }

    /**
     * Parses an unquoted time zone abbreviation.
     *
     * This assumes the parser is position at the first byte in the
     * abbreviation.
     *
     * Upon success, the parser will be positioned immediately after the
     * last byte in the abbreviation.
     *
     * The string returned is guaranteed to be no more than 30 bytes.
     * (This restriction is somewhat arbitrary, but it's so we can put
     * the abbreviation in a fixed capacity array.)
     */
    public open fun parseUnquotedAbbreviation(): Result<Abbreviation, Error> {
        val start = this.pos()
        for (i in 0 until Int.MAX_VALUE) {
            if (!this.byte().isAsciiAlphabetic()) {
                break
            }
            if (i >= Abbreviation.capacity()) {
                return Result.Err<Abbreviation, Error>(err("expected abbreviation with at most {} bytes, but found a longer abbreviation beginning with `{}`", Abbreviation.capacity(), Bytes(((this.tz).subList(start, i)))))
            }
            if (!this.bump()) {
                break
            }
        }
        val end = this.pos()
        val abbrev =
            when (val tryResult = String.fromUtf8(((this.tz).subList(start, end))).mapErr({
                // NOTE: I believe this error is technically impossible
                // since the loop above restricts letters in an
                // abbreviation to ASCII. So everything from `start` to
                // `end` is ASCII and thus should be UTF-8. But it doesn't
                // cost us anything to report an error here in case the
                // code above evolves somehow.
                err("found abbreviation `{}`, but it is not valid UTF-8", Bytes(((this.tz).subList(start, end))))
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Abbreviation, Error>(tryResult.errValue()) }
        if (abbrev.len() < 3) {
            return Result.Err<Abbreviation, Error>(err("expected abbreviation with 3 or more bytes, but found abbreviation {:?} with {} bytes", abbrev, abbrev.len()))
        }
        // OK because we verified above that the abbreviation
        // does not exceed `Abbreviation.capacity`.
        return Result.Ok<Abbreviation, Error>(Abbreviation.new(abbrev).unwrap())
    }

    /**
     * Parses a quoted time zone abbreviation.
     *
     * This assumes the parser is positioned immediately after the opening
     * `<` quote. That is, at the first byte in the abbreviation.
     *
     * Upon success, the parser will be positioned immediately after the
     * closing `>` quote.
     *
     * The string returned is guaranteed to be no more than 30 bytes.
     * (This restriction is somewhat arbitrary, but it's so we can put
     * the abbreviation in a fixed capacity array.)
     */
    public open fun parseQuotedAbbreviation(): Result<Abbreviation, Error> {
        val start = this.pos()
        for (i in 0 until Int.MAX_VALUE) {
            if (!this.byte().isAsciiAlphanumeric() && this.byte() != 43u && this.byte() != 45u) {
                break
            }
            if (i >= Abbreviation.capacity()) {
                return Result.Err<Abbreviation, Error>(err("expected abbreviation with at most {} bytes, but found a longer abbreviation beginning with `{}`", Abbreviation.capacity(), Bytes(((this.tz).subList(start, i)))))
            }
            if (!this.bump()) {
                break
            }
        }
        val end = this.pos()
        val abbrev =
            when (val tryResult = String.fromUtf8(((this.tz).subList(start, end))).mapErr({
                // NOTE: I believe this error is technically impossible
                // since the loop above restricts letters in an
                // abbreviation to ASCII. So everything from `start` to
                // `end` is ASCII and thus should be UTF-8. But it doesn't
                // cost us anything to report an error here in case the
                // code above evolves somehow.
                err("found abbreviation `{}`, but it is not valid UTF-8", Bytes(((this.tz).subList(start, end))))
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Abbreviation, Error>(tryResult.errValue()) }
        if (this.isDone()) {
            return Result.Err<Abbreviation, Error>(err("found non-empty quoted abbreviation {abbrev:?}, but did not find expected end-of-quoted abbreviation '>' character"))
        }
        if (this.byte() != 62u) {
            return Result.Err<Abbreviation, Error>(err("found non-empty quoted abbreviation {abbrev:?}, but found `{}` instead of end-of-quoted abbreviation '>' character", Byte(this.byte())))
        }
        this.bump()
        if (abbrev.len() < 3) {
            return Result.Err<Abbreviation, Error>(err("expected abbreviation with 3 or more bytes, but found abbreviation {abbrev:?} with {} bytes", abbrev.len()))
        }
        // OK because we verified above that the abbreviation
        // does not exceed `Abbreviation.capacity`.
        return Result.Ok<Abbreviation, Error>(Abbreviation.new(abbrev).unwrap())
    }

    /**
     * Parse a POSIX time offset.
     *
     * This assumes the parser is positioned at the first byte of the
     * offset. This can either be a digit (for a positive offset) or the
     * sign of the offset (which must be either `-` or `+`).
     *
     * Upon success, the parser will be positioned immediately after the
     * end of the offset.
     */
    public open fun parsePosixOffset(): Result<PosixOffset, Error> {
        val sign =
            when (val tryResult = this.parseOptionalSign().mapErr({ e ->
                err("failed to parse sign for time offset in POSIX time zone string: {e}")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixOffset, Error>(tryResult.errValue()) }.unwrapOr(1)
        val hour = when (val tryResult = this.parseHourPosix()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixOffset, Error>(tryResult.errValue()) }
        var (minute, second) = Pair(0, 0)
        if (this.maybeByte() == 58u) {
            if (!this.bump()) {
                return Result.Err<PosixOffset, Error>(err("incomplete time in POSIX timezone (missing minutes)"))
            }
            minute = when (val tryResult = this.parseMinute()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixOffset, Error>(tryResult.errValue()) }
            if (this.maybeByte() == 58u) {
                if (!this.bump()) {
                    return Result.Err<PosixOffset, Error>(err("incomplete time in POSIX timezone (missing seconds)"))
                }
                second = when (val tryResult = this.parseSecond()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixOffset, Error>(tryResult.errValue()) }
            }
        }
        val offset = PosixOffset(second = Int.from(hour) * 3600)
        offset.second += Int.from(minute) * 60
        offset.second += Int.from(second)
        // Yes, we flip the sign, because POSIX is backwards.
        // For example, `EST5` corresponds to `- 05: 00`.
        offset.second *= Int.from(-sign)
        // Must be true because the parsing routines for hours, minutes
        // and seconds enforce they are in the ranges -24..=24, 0..=59
        // and 0..=59, respectively.
        check(-89999 <= offset.second && offset.second <= 89999) { "POSIX offset seconds ${offset.second} is out of range" }
        return Result.Ok<PosixOffset, Error>(offset)
    }

    /**
     * Parses a POSIX DST transition rule.
     *
     * This assumes the parser is positioned at the first byte in the
     * rule. That is, it comes immediately after the DST abbreviation or
     * its optional offset.
     *
     * Upon success, the parser will be positioned immediately after the
     * DST transition rule. In typical cases, this corresponds to the end
     * of the TZ string.
     */
    public open fun parseRule(): Result<PosixRule, Error> {
        val start =
            when (val tryResult = this.parsePosixDatetime().mapErr({ e ->
                err("failed to parse start of DST transition rule: {e}")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixRule, Error>(tryResult.errValue()) }
        if (this.maybeByte() != 44u || !this.bump()) {
            return Result.Err<PosixRule, Error>(err("expected end of DST rule after parsing the start of the DST rule"))
        }
        val end =
            when (val tryResult = this.parsePosixDatetime().mapErr({ e ->
                err("failed to parse end of DST transition rule: {e}")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixRule, Error>(tryResult.errValue()) }
        return Result.Ok<PosixRule, Error>(PosixRule(start = start, end = end))
    }

    /**
     * Parses a POSIX datetime specification.
     *
     * This assumes the parser is position at the first byte where a
     * datetime specification is expected to occur.
     *
     * Upon success, the parser will be positioned after the datetime
     * specification. This will either be immediately after the date, or
     * if it's present, the time part of the specification.
     */
    public open fun parsePosixDatetime(): Result<PosixDayTime, Error> {
        val daytime = PosixDayTime(date = when (val tryResult = this.parsePosixDate()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixDayTime, Error>(tryResult.errValue()) }, time = PosixTime.DEFAULT)
        if (this.maybeByte() != 47u) {
            return Result.Ok<PosixDayTime, Error>(daytime)
        }
        if (!this.bump()) {
            return Result.Err<PosixDayTime, Error>(err("expected time specification after '/' following a date\n                 specification in a POSIX time zone DST transition rule"))
        }
        daytime.time = when (val tryResult = this.parsePosixTime()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixDayTime, Error>(tryResult.errValue()) }
        return Result.Ok<PosixDayTime, Error>(daytime)
    }

    /**
     * Parses a POSIX date specification.
     *
     * This assumes the parser is positioned at the first byte of the date
     * specification. This can be `J` (for one based Julian day without
     * leap days), `M` (for "weekday of month") or a digit starting the
     * zero based Julian day with leap days. This routine will validate
     * that the position points to one of these possible values. That is,
     * the caller doesn't need to parse the `M` or the `J` or the leading
     * digit. The caller should just call this routine when it *expect* a
     * date specification to follow.
     *
     * Upon success, the parser will be positioned immediately after the
     * date specification.
     */
    public open fun parsePosixDate(): Result<PosixDay, Error> {
        return when (this.byte()) {
            74u -> {
                if (!this.bump()) {
                    return Result.Err<PosixDay, Error>(err("expected one-based Julian day after 'J' in date specification of a POSIX time zone DST transition rule, but got the end of the string instead"))
                }
                Result.Ok<PosixDay, Error>(PosixDay.JulianOne(when (val tryResult = this.parsePosixJulianDayNoLeap()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixDay, Error>(tryResult.errValue()) }))
            }
            in 48u..57u -> Result.Ok<PosixDay, Error>(PosixDay.JulianZero(when (val tryResult = this.parsePosixJulianDayWithLeap()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixDay, Error>(tryResult.errValue()) }))
            77u -> {
                if (!this.bump()) {
                    return Result.Err<PosixDay, Error>(err("expected month-week-weekday after 'M' in date specification of a POSIX time zone DST transition rule, but got the end of the string instead"))
                }
                val (month, week, weekday) = when (val tryResult = this.parseWeekdayOfMonth()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixDay, Error>(tryResult.errValue()) }
                Result.Ok<PosixDay, Error>(PosixDay.WeekdayOfMonth(month = month, week = week, weekday = weekday))
            }
            else -> Result.Err<PosixDay, Error>(err("expected 'J', a digit or 'M' at the beginning of a date specification of a POSIX time zone DST transition rule, but got `{}` instead", Byte(this.byte())))
        }
    }

    /**
     * Parses a POSIX Julian day that does not include leap days
     * (`1 <= n <= 365`).
     *
     * This assumes the parser is positioned just after the `J` and at the
     * first digit of the Julian day. Upon success, the parser will be
     * positioned immediately following the day number.
     */
    public open fun parsePosixJulianDayNoLeap(): Result<Short, Error> {
        val number = when (val tryResult = this.parseNumberWithUptoNDigits(3).mapErr({ e -> err("invalid one based Julian day: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Short, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Short.tryFrom(number).mapErr({
                err("one based Julian day `{number}` in POSIX time zone does not fit into 16-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Short, Error>(tryResult.errValue()) }
        if (!(1 <= number1 && number1 <= 365)) {
            return Result.Err<Short, Error>(err("parsed one based Julian day `{number}`, but one based Julian day in POSIX time zone must be in range 1..=365"))
        }
        return Result.Ok<Short, Error>(number1)
    }

    /**
     * Parses a POSIX Julian day that includes leap days (`0 <= n <=
     * 365`).
     *
     * This assumes the parser is positioned at the first digit of the
     * Julian day. Upon success, the parser will be positioned immediately
     * following the day number.
     */
    public open fun parsePosixJulianDayWithLeap(): Result<Short, Error> {
        val number = when (val tryResult = this.parseNumberWithUptoNDigits(3).mapErr({ e -> err("invalid zero based Julian day: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Short, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Short.tryFrom(number).mapErr({
                err("zero based Julian day `{number}` in POSIX time zone does not fit into 16-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Short, Error>(tryResult.errValue()) }
        if (!(0 <= number1 && number1 <= 365)) {
            return Result.Err<Short, Error>(err("parsed zero based Julian day `{number}`, but zero based Julian day in POSIX time zone must be in range 0..=365"))
        }
        return Result.Ok<Short, Error>(number1)
    }

    /**
     * Parses a POSIX "weekday of month" specification.
     *
     * This assumes the parser is positioned just after the `M` byte and
     * at the first digit of the month. Upon success, the parser will be
     * positioned immediately following the "weekday of the month" that
     * was parsed.
     *
     * The tuple returned is month (1..=12), week (1..=5) and weekday
     * (0..=6 with 0=Sunday).
     */
    public open fun parseWeekdayOfMonth(): Result<Triple<Byte, Byte, Byte>, Error> {
        val month = when (val tryResult = this.parseMonth()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Triple<Byte, Byte, Byte>, Error>(tryResult.errValue()) }
        if (this.maybeByte() != 46u) {
            return Result.Err<Triple<Byte, Byte, Byte>, Error>(err("expected '.' after month `{month}` in POSIX time zone rule"))
        }
        if (!this.bump()) {
            return Result.Err<Triple<Byte, Byte, Byte>, Error>(err("expected week after month `{month}` in POSIX time zone rule"))
        }
        val week = when (val tryResult = this.parseWeek()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Triple<Byte, Byte, Byte>, Error>(tryResult.errValue()) }
        if (this.maybeByte() != 46u) {
            return Result.Err<Triple<Byte, Byte, Byte>, Error>(err("expected '.' after week `{week}` in POSIX time zone rule"))
        }
        if (!this.bump()) {
            return Result.Err<Triple<Byte, Byte, Byte>, Error>(err("expected day-of-week after week `{week}` in POSIX time zone rule"))
        }
        val weekday = when (val tryResult = this.parseWeekday()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Triple<Byte, Byte, Byte>, Error>(tryResult.errValue()) }
        return Result.Ok<Triple<Byte, Byte, Byte>, Error>(Triple(month, week, weekday))
    }

    /**
     * This parses a POSIX time specification in the format
     * `[+ / -] hh[: mm[: ss]]`.
     *
     * This assumes the parser is positioned at the first `h` (or the
     * sign, if present). Upon success, the parser will be positioned
     * immediately following the end of the time specification.
     */
    public open fun parsePosixTime(): Result<PosixTime, Error> {
        val (sign1, hour1) =
            if (this.ianav3plus) {
                val sign =
                    when (val tryResult = this.parseOptionalSign().mapErr({ e ->
                        err("failed to parse sign for transition time in POSIX time zone string: {e}")
                    })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTime, Error>(tryResult.errValue()) }.unwrapOr(1)
                val hour = when (val tryResult = this.parseHourIanav3plus()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTime, Error>(tryResult.errValue()) }
                Pair(sign, hour)
            } else {
                Pair(1, Short.from(when (val tryResult = this.parseHourPosix()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTime, Error>(tryResult.errValue()) }))
            }
        var (minute, second) = Pair(0, 0)
        if (this.maybeByte() == 58u) {
            if (!this.bump()) {
                return Result.Err<PosixTime, Error>(err("incomplete transition time in POSIX time zone string (missing minutes)"))
            }
            minute = when (val tryResult = this.parseMinute()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTime, Error>(tryResult.errValue()) }
            if (this.maybeByte() == 58u) {
                if (!this.bump()) {
                    return Result.Err<PosixTime, Error>(err("incomplete transition time in POSIX time zone string (missing seconds)"))
                }
                second = when (val tryResult = this.parseSecond()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<PosixTime, Error>(tryResult.errValue()) }
            }
        }
        val time = PosixTime(second = Int.from(hour1) * 3600)
        time.second += Int.from(minute) * 60
        time.second += Int.from(second)
        time.second *= Int.from(sign1)
        // Must be true because the parsing routines for hours, minutes
        // and seconds enforce they are in the ranges -167..=167, 0..=59
        // and 0..=59, respectively.
        check(-604799 <= time.second && time.second <= 604799) { "POSIX time seconds ${time.second} is out of range" }
        return Result.Ok<PosixTime, Error>(time)
    }

    /**
     * Parses a month.
     *
     * This is expected to be positioned at the first digit. Upon success,
     * the parser will be positioned after the month (which may contain
     * two digits).
     */
    public open fun parseMonth(): Result<Byte, Error> {
        val number = when (val tryResult = this.parseNumberWithUptoNDigits(2)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Byte.tryFrom(number).mapErr({
                err("month `{number}` in POSIX time zone does not fit into 8-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        if (!(1 <= number1 && number1 <= 12)) {
            return Result.Err<Byte, Error>(err("parsed month `{number}`, but month in POSIX time zone must be in range 1..=12"))
        }
        return Result.Ok<Byte, Error>(number1)
    }

    /**
     * Parses a week-of-month number.
     *
     * This is expected to be positioned at the first digit. Upon success,
     * the parser will be positioned after the week digit.
     */
    public open fun parseWeek(): Result<Byte, Error> {
        val number = when (val tryResult = this.parseNumberWithExactlyNDigits(1)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Byte.tryFrom(number).mapErr({
                err("week `{number}` in POSIX time zone does not fit into 8-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        if (!(1 <= number1 && number1 <= 5)) {
            return Result.Err<Byte, Error>(err("parsed week `{number}`, but week in POSIX time zone must be in range 1..=5"))
        }
        return Result.Ok<Byte, Error>(number1)
    }

    /**
     * Parses a weekday number.
     *
     * This is expected to be positioned at the first digit. Upon success,
     * the parser will be positioned after the week digit.
     *
     * The weekday returned is guaranteed to be in the range `0 ..= 6`, with
     * `0` corresponding to Sunday.
     */
    public open fun parseWeekday(): Result<Byte, Error> {
        val number = when (val tryResult = this.parseNumberWithExactlyNDigits(1)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Byte.tryFrom(number).mapErr({
                err("weekday `{number}` in POSIX time zone does not fit into 8-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        if (!(0 <= number1 && number1 <= 6)) {
            return Result.Err<Byte, Error>(err("parsed weekday `{number}`, but weekday in POSIX time zone must be in range `0..=6` (with `0` corresponding to Sunday)"))
        }
        return Result.Ok<Byte, Error>(number1)
    }

    /**
     * Parses an hour from a POSIX time specification with the IANA
     * v3+ extension. That is, the hour may be in the range `0 ..= 167`.
     * (Callers should parse an optional sign preceding the hour digits
     * when IANA V3+ parsing is enabled.)
     *
     * The hour is allowed to be a single digit (unlike minutes or
     * seconds).
     *
     * This assumes the parser is positioned at the position where the
     * first hour digit should occur. Upon success, the parser will be
     * positioned immediately after the last hour digit.
     */
    public open fun parseHourIanav3plus(): Result<Short, Error> {
        // Callers should only be using this method when IANA v3+ parsing
        // is enabled.
        check(this.ianav3plus)
        val number = when (val tryResult = this.parseNumberWithUptoNDigits(3).mapErr({ e -> err("invalid hour digits: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Short, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Short.tryFrom(number).mapErr({
                err("hour `{number}` in POSIX time zone does not fit into 16-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Short, Error>(tryResult.errValue()) }
        if (!(0 <= number1 && number1 <= 167)) {
            // The error message says -167 but the check above uses 0.
            // This is because the caller is responsible for parsing
            // the sign.
            return Result.Err<Short, Error>(err("parsed hour `{number}`, but hour in IANA v3+ POSIX time zone must be in range `-167..=167`"))
        }
        return Result.Ok<Short, Error>(number1)
    }

    /**
     * Parses an hour from a POSIX time specification, with the allowed
     * range being `0 ..= 24`.
     *
     * The hour is allowed to be a single digit (unlike minutes or
     * seconds).
     *
     * This assumes the parser is positioned at the position where the
     * first hour digit should occur. Upon success, the parser will be
     * positioned immediately after the last hour digit.
     */
    public open fun parseHourPosix(): Result<Byte, Error> {
        val number = when (val tryResult = this.parseNumberWithUptoNDigits(2).mapErr({ e -> err("invalid hour digits: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Byte.tryFrom(number).mapErr({
                err("hour `{number}` in POSIX time zone does not fit into 8-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        if (!(0 <= number1 && number1 <= 24)) {
            return Result.Err<Byte, Error>(err("parsed hour `{number}`, but hour in POSIX time zone must be in range `0..=24`"))
        }
        return Result.Ok<Byte, Error>(number1)
    }

    /**
     * Parses a minute from a POSIX time specification.
     *
     * The minute must be exactly two digits.
     *
     * This assumes the parser is positioned at the position where the
     * first minute digit should occur. Upon success, the parser will be
     * positioned immediately after the second minute digit.
     */
    public open fun parseMinute(): Result<Byte, Error> {
        val number = when (val tryResult = this.parseNumberWithExactlyNDigits(2).mapErr({ e -> err("invalid minute digits: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Byte.tryFrom(number).mapErr({
                err("minute `{number}` in POSIX time zone does not fit into 8-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        if (!(0 <= number1 && number1 <= 59)) {
            return Result.Err<Byte, Error>(err("parsed minute `{number}`, but minute in POSIX time zone must be in range `0..=59`"))
        }
        return Result.Ok<Byte, Error>(number1)
    }

    /**
     * Parses a second from a POSIX time specification.
     *
     * The second must be exactly two digits.
     *
     * This assumes the parser is positioned at the position where the
     * first second digit should occur. Upon success, the parser will be
     * positioned immediately after the second second digit.
     */
    public open fun parseSecond(): Result<Byte, Error> {
        val number = when (val tryResult = this.parseNumberWithExactlyNDigits(2).mapErr({ e -> err("invalid second digits: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        val number1 =
            when (val tryResult = Byte.tryFrom(number).mapErr({
                err("second `{number}` in POSIX time zone does not fit into 8-bit integer")
            })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Byte, Error>(tryResult.errValue()) }
        if (!(0 <= number1 && number1 <= 59)) {
            return Result.Err<Byte, Error>(err("parsed second `{number}`, but second in POSIX time zone must be in range `0..=59`"))
        }
        return Result.Ok<Byte, Error>(number1)
    }

    /**
     * Parses a signed 64-bit integer expressed in exactly `n` digits.
     *
     * If `n` digits could not be found (or if the `TZ` string ends before
     * `n` digits could be found), then this returns an error.
     *
     * This assumes that `n >= 1` and that the parser is positioned at the
     * first digit. Upon success, the parser is positioned immediately
     * after the `n`th digit.
     */
    public open fun parseNumberWithExactlyNDigits(n: Int): Result<Int, Error> {
        check(n >= 1) { "numbers must have at least 1 digit" }
        val start = this.pos()
        var number: Int = 0
        for (i in 0 until n) {
            if (this.isDone()) {
                return Result.Err<Int, Error>(err("expected {n} digits, but found {i}"))
            }
            val byte = this.byte()
            val digit2 =
                run {
                    val matchVal = byte.checkedSub(48u)
                    when {
                        matchVal == null -> {
                            return Result.Err<Int, Error>(err("invalid digit, expected 0-9 but got {}", Byte(byte)))
                        }
                        matchVal != null && matchVal > 9 -> {
                            val digit = matchVal
                            return Result.Err<Int, Error>(err("invalid digit, expected 0-9 but got {}", Byte(byte)))
                        }
                        else -> {
                            val digit1 = matchVal
                            check((0..9).contains(digit1))
                            Int.from(digit1)
                        }
                    }
                }
            number = when (val tryResult = (number.checkedMul(10)?.let({ n1 -> n1.checkedAdd(digit2) })?.let { Result.Ok(it) } ?: Result.Err(err("number `{}` too big to parse into 64-bit integer", Bytes(((this.tz).subList(start, i))))))) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
            this.bump()
        }
        return Result.Ok<Int, Error>(number)
    }

    /**
     * Parses a signed 64-bit integer expressed with up to `n` digits and
     * at least 1 digit.
     *
     * This assumes that `n >= 1` and that the parser is positioned at the
     * first digit. Upon success, the parser is position immediately after
     * the last digit (which can be at most `n`).
     */
    public open fun parseNumberWithUptoNDigits(n: Int): Result<Int, Error> {
        check(n >= 1) { "numbers must have at least 1 digit" }
        val start = this.pos()
        var number: Int = 0
        for (i in 0 until n) {
            if (this.isDone() || !this.byte().isAsciiDigit()) {
                if (i == 0) {
                    return Result.Err<Int, Error>(err("invalid number, no digits found"))
                }
                break
            }
            val digit = Int.from(this.byte() - 48u)
            number = when (val tryResult = (number.checkedMul(10)?.let({ n1 -> n1.checkedAdd(digit) })?.let { Result.Ok(it) } ?: Result.Err(err("number `{}` too big to parse into 64-bit integer", Bytes(((this.tz).subList(start, i))))))) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
            this.bump()
        }
        return Result.Ok<Int, Error>(number)
    }

    /**
     * Parses an optional sign.
     *
     * This assumes the parser is positioned at the position where a
     * positive or negative sign is permitted. If one exists, then it
     * is consumed and returned. Moreover, if one exists, then this
     * guarantees that it is not the last byte in the input. That is, upon
     * success, it is valid to call `self.byte()`.
     */
    public open fun parseOptionalSign(): Result<Byte?, Error> {
        if (this.isDone()) {
            return Result.Ok<Byte?, Error>(null)
        }
        return Result.Ok<Byte?, Error>(when (this.byte()) {
            45u -> {
                if (!this.bump()) {
                    return Result.Err<Byte?, Error>(err("expected digit after '-' sign, but got end of input"))
                }
                -1
            }
            43u -> {
                if (!this.bump()) {
                    return Result.Err<Byte?, Error>(err("expected digit after '+' sign, but got end of input"))
                }
                1
            }
            else -> null
        })
    }

    /**
     * Helper routines for parsing a POSIX `TZ` string.
     */
    /**
     * Bump the parser to the next byte.
     *
     * If the end of the input has been reached, then `false` is returned.
     */
    public open fun bump(): Boolean {
        if (this.isDone()) {
            return false
        }
        this.pos.set((this.pos().checkedAdd(1) ?: error("pos cannot overflow usize")))
        return !this.isDone()
    }

    /**
     * Returns true if the next call to `bump` would return false.
     */
    public open fun isDone(): Boolean = this.pos() == this.tz.size

    /**
     * Return the byte at the current position of the parser.
     *
     * This panics if the parser is positioned at the end of the TZ
     * string.
     */
    public open fun byte(): UByte = this.tz[this.pos()]

    /**
     * Return the byte at the current position of the parser. If the TZ
     * string has been exhausted, then this returns `None`.
     */
    public open fun maybeByte(): UByte? = this.tz.get(this.pos())

    /**
     * Return the current byte offset of the parser.
     *
     * The offset starts at `0` from the beginning of the TZ string.
     */
    public open fun pos(): Int = this.pos.get()

    /**
     * Returns the remaining bytes of the TZ string.
     *
     * This includes `self.byte()`. It may be empty.
     */
    public open fun remaining(): List<UByte> = ((this.tz).subList(this.pos(), (this.tz).size))

    public companion object {
        public operator fun invoke(
            tz: List<UByte>,
            pos: Cell<Int>,
            ianav3plus: Boolean,
        ): Parser = Parser(
            tz = tz,
            posCtorArg = pos,
            ianav3plus = ianav3plus,
        )

        /**
         * Create a new parser for extracting a POSIX time zone from the given
         * bytes.
         */
        public fun new(tz: List<UByte>): Parser = Parser(tz = tz, pos = Cell.new(0), ianav3plus = false)
    }
}
