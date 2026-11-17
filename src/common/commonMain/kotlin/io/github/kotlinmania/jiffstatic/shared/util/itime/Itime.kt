// port-lint: source shared/util/itime.rs
package io.github.kotlinmania.jiffstatic.shared.util.itime

import io.github.kotlinmania.jiffstatic.shared.util.error.Error
import io.github.kotlinmania.jiffstatic.shared.util.error.err
import io.github.kotlinmania.rustlang.core.cmp.max
import io.github.kotlinmania.rustlang.core.cmp.min
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout
import io.github.kotlinmania.rustlang.core.num.checkedAdd
import io.github.kotlinmania.rustlang.core.num.divEuclid
import io.github.kotlinmania.rustlang.core.num.remEuclid
import io.github.kotlinmania.rustlang.core.ptr.writeBytes
import io.github.kotlinmania.rustlang.core.result.Result
import io.github.kotlinmania.rustlang.core.result.unwrapOrElse

// This module defines the internal core time data types.
//
// This includes physical time (i.e., a timestamp) and civil time.
//
// These types exist to provide a home for the core algorithms in a datetime
// crate. For example, converting from a timestamp to a Gregorian calendar date
// and clock time.
//
// These routines are specifically implemented on simple primitive integer types
// and implicitly assume that the inputs are valid (i.e., within Jiff's minimum
// and maximum ranges).
//
// These exist to provide `const` capabilities, and also to provide a small
// reusable core of important algorithms that can be shared between `jiff` and
// `jiff - static`.
//
// # Naming
//
// The types in this module are prefixed with letter `I` to make it clear that
// they are internal types. Specifically, to distinguish them from Jiff's public
// types. For example, `Date` versus `IDate`.

public open class ITimestamp(
    public var second: Long,
    public var nanosecond: Int,
) {
    public open fun clone(): ITimestamp = ITimestamp(second, nanosecond)

    /**
     * Converts a Unix timestamp with an offset to a Gregorian datetime.
     *
     * The offset should correspond to the number of seconds required to
     * add to this timestamp to get the local time.
     */
    public open fun toDatetime(offset: IOffset): IDateTime {
        var second = this.second
        var nanosecond = this.nanosecond
        second += offset.second.toLong()
        var epochDay = second.divEuclid(86400L).toInt()
        second = second.remEuclid(86400L)
        if (nanosecond < 0) {
            if (second > 0L) {
                second -= 1L
                nanosecond += 1000000000
            } else {
                epochDay -= 1
                second += 86399L
                nanosecond += 1000000000
            }
        }
        val date = IEpochDay(epochDay = epochDay).toDate()
        var time = ITimeSecond(second = second.toInt()).toTime()
        time.subsecNanosecond = nanosecond
        return IDateTime(date = date, time = time)
    }

    public companion object {
        public fun typeLayout(): TypeLayout<ITimestamp> =
            object : TypeLayout<ITimestamp> {
                override val alignBytes: Long = 8L
                override val sizeBytes: Long = 16L
                override fun readNative(addr: Long): ITimestamp =
                    ITimestamp(
                        (PrimitiveLayouts.I64).readNative(addr),
                        (PrimitiveLayouts.I32).readNative(addr + 8L),
                    )
                override fun writeNative(addr: Long, value: ITimestamp) {
                    (PrimitiveLayouts.I64).writeNative(addr, value.second)
                    (PrimitiveLayouts.I32).writeNative(addr + 8L, value.nanosecond)
                }
                override fun readBytes(buf: ByteArray, offset: Int): ITimestamp =
                    ITimestamp(
                        (PrimitiveLayouts.I64).readBytes(buf, offset),
                        (PrimitiveLayouts.I32).readBytes(buf, offset + 8),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: ITimestamp) {
                    (PrimitiveLayouts.I64).writeBytes(buf, offset, value.second)
                    (PrimitiveLayouts.I32).writeBytes(buf, offset + 8, value.nanosecond)
                }
            }

        public val MIN: ITimestamp = ITimestamp(second = -377705023201L, nanosecond = 0)

        public val MAX: ITimestamp = ITimestamp(second = 253402207200L, nanosecond = 999999999)

        /**
         * Creates an `ITimestamp` from a Unix timestamp in seconds.
         */
        public fun fromSecond(second: Long): ITimestamp = ITimestamp(second = second, nanosecond = 0)
    }
}

