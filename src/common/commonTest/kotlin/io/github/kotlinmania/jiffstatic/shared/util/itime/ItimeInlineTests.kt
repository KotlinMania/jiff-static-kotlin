// port-lint: tests shared/util/itime.rs
package io.github.kotlinmania.jiffstatic.shared.util.itime

import io.github.kotlinmania.rustlang.core.result.Result

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

public class ItimeInlineTests {
    @kotlin.test.Test
    public fun roundtripEpochdayDate() {
        for (year in -9999..9999) {
            for (month in 1..12) {
                for (day in 1..daysInMonth(year, month)) {
                    val date = IDate(year = year, month = month, day = day)
                    val epochDay = date.toEpochDay()
                    val dateRoundtrip = epochDay.toDate()
                    check(date == dateRoundtrip)
                }
            }
        }
    }

    @kotlin.test.Test
    public fun roundtripSecondTime() {
        for (second in 0..86399) {
            val second1 = ITimeSecond(second = second)
            val time = second1.toTime()
            val secondRoundtrip = time.toSecond()
            check(second1 == secondRoundtrip)
        }
    }

    @kotlin.test.Test
    public fun roundtripNanosecondTime() {
        for (second in 0..86399) {
            for (nanosecond in listOf(0, 250000000, 500000000, 750000000, 900000000)) {
                val nanosecond1 = ITimeNanosecond(nanosecond = (second * 1000000000L + nanosecond))
                val time = nanosecond1.toTime()
                val nanosecondRoundtrip = time.toNanosecond()
                check(nanosecond1 == nanosecondRoundtrip)
            }
        }
    }

    @kotlin.test.Test
    public fun nthWeekday() {
        val d1 = IDate(year = 2017, month = 3, day = 1)
        val wday = IWeekday.fromSundayZeroOffset(5)
        val d2 = d1.nthWeekdayOfMonth(2, wday).unwrap()
        check(d2 == IDate(year = 2017, month = 3, day = 10))
        val d11 = IDate(year = 2024, month = 3, day = 1)
        val wday1 = IWeekday.fromSundayZeroOffset(4)
        val d21 = d11.nthWeekdayOfMonth(-1, wday1).unwrap()
        check(d21 == IDate(year = 2024, month = 3, day = 28))
        val d12 = IDate(year = 2024, month = 3, day = 25)
        val wday2 = IWeekday.fromSundayZeroOffset(1)
        check(d12.nthWeekdayOfMonth(5, wday2).isErr())
        check(d12.nthWeekdayOfMonth(-5, wday2).isErr())
    }

    @kotlin.test.Test
    public fun weekday() {
        val wday = IWeekday.fromSundayZeroOffset(0)
        check(wday.toMondayOneOffset() == 7.toByte())
        val wday1 = IWeekday.fromMondayOneOffset(7)
        check(wday1.toSundayZeroOffset() == 0.toByte())
        val wday2 = IWeekday.fromSundayOneOffset(1)
        check(wday2.toMondayZeroOffset() == 6.toByte())
        val wday3 = IWeekday.fromMondayZeroOffset(6)
        check(wday3.toSundayOneOffset() == 1.toByte())
    }

    @kotlin.test.Test
    public fun weekdaySince() {
        val wday1 = IWeekday.fromSundayZeroOffset(0)
        val wday2 = IWeekday.fromSundayZeroOffset(6)
        check(wday2.since(wday1) == 6.toByte())
        check(wday1.since(wday2) == 1.toByte())
    }

    @kotlin.test.Test
    public fun leapYear() {
        check(!isLeapYear(1900))
        check(isLeapYear(2000))
        check(!isLeapYear(2001))
        check(!isLeapYear(2002))
        check(!isLeapYear(2003))
        check(isLeapYear(2004))
    }

    @kotlin.test.Test
    public fun numberOfDaysInMonth() {
        check(daysInMonth(2024, 1) == 31.toByte())
        check(daysInMonth(2024, 2) == 29.toByte())
        check(daysInMonth(2024, 3) == 31.toByte())
        check(daysInMonth(2024, 4) == 30.toByte())
        check(daysInMonth(2024, 5) == 31.toByte())
        check(daysInMonth(2024, 6) == 30.toByte())
        check(daysInMonth(2024, 7) == 31.toByte())
        check(daysInMonth(2024, 8) == 31.toByte())
        check(daysInMonth(2024, 9) == 30.toByte())
        check(daysInMonth(2024, 10) == 31.toByte())
        check(daysInMonth(2024, 11) == 30.toByte())
        check(daysInMonth(2024, 12) == 31.toByte())
        check(daysInMonth(2025, 1) == 31.toByte())
        check(daysInMonth(2025, 2) == 28.toByte())
        check(daysInMonth(2025, 3) == 31.toByte())
        check(daysInMonth(2025, 4) == 30.toByte())
        check(daysInMonth(2025, 5) == 31.toByte())
        check(daysInMonth(2025, 6) == 30.toByte())
        check(daysInMonth(2025, 7) == 31.toByte())
        check(daysInMonth(2025, 8) == 31.toByte())
        check(daysInMonth(2025, 9) == 30.toByte())
        check(daysInMonth(2025, 10) == 31.toByte())
        check(daysInMonth(2025, 11) == 30.toByte())
        check(daysInMonth(2025, 12) == 31.toByte())
        check(daysInMonth(1900, 2) == 28.toByte())
        check(daysInMonth(2000, 2) == 29.toByte())
    }
}