public open class IOffset(
    public var second: Int,
) {
    public open fun clone(): IOffset = IOffset(second)

    public companion object {
        public fun typeLayout(): TypeLayout<IOffset> =
            object : TypeLayout<IOffset> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 4L
                override fun readNative(addr: Long): IOffset =
                    IOffset(
                        (PrimitiveLayouts.I32).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: IOffset) {
                    (PrimitiveLayouts.I32).writeNative(addr, value.second)
                }
                override fun readBytes(buf: ByteArray, offset: Int): IOffset =
                    IOffset(
                        (PrimitiveLayouts.I32).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: IOffset) {
                    (PrimitiveLayouts.I32).writeBytes(buf, offset, value.second)
                }
            }

        public val UTC: IOffset = IOffset(second = 0)
    }
}

public open class IDateTime(
    public var date: IDate,
    public var time: ITime,
) {
    public open fun clone(): IDateTime = IDateTime(date, time)

    /**
     * Converts a Gregorian datetime and its offset to a Unix timestamp.
     *
     * The offset should correspond to the number of seconds required to
     * subtract from this datetime in order to get to UTC.
     */
    public open fun toTimestamp(offset: IOffset): ITimestamp {
        val epochDay = this.date.toEpochDay().epochDay
        var second = (epochDay.toLong()) * 86400L + ((this.time.toSecond().second).toLong())
        var nanosecond = this.time.subsecNanosecond
        second -= offset.second.toLong()
        if (epochDay < 0 && nanosecond != 0) {
            second += 1L
            nanosecond -= 1000000000
        }
        return ITimestamp(second = second, nanosecond = nanosecond)
    }

    /**
     * Converts a Gregorian datetime and its offset to a Unix timestamp.
     *
     * If the timestamp would overflow Jiff's timestamp range, then this
     * returns `None`.
     *
     * The offset should correspond to the number of seconds required to
     * subtract from this datetime in order to get to UTC.
     */
    public open fun toTimestampChecked(offset: IOffset): ITimestamp? {
        val ts = this.toTimestamp(offset)
        if (!(ITimestamp.MIN <= ts && ts <= ITimestamp.MAX)) {
            return null
        }
        return ts
    }

    public open fun saturatingAddSeconds(seconds: Int): IDateTime =
        this.checkedAddSeconds(seconds).unwrapOrElse({
            if (seconds < 0) {
                IDateTime.MIN
            } else {
                IDateTime.MAX
            }
        })

    public open fun checkedAddSeconds(seconds: Int): Result<IDateTime, Error> {
        val daySecond = when (val tryResult = ((this.time.toSecond().second).checkedAdd(seconds)?.let { Result.Ok<Int, Error>(it) } ?: Result.Err<Int, Error>(err("adding `{seconds}s` to datetime overflowed")))) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<IDateTime, Error>(tryResult.errValue()) }
        val days = daySecond.divEuclid(86400)
        val second = daySecond.remEuclid(86400)
        val date = when (val tryResult = this.date.checkedAddDays(days)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<IDateTime, Error>(tryResult.errValue()) }
        val time = ITimeSecond(second = second).toTime()
        return Result.Ok<IDateTime, Error>(IDateTime(date = date, time = time))
    }

    public companion object {
        public fun typeLayout(): TypeLayout<IDateTime> =
            object : TypeLayout<IDateTime> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 12L
                override fun readNative(addr: Long): IDateTime =
                    IDateTime(
                        (IDate.typeLayout()).readNative(addr),
                        (ITime.typeLayout()).readNative(addr + 4L),
                    )
                override fun writeNative(addr: Long, value: IDateTime) {
                    (IDate.typeLayout()).writeNative(addr, value.date)
                    (ITime.typeLayout()).writeNative(addr + 4L, value.time)
                }
                override fun readBytes(buf: ByteArray, offset: Int): IDateTime =
                    IDateTime(
                        (IDate.typeLayout()).readBytes(buf, offset),
                        (ITime.typeLayout()).readBytes(buf, offset + 4),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: IDateTime) {
                    (IDate.typeLayout()).writeBytes(buf, offset, value.date)
                    (ITime.typeLayout()).writeBytes(buf, offset + 4, value.time)
                }
            }

        public val MIN: IDateTime = IDateTime(date = IDate.MIN, time = ITime.MIN)

        public val MAX: IDateTime = IDateTime(date = IDate.MAX, time = ITime.MAX)
    }
}

public open class IEpochDay(
    public var epochDay: Int,
) {
    public open fun clone(): IEpochDay = IEpochDay(epochDay)

    // to mimic source

    /**
     * Converts days since the Unix epoch to a Gregorian date.
     *
     * This is Neri-Schneider. There's no branching or divisions.
     *
     * Ref: <https://github.com/cassioneri/eaf/blob/684d3cc32d14eee371d0abe4f683d6d6a49ed5c1/algorithms/neriSchneider.hpp#L40C3-L40C34>
     */
    public open fun toDate(): IDate {
        val L: UInt = 400u * s
        val year = ((Y - (L.toUInt())) + (J.toUInt())).toShort()
        val month =
            (if (J) {
                M - 12u
            } else {
                M
            }).toByte()
        val day = (D + 1u).toByte()
        return IDate(year = year, month = month, day = day)
    }

    /**
     * Returns the day of the week for this epoch day.
     */
    public open fun weekday(): IWeekday {
        // Based on Hinnant's approach here, although we use ISO weekday
        // numbering by default. Basically, this works by using the knowledge
        // that 1970-01-01 was a Thursday.
        //
        // Ref: http://howardhinnant.github.io/dateAlgorithms.html
        return IWeekday.fromMondayZeroOffset((this.epochDay + 3).remEuclid(7).toByte())
    }

    /**
     * Add the given number of days to this epoch day.
     *
     * If this would overflow an `Int` or result in an out-of-bounds epoch
     * day, then this returns an error.
     */
    public open fun checkedAdd(amount: Int): Result<IEpochDay, Error> {
        val epochDay = this.epochDay
        val sum = when (val tryResult = (epochDay.checkedAdd(amount)?.let { Result.Ok<Int, Error>(it) } ?: Result.Err<Int, Error>(err("adding `{amount}` to epoch day `{epoch_day}` overflowed i32")))) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<IEpochDay, Error>(tryResult.errValue()) }
        val ret = IEpochDay(epochDay = sum)
        if (!(IEpochDay.MIN <= ret && ret <= IEpochDay.MAX)) {
            return Result.Err<IEpochDay, Error>(err("adding `{amount}` to epoch day `{epoch_day}` resulted in `{sum}`, which is not in the required epoch day range of `{min}..={max}`", min = IEpochDay.MIN.epochDay, max = IEpochDay.MAX.epochDay))
        }
        return Result.Ok<IEpochDay, Error>(ret)
    }

    public companion object {
        public fun typeLayout(): TypeLayout<IEpochDay> =
            object : TypeLayout<IEpochDay> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 4L
                override fun readNative(addr: Long): IEpochDay =
                    IEpochDay(
                        (PrimitiveLayouts.I32).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: IEpochDay) {
                    (PrimitiveLayouts.I32).writeNative(addr, value.epochDay)
                }
                override fun readBytes(buf: ByteArray, offset: Int): IEpochDay =
                    IEpochDay(
                        (PrimitiveLayouts.I32).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: IEpochDay) {
                    (PrimitiveLayouts.I32).writeBytes(buf, offset, value.epochDay)
                }
            }

        public val MIN: IEpochDay = IEpochDay(epochDay = -4371587)

        public val MAX: IEpochDay = IEpochDay(epochDay = 2932896)
    }
}

public open class IDate(
    public var year: Short,
    public var month: Byte,
    public var day: Byte,
) {
    public open fun clone(): IDate = IDate(year, month, day)

    // to mimic source

    /**
     * Converts a Gregorian date to days since the Unix epoch.
     *
     * This is Neri-Schneider. There's no branching or divisions.
     *
     * Ref: https://github.com/cassioneri/eaf/blob/684d3cc32d14eee371d0abe4f683d6d6a49ed5c1/algorithms/neriSchneider.hpp#L83
     */
    public open fun toEpochDay(): IEpochDay {
        val month = this.month.toUInt()
        val m =
            if (J) {
                month + 12u
            } else {
                month
            }
        val epochDay = N_U.toInt()
        return IEpochDay(epochDay = epochDay)
    }

    /**
     * Returns the day of the week for this date.
     */
    public open fun weekday(): IWeekday = this.toEpochDay().weekday()

    /**
     * Returns the `nth` weekday of the month represented by this date.
     *
     * `nth` must be non-zero and otherwise in the range `- 5 ..= 5`. If it
     * isn't, an error is returned.
     *
     * This also returns an error if `abs(nth) == 5` and there is no "5th"
     * weekday of this month.
     */
    public open fun nthWeekdayOfMonth(nth: Byte, weekday: IWeekday): Result<IDate, Error> {
        if (nth == 0.toByte() || !((-5).toByte() <= nth && nth <= 5.toByte())) {
            return Result.Err<IDate, Error>(err("got nth weekday of `{nth}`, but must be non-zero and in range `-5..=5`"))
        }
        return if (nth > 0.toByte()) {
            val firstWeekday = this.firstOfMonth().weekday()
            val diff = weekday.since(firstWeekday)
            val day = diff + 1 + (nth - 1) * 7
            IDate.tryNew(this.year, this.month, day)
        } else {
            val last = this.lastOfMonth()
            val lastWeekday = last.weekday()
            val diff1 = lastWeekday.since(weekday)
            val day1 = last.day - diff1 - (nth.abs() - 1) * 7
            // Our math can go below 1 when nth is -5 and there is no "5th from
            // last" weekday in this month. Since this is outside the bounds
            // of `Day`, we can't let this boundary condition escape. So we
            // check it here.
            if (day1 < 1.toByte()) {
                return Result.Err<IDate, Error>(err("day={day} is out of range for year={year} and month={month}, must be in range 1..={max_day}", year = this.year, month = this.month, maxDay = daysInMonth(this.year, this.month)))
            }
            IDate.tryNew(this.year, this.month, day1)
        }
    }

    /**
     * Returns the day before this date.
     */
    public open fun yesterday(): Result<IDate, Error> {
        if (this.day == 1.toByte()) {
            if (this.month == 1.toByte()) {
                val year = this.year - 1
                if (year <= (-10000).toShort()) {
                    return Result.Err<IDate, Error>(err("returning yesterday for -9999-01-01 is not possible because it is less than Jiff's supported\n                         minimum date"))
                }
                return Result.Ok<IDate, Error>(IDate(year = year, month = 12, day = 31))
            }
            val month = this.month - 1
            val day = daysInMonth(this.year, this.month)
            return Result.Ok<IDate, Error>(this.copy(month = month, day = day))
        }
        return Result.Ok<IDate, Error>(this.copy(day = (this.day - 1).toByte()))
    }

    /**
     * Returns the day after this date.
     */
    public open fun tomorrow(): Result<IDate, Error> {
        if (this.day >= 28.toByte() && this.day == daysInMonth(this.year, this.month)) {
            if (this.month == 12.toByte()) {
                val year = this.year + 1
                if (year >= 10000.toShort()) {
                    return Result.Err<IDate, Error>(err("returning tomorrow for 9999-12-31 is not possible because it is greater than Jiff's supported\n                         maximum date"))
                }
                return Result.Ok<IDate, Error>(IDate(year = year, month = 1, day = 1))
            }
            val month = this.month + 1
            return Result.Ok<IDate, Error>(this.copy(month = month, day = 1))
        }
        return Result.Ok<IDate, Error>(this.copy(day = (this.day + 1).toByte()))
    }

    /**
     * Returns the year one year before this date.
     */
    public open fun prevYear(): Result<Short, Error> {
        val year = this.year - 1
        if (year <= (-10000).toShort()) {
            return Result.Err<Short, Error>(err("returning previous year for {year:04}-{month:02}-{day:02} is not possible because it is less than Jiff's supported minimum date", year = this.year, month = this.month, day = this.day))
        }
        return Result.Ok<Short, Error>(year)
    }

    /**
     * Returns the year one year from this date.
     */
    public open fun nextYear(): Result<Short, Error> {
        val year = this.year + 1
        if (year >= 10000.toShort()) {
            return Result.Err<Short, Error>(err("returning next year for {year:04}-{month:02}-{day:02} is not possible because it is greater than Jiff's supported maximum date", year = this.year, month = this.month, day = this.day))
        }
        return Result.Ok<Short, Error>(year)
    }

    /**
     * Add the number of days to this date.
     */
    public open fun checkedAddDays(amount: Int): Result<IDate, Error> =
        when (amount) {
            0 -> Result.Ok<IDate, Error>(this)
            -1 -> this.yesterday()
            1 -> this.tomorrow()
            else -> {
                val n = amount
                this.toEpochDay().checkedAdd(n)?.let({ d -> d.toDate() })
            }
        }

    public open fun firstOfMonth(): IDate = this.copy(day = 1)

    public open fun lastOfMonth(): IDate = this.copy(day = daysInMonth(this.year, this.month))

    public companion object {
        public fun typeLayout(): TypeLayout<IDate> =
            object : TypeLayout<IDate> {
                override val alignBytes: Long = 2L
                override val sizeBytes: Long = 4L
                override fun readNative(addr: Long): IDate =
                    IDate(
                        (PrimitiveLayouts.I16).readNative(addr),
                        (PrimitiveLayouts.I8).readNative(addr + 2L),
                        (PrimitiveLayouts.I8).readNative(addr + 3L),
                    )
                override fun writeNative(addr: Long, value: IDate) {
                    (PrimitiveLayouts.I16).writeNative(addr, value.year)
                    (PrimitiveLayouts.I8).writeNative(addr + 2L, value.month)
                    (PrimitiveLayouts.I8).writeNative(addr + 3L, value.day)
                }
                override fun readBytes(buf: ByteArray, offset: Int): IDate =
                    IDate(
                        (PrimitiveLayouts.I16).readBytes(buf, offset),
                        (PrimitiveLayouts.I8).readBytes(buf, offset + 2),
                        (PrimitiveLayouts.I8).readBytes(buf, offset + 3),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: IDate) {
                    (PrimitiveLayouts.I16).writeBytes(buf, offset, value.year)
                    (PrimitiveLayouts.I8).writeBytes(buf, offset + 2, value.month)
                    (PrimitiveLayouts.I8).writeBytes(buf, offset + 3, value.day)
                }
            }

        public val MIN: IDate = IDate(year = (-9999).toShort(), month = 1, day = 1)

        public val MAX: IDate = IDate(year = 9999, month = 12, day = 31)

        /**
         * Fallibly builds a new date.
         *
         * This checks that the given day is valid for the given year/month.
         *
         * No other conditions are checked. This assumes `year` and `month` are
         * valid, and that `day >= 1`.
         */
        public fun tryNew(year: Short, month: Byte, day: Byte): Result<IDate, Error> {
            if (day > 28.toByte()) {
                val maxDay = daysInMonth(year, month)
                if (day > maxDay) {
                    return Result.Err<IDate, Error>(err("day={day} is out of range for year={year} and month={month}, must be in range 1..={max_day}"))
                }
            }
            return Result.Ok<IDate, Error>(IDate(year = year, month = month, day = day))
        }

        /**
         * Returns the date corresponding to the day of the given year. The day
         * of the year should be a value in `1 ..= 366`, with `366` only being valid
         * if `year` is a leap year.
         *
         * This assumes that `year` is valid, but returns an error if `day` is
         * not in the range `1 ..= 366`.
         */
        public fun fromDayOfYear(year: Short, day: Short): Result<IDate, Error> {
            if (!(1.toShort() <= day && day <= 366.toShort())) {
                return Result.Err<IDate, Error>(err("day-of-year={day} is out of range for year={year}, must be in range 1..={max_day}", maxDay = daysInYear(year)))
            }
            val start = IDate(year = year, month = 1, day = 1).toEpochDay()
            val end =
                when (val tryResult = start.checkedAdd(Int.from(day) - 1).mapErr({
                    err("failed to find date for year={year} and day-of-year={day}: adding `{day}` to `{start}` overflows Jiff's range", start = start.epochDay)
                })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<IDate, Error>(tryResult.errValue()) }.toDate()
            // If we overflowed into the next year, then `day` is too big.
            if (year != end.year) {
                // Can only happen given day=366 and this is a leap year.
                check(day == 366.toShort())
                check(!isLeapYear(year))
                return Result.Err<IDate, Error>(err("day-of-year={day} is out of range for year={year}, must be in range 1..={max_day}", maxDay = daysInYear(year)))
            }
            return Result.Ok<IDate, Error>(end)
        }

        /**
         * Returns the date corresponding to the day of the given year. The day
         * of the year should be a value in `1 ..= 365`, with February 29 being
         * completely ignored. That is, it is guaranteed that Febraury 29 will
         * never be returned by this function. It is impossible.
         *
         * This assumes that `year` is valid, but returns an error if `day` is
         * not in the range `1 ..= 365`.
         */
        public fun fromDayOfYearNoLeap(year: Short, day: Short): Result<IDate, Error> {
            var day1 = day
            if (!(1.toShort() <= day1 && day1 <= 365.toShort())) {
                return Result.Err<IDate, Error>(err("day-of-year={day} is out of range for year={year}, must be in range 1..=365"))
            }
            if (day1 >= 60.toShort() && isLeapYear(year)) {
                day1 += 1
            }
            // The boundary check above guarantees this always succeeds.
            return Result.Ok<IDate, Error>(IDate.fromDayOfYear(year, day1).unwrap())
        }
    }
}

/**
 * Represents a clock time.
 *
 * This uses units of hours, minutes, seconds and fractional seconds (to
 * nanosecond precision).
 */
public open class ITime(
    public var hour: Byte,
    public var minute: Byte,
    public var second: Byte,
    public var subsecNanosecond: Int,
) {
    public open fun clone(): ITime = ITime(hour, minute, second, subsecNanosecond)

    public open fun toSecond(): ITimeSecond {
        var second: Int = 0
        second += (this.hour.toInt()) * 3600
        second += (this.minute.toInt()) * 60
        second += this.second.toInt()
        return ITimeSecond(second = second)
    }

    public open fun toNanosecond(): ITimeNanosecond {
        var nanosecond: Long = 0L
        nanosecond += (this.hour.toLong()) * 3600000000000L
        nanosecond += (this.minute.toLong()) * 60000000000L
        nanosecond += (this.second.toLong()) * 1000000000L
        nanosecond += this.subsecNanosecond.toLong()
        return ITimeNanosecond(nanosecond = nanosecond)
    }

    public companion object {
        public fun typeLayout(): TypeLayout<ITime> =
            object : TypeLayout<ITime> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 8L
                override fun readNative(addr: Long): ITime =
                    ITime(
                        (PrimitiveLayouts.I8).readNative(addr),
                        (PrimitiveLayouts.I8).readNative(addr + 1L),
                        (PrimitiveLayouts.I8).readNative(addr + 2L),
                        (PrimitiveLayouts.I32).readNative(addr + 4L),
                    )
                override fun writeNative(addr: Long, value: ITime) {
                    (PrimitiveLayouts.I8).writeNative(addr, value.hour)
                    (PrimitiveLayouts.I8).writeNative(addr + 1L, value.minute)
                    (PrimitiveLayouts.I8).writeNative(addr + 2L, value.second)
                    (PrimitiveLayouts.I32).writeNative(addr + 4L, value.subsecNanosecond)
                }
                override fun readBytes(buf: ByteArray, offset: Int): ITime =
                    ITime(
                        (PrimitiveLayouts.I8).readBytes(buf, offset),
                        (PrimitiveLayouts.I8).readBytes(buf, offset + 1),
                        (PrimitiveLayouts.I8).readBytes(buf, offset + 2),
                        (PrimitiveLayouts.I32).readBytes(buf, offset + 4),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: ITime) {
                    (PrimitiveLayouts.I8).writeBytes(buf, offset, value.hour)
                    (PrimitiveLayouts.I8).writeBytes(buf, offset + 1, value.minute)
                    (PrimitiveLayouts.I8).writeBytes(buf, offset + 2, value.second)
                    (PrimitiveLayouts.I32).writeBytes(buf, offset + 4, value.subsecNanosecond)
                }
            }

        public val ZERO: ITime = ITime(hour = 0, minute = 0, second = 0, subsecNanosecond = 0)

        public val MIN: ITime = ITime(hour = 0, minute = 0, second = 0, subsecNanosecond = 0)

        public val MAX: ITime = ITime(hour = 23, minute = 59, second = 59, subsecNanosecond = 999999999)
    }
}

/**
 * Represents a single point in the day, to second precision.
 */
public open class ITimeSecond(
    public var second: Int,
) {
    public open fun clone(): ITimeSecond = ITimeSecond(second)

    public open fun toTime(): ITime {
        var second = this.second
        val time = ITime.ZERO
        if (second != 0) {
            time.hour = (second / 3600).toByte()
            second %= 3600
            if (second != 0) {
                time.minute = (second / 60).toByte()
                time.second = (second % 60).toByte()
            }
        }
        return time
    }

    public companion object {
        public fun typeLayout(): TypeLayout<ITimeSecond> =
            object : TypeLayout<ITimeSecond> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 4L
                override fun readNative(addr: Long): ITimeSecond =
                    ITimeSecond(
                        (PrimitiveLayouts.I32).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: ITimeSecond) {
                    (PrimitiveLayouts.I32).writeNative(addr, value.second)
                }
                override fun readBytes(buf: ByteArray, offset: Int): ITimeSecond =
                    ITimeSecond(
                        (PrimitiveLayouts.I32).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: ITimeSecond) {
                    (PrimitiveLayouts.I32).writeBytes(buf, offset, value.second)
                }
            }
    }
}

/**
 * Represents a single point in the day, to nanosecond precision.
 */
public open class ITimeNanosecond(
    public var nanosecond: Long,
) {
    public open fun clone(): ITimeNanosecond = ITimeNanosecond(nanosecond)

    public open fun toTime(): ITime {
        var nanosecond = this.nanosecond
        val time = ITime.ZERO
        if (nanosecond != 0L) {
            time.hour = (nanosecond / 3600000000000L).toByte()
            nanosecond %= 3600000000000L
            if (nanosecond != 0L) {
                time.minute = (nanosecond / 60000000000L).toByte()
                nanosecond %= 60000000000L
                if (nanosecond != 0L) {
                    time.second = (nanosecond / 1000000000L).toByte()
                    time.subsecNanosecond = (nanosecond % 1000000000L).toInt()
                }
            }
        }
        return time
    }

    public companion object {
        public fun typeLayout(): TypeLayout<ITimeNanosecond> =
            object : TypeLayout<ITimeNanosecond> {
                override val alignBytes: Long = 8L
                override val sizeBytes: Long = 8L
                override fun readNative(addr: Long): ITimeNanosecond =
                    ITimeNanosecond(
                        (PrimitiveLayouts.I64).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: ITimeNanosecond) {
                    (PrimitiveLayouts.I64).writeNative(addr, value.nanosecond)
                }
                override fun readBytes(buf: ByteArray, offset: Int): ITimeNanosecond =
                    ITimeNanosecond(
                        (PrimitiveLayouts.I64).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: ITimeNanosecond) {
                    (PrimitiveLayouts.I64).writeBytes(buf, offset, value.nanosecond)
                }
            }
    }
}

/**
 * Represents a weekday.
 */
public open class IWeekday(
    /**
     * Range is `1 ..= 6` with `1 = Monday`.
     */
    public var offset: Byte,
) {
    public open fun clone(): IWeekday = IWeekday(offset)

    /**
     * Returns this weekday as an offset in the range `0 ..= 6` where
     * `0 = Monday`.
     */
    public open fun toMondayZeroOffset(): Byte = (this.toMondayOneOffset() - 1).toByte()

    /**
     * Returns this weekday as an offset in the range `1 ..= 7` where
     * `1 = Monday`.
     */
    public open fun toMondayOneOffset(): Byte = this.offset

    public open fun since(other: IWeekday): Byte = (this.toMondayZeroOffset() - other.toMondayZeroOffset()).remEuclid(7.toByte())

    public companion object {
        public fun typeLayout(): TypeLayout<IWeekday> =
            object : TypeLayout<IWeekday> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 1L
                override fun readNative(addr: Long): IWeekday =
                    IWeekday(
                        (PrimitiveLayouts.I8).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: IWeekday) {
                    (PrimitiveLayouts.I8).writeNative(addr, value.offset)
                }
                override fun readBytes(buf: ByteArray, offset: Int): IWeekday =
                    IWeekday(
                        (PrimitiveLayouts.I8).readBytes(buf, offset),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: IWeekday) {
                    (PrimitiveLayouts.I8).writeBytes(buf, offset, value.offset)
                }
            }

        /**
         * Creates a weekday assuming the week starts on Monday and Monday is at
         * offset `0`.
         */
        public fun fromMondayZeroOffset(offset: Byte): IWeekday {
            check(0.toByte() <= offset && offset <= 6.toByte())
            return IWeekday.fromMondayOneOffset((offset + 1).toByte())
        }

        /**
         * Creates a weekday assuming the week starts on Monday and Monday is at
         * offset `1`.
         */
        public fun fromMondayOneOffset(offset: Byte): IWeekday {
            check(1.toByte() <= offset && offset <= 7.toByte())
            return IWeekday(offset = offset)
        }

        /**
         * Creates a weekday assuming the week starts on Sunday and Sunday is at
         * offset `0`.
         */
        public fun fromSundayZeroOffset(offset: Byte): IWeekday {
            check(0.toByte() <= offset && offset <= 6.toByte())
            return IWeekday.fromMondayZeroOffset((offset - 1).remEuclid(7.toByte()))
        }
    }
}

public sealed class IAmbiguousOffset {
    public data class Unambiguous(public var offset: IOffset) : IAmbiguousOffset()
    public data class Gap(public var before: IOffset, public var after: IOffset) : IAmbiguousOffset()
    public data class Fold(public var before: IOffset, public var after: IOffset) : IAmbiguousOffset()

    public fun rustEquals(other: IAmbiguousOffset): Boolean = this == other
}

/**
 * Returns true if and only if the given year is a leap year.
 *
 * A leap year is a year with 366 days. Typical years have 365 days.
 */
public fun isLeapYear(year: Short): Boolean {
    // From: https://github.com/BurntSushi/jiff/pull/23
    val d =
        if (year % 25 != 0.toShort()) {
            4
        } else {
            16
        }
    return (year % d) == 0.toShort()
}

/**
 * Return the number of days in the given year.
 */
public fun daysInYear(year: Short): Short =
    if (isLeapYear(year)) {
        366
    } else {
        365
    }

/**
 * Return the number of days in the given month.
 */
public fun daysInMonth(year: Short, month: Byte): Byte {
    // From: https://github.com/BurntSushi/jiff/pull/23
    return if (month == 2.toByte()) {
        if (isLeapYear(year)) {
            29
        } else {
            28
        }
    } else {
        ((30.toByte()).toInt() or ((month.toInt() xor (month shr 3).toInt()).toByte()).toInt()).toByte()
    }
}
