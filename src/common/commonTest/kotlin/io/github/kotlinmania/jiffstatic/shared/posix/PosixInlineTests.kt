// port-lint: tests shared/posix.rs
package io.github.kotlinmania.jiffstatic.shared.posix

import io.github.kotlinmania.jiffstatic.shared.PosixDay
import io.github.kotlinmania.jiffstatic.shared.PosixDayTime
import io.github.kotlinmania.jiffstatic.shared.PosixDst
import io.github.kotlinmania.jiffstatic.shared.PosixOffset
import io.github.kotlinmania.jiffstatic.shared.PosixRule
import io.github.kotlinmania.jiffstatic.shared.PosixTime
import io.github.kotlinmania.jiffstatic.shared.PosixTimeZone
import io.github.kotlinmania.jiffstatic.shared.util.arraystr.Abbreviation
import io.github.kotlinmania.jiffstatic.shared.util.escape.Byte
import io.github.kotlinmania.jiffstatic.shared.util.itime.IDate
import io.github.kotlinmania.jiffstatic.shared.util.itime.IOffset
import io.github.kotlinmania.rustlang.core.result.Result

public fun posixTimeZone(input: AsRef<List<UByte>>): PosixTimeZone<Abbreviation> {
    val input1 = input.asRef()
    val tz = PosixTimeZone.parse(input1).unwrap()
    // While we're here, assert that converting the TZ back
    // to a string matches what we got. In the original version
    // of the POSIX TZ parser, we were very meticulous about
    // capturing the exact AST of the time zone. But I've
    // since simplified the data structure considerably such
    // that it is lossy in terms of what was actually parsed
    // (but of course, not lossy in terms of the semantic
    // meaning of the time zone).
    //
    // So to account for this, we serialize to a string and
    // then parse it back. We should get what we started with.
    val reparsed = PosixTimeZone.parse(tz.toString().encodeToByteArray().map { it.toUByte() }).unwrap()
    check(tz == reparsed)
    check(tz.toString() == reparsed.toString())
    return tz
}

public fun parser(s: String): Parser = Parser.new(s.encodeToByteArray().map { it.toUByte() })

public fun date(year: Short, month: Byte, day: Byte): IDate = IDate(year = year, month = month, day = day)

public class PosixInlineTests {
    @kotlin.test.Test
    public fun parse() {
        val p = parser("NZST-12NZDT,J60,J300")
        check(p.parse().unwrap() == PosixTimeZone(stdAbbrev = "NZST".into(), stdOffset = PosixOffset(second = 12 * 60 * 60), dst = PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = 13 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(60), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.JulianOne(300), time = PosixTime(second = 2 * 60 * 60))))))
        val p1 = Parser.new("NZST-12NZDT,J60,J300WAT")
        check(p1.parse().isErr())
    }

    @kotlin.test.Test
    public fun parsePosixTimeZone() {
        val p = Parser.new("NZST-12NZDT,M9.5.0,M4.1.0/3")
        check(p.parsePosixTimeZone().unwrap() == PosixTimeZone(stdAbbrev = "NZST".into(), stdOffset = PosixOffset(second = 12 * 60 * 60), dst = PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = 13 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 9, week = 5, weekday = 0), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 4, week = 1, weekday = 0), time = PosixTime(second = 3 * 60 * 60))))))
        val p1 = Parser.new("NZST-12NZDT,M9.5.0,M4.1.0/3WAT")
        check(p1.parsePosixTimeZone().unwrap() == PosixTimeZone(stdAbbrev = "NZST".into(), stdOffset = PosixOffset(second = 12 * 60 * 60), dst = PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = 13 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 9, week = 5, weekday = 0), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 4, week = 1, weekday = 0), time = PosixTime(second = 3 * 60 * 60))))))
        val p2 = Parser.new("NZST-12NZDT,J60,J300")
        check(p2.parsePosixTimeZone().unwrap() == PosixTimeZone(stdAbbrev = "NZST".into(), stdOffset = PosixOffset(second = 12 * 60 * 60), dst = PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = 13 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(60), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.JulianOne(300), time = PosixTime(second = 2 * 60 * 60))))))
        val p3 = Parser.new("NZST-12NZDT,J60,J300WAT")
        check(p3.parsePosixTimeZone().unwrap() == PosixTimeZone(stdAbbrev = "NZST".into(), stdOffset = PosixOffset(second = 12 * 60 * 60), dst = PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = 13 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(60), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.JulianOne(300), time = PosixTime(second = 2 * 60 * 60))))))
    }

    @kotlin.test.Test
    public fun parsePosixDst() {
        val p = Parser.new("NZDT,M9.5.0,M4.1.0/3")
        check(p.parsePosixDst(PosixOffset(second = 12 * 60 * 60)).unwrap() == PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = 13 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 9, week = 5, weekday = 0), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 4, week = 1, weekday = 0), time = PosixTime(second = 3 * 60 * 60)))))
        val p1 = Parser.new("NZDT,J60,J300")
        check(p1.parsePosixDst(PosixOffset(second = 12 * 60 * 60)).unwrap() == PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = 13 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(60), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.JulianOne(300), time = PosixTime(second = 2 * 60 * 60)))))
        val p2 = Parser.new("NZDT-7,J60,J300")
        check(p2.parsePosixDst(PosixOffset(second = 12 * 60 * 60)).unwrap() == PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = 7 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(60), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.JulianOne(300), time = PosixTime(second = 2 * 60 * 60)))))
        val p3 = Parser.new("NZDT+7,J60,J300")
        check(p3.parsePosixDst(PosixOffset(second = 12 * 60 * 60)).unwrap() == PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = -7 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(60), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.JulianOne(300), time = PosixTime(second = 2 * 60 * 60)))))
        val p4 = Parser.new("NZDT7,J60,J300")
        check(p4.parsePosixDst(PosixOffset(second = 12 * 60 * 60)).unwrap() == PosixDst(abbrev = "NZDT".into(), offset = PosixOffset(second = -7 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(60), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.JulianOne(300), time = PosixTime(second = 2 * 60 * 60)))))
        val p5 = Parser.new("NZDT7,")
        check(p5.parsePosixDst(PosixOffset(second = 12 * 60 * 60)).isErr())
        val p6 = Parser.new("NZDT7!")
        check(p6.parsePosixDst(PosixOffset(second = 12 * 60 * 60)).isErr())
    }

    @kotlin.test.Test
    public fun parseAbbreviation() {
        val p = Parser.new("ABC")
        check(p.parseAbbreviation().unwrap() == "ABC")
        val p1 = Parser.new("<ABC>")
        check(p1.parseAbbreviation().unwrap() == "ABC")
        val p2 = Parser.new("<+09>")
        check(p2.parseAbbreviation().unwrap() == "+09")
        val p3 = Parser.new("+09")
        check(p3.parseAbbreviation().isErr())
    }

    @kotlin.test.Test
    public fun parseUnquotedAbbreviation() {
        val p = Parser.new("ABC")
        check(p.parseUnquotedAbbreviation().unwrap() == "ABC")
        val p1 = Parser.new("ABCXYZ")
        check(p1.parseUnquotedAbbreviation().unwrap() == "ABCXYZ")
        val p2 = Parser.new("ABC123")
        check(p2.parseUnquotedAbbreviation().unwrap() == "ABC")
        val tz = "a".repeat(30)
        val p3 = Parser.new(tz)
        check(p3.parseUnquotedAbbreviation().unwrap() == tz)
        val p4 = Parser.new("a")
        check(p4.parseUnquotedAbbreviation().isErr())
        val p5 = Parser.new("ab")
        check(p5.parseUnquotedAbbreviation().isErr())
        val p6 = Parser.new("ab1")
        check(p6.parseUnquotedAbbreviation().isErr())
        val tz1 = "a".repeat(31)
        val p7 = Parser.new(tz1)
        check(p7.parseUnquotedAbbreviation().isErr())
        val p8 = Parser.new("abÿcd".encodeToByteArray())
        check(p8.parseUnquotedAbbreviation().isErr())
    }

    @kotlin.test.Test
    public fun parseQuotedAbbreviation() {
        // The inputs look a little funny here, but that's because
        // `parseQuotedAbbreviation` starts after the opening quote
        // has been parsed.
        val p = Parser.new("ABC>")
        check(p.parseQuotedAbbreviation().unwrap() == "ABC")
        val p1 = Parser.new("ABCXYZ>")
        check(p1.parseQuotedAbbreviation().unwrap() == "ABCXYZ")
        val p2 = Parser.new("ABC>123")
        check(p2.parseQuotedAbbreviation().unwrap() == "ABC")
        val p3 = Parser.new("ABC123>")
        check(p3.parseQuotedAbbreviation().unwrap() == "ABC123")
        val p4 = Parser.new("ab1>")
        check(p4.parseQuotedAbbreviation().unwrap() == "ab1")
        val p5 = Parser.new("+09>")
        check(p5.parseQuotedAbbreviation().unwrap() == "+09")
        val p6 = Parser.new("-09>")
        check(p6.parseQuotedAbbreviation().unwrap() == "-09")
        val tz = ("a".repeat(30)).toString() + ">"
        val p7 = Parser.new(tz)
        check(p7.parseQuotedAbbreviation().unwrap() == tz.trimEndMatches(">"))
        val p8 = Parser.new("a>")
        check(p8.parseQuotedAbbreviation().isErr())
        val p9 = Parser.new("ab>")
        check(p9.parseQuotedAbbreviation().isErr())
        val tz1 = ("a".repeat(31)).toString() + ">"
        val p10 = Parser.new(tz1)
        check(p10.parseQuotedAbbreviation().isErr())
        val p11 = Parser.new("abÿcd>".encodeToByteArray())
        check(p11.parseQuotedAbbreviation().isErr())
        val p12 = Parser.new("ABC")
        check(p12.parseQuotedAbbreviation().isErr())
        val p13 = Parser.new("ABC!>")
        check(p13.parseQuotedAbbreviation().isErr())
    }

    @kotlin.test.Test
    public fun parsePosixOffset() {
        val p = Parser.new("5")
        check(p.parsePosixOffset().unwrap().second == -5 * 60 * 60)
        val p1 = Parser.new("+5")
        check(p1.parsePosixOffset().unwrap().second == -5 * 60 * 60)
        val p2 = Parser.new("-5")
        check(p2.parsePosixOffset().unwrap().second == 5 * 60 * 60)
        val p3 = Parser.new("-12:34:56")
        check(p3.parsePosixOffset().unwrap().second == 12 * 60 * 60 + 34 * 60 + 56)
        val p4 = Parser.new("a")
        check(p4.parsePosixOffset().isErr())
        val p5 = Parser.new("-")
        check(p5.parsePosixOffset().isErr())
        val p6 = Parser.new("+")
        check(p6.parsePosixOffset().isErr())
        val p7 = Parser.new("-a")
        check(p7.parsePosixOffset().isErr())
        val p8 = Parser.new("+a")
        check(p8.parsePosixOffset().isErr())
        val p9 = Parser.new("-25")
        check(p9.parsePosixOffset().isErr())
        val p10 = Parser.new("+25")
        check(p10.parsePosixOffset().isErr())
        // This checks that we don't accidentally permit IANA rules for
        // offset parsing. Namely, the IANA tzfile v3+ extension only applies
        // to transition times. But since POSIX says that the "time" for the
        // offset and transition is the same format, it would be an easy
        // implementation mistake to implement the more flexible rule for
        // IANA and have it accidentally also apply to the offset. So we check
        // that it doesn't here.
        val p11 = Parser.new("25").copy(ianav3plus = true)
        check(p11.parsePosixOffset().isErr())
        val p12 = Parser.new("+25").copy(ianav3plus = true)
        check(p12.parsePosixOffset().isErr())
        val p13 = Parser.new("-25").copy(ianav3plus = true)
        check(p13.parsePosixOffset().isErr())
    }

    @kotlin.test.Test
    public fun parseRule() {
        val p = Parser.new("M9.5.0,M4.1.0/3")
        check(p.parseRule().unwrap() == PosixRule(start = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 9, week = 5, weekday = 0), time = PosixTime(second = 2 * 60 * 60)), end = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 4, week = 1, weekday = 0), time = PosixTime(second = 3 * 60 * 60))))
        val p1 = Parser.new("M9.5.0")
        check(p1.parseRule().isErr())
        val p2 = Parser.new(",M9.5.0,M4.1.0/3")
        check(p2.parseRule().isErr())
        val p3 = Parser.new("M9.5.0/")
        check(p3.parseRule().isErr())
        val p4 = Parser.new("M9.5.0,M4.1.0/")
        check(p4.parseRule().isErr())
    }

    @kotlin.test.Test
    public fun parsePosixDatetime() {
        val p = Parser.new("J1")
        check(p.parsePosixDatetime().unwrap() == PosixDayTime(date = PosixDay.JulianOne(1), time = PosixTime(second = 2 * 60 * 60)))
        val p1 = Parser.new("J1/3")
        check(p1.parsePosixDatetime().unwrap() == PosixDayTime(date = PosixDay.JulianOne(1), time = PosixTime(second = 3 * 60 * 60)))
        val p2 = Parser.new("M4.1.0/3")
        check(p2.parsePosixDatetime().unwrap() == PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 4, week = 1, weekday = 0), time = PosixTime(second = 3 * 60 * 60)))
        val p3 = Parser.new("1/3:45:05")
        check(p3.parsePosixDatetime().unwrap() == PosixDayTime(date = PosixDay.JulianZero(1), time = PosixTime(second = 3 * 60 * 60 + 45 * 60 + 5)))
        val p4 = Parser.new("a")
        check(p4.parsePosixDatetime().isErr())
        val p5 = Parser.new("J1/")
        check(p5.parsePosixDatetime().isErr())
        val p6 = Parser.new("1/")
        check(p6.parsePosixDatetime().isErr())
        val p7 = Parser.new("M4.1.0/")
        check(p7.parsePosixDatetime().isErr())
    }

    @kotlin.test.Test
    public fun parsePosixDate() {
        val p = Parser.new("J1")
        check(p.parsePosixDate().unwrap() == PosixDay.JulianOne(1))
        val p1 = Parser.new("J365")
        check(p1.parsePosixDate().unwrap() == PosixDay.JulianOne(365))
        val p2 = Parser.new("0")
        check(p2.parsePosixDate().unwrap() == PosixDay.JulianZero(0))
        val p3 = Parser.new("1")
        check(p3.parsePosixDate().unwrap() == PosixDay.JulianZero(1))
        val p4 = Parser.new("365")
        check(p4.parsePosixDate().unwrap() == PosixDay.JulianZero(365))
        val p5 = Parser.new("M9.5.0")
        check(p5.parsePosixDate().unwrap() == PosixDay.WeekdayOfMonth(month = 9, week = 5, weekday = 0))
        val p6 = Parser.new("M9.5.6")
        check(p6.parsePosixDate().unwrap() == PosixDay.WeekdayOfMonth(month = 9, week = 5, weekday = 6))
        val p7 = Parser.new("M09.5.6")
        check(p7.parsePosixDate().unwrap() == PosixDay.WeekdayOfMonth(month = 9, week = 5, weekday = 6))
        val p8 = Parser.new("M12.1.1")
        check(p8.parsePosixDate().unwrap() == PosixDay.WeekdayOfMonth(month = 12, week = 1, weekday = 1))
        val p9 = Parser.new("a")
        check(p9.parsePosixDate().isErr())
        val p10 = Parser.new("j")
        check(p10.parsePosixDate().isErr())
        val p11 = Parser.new("m")
        check(p11.parsePosixDate().isErr())
        val p12 = Parser.new("n")
        check(p12.parsePosixDate().isErr())
        val p13 = Parser.new("J366")
        check(p13.parsePosixDate().isErr())
        val p14 = Parser.new("366")
        check(p14.parsePosixDate().isErr())
    }

    @kotlin.test.Test
    public fun parsePosixJulianDayNoLeap() {
        val p = Parser.new("1")
        check(p.parsePosixJulianDayNoLeap().unwrap() == 1)
        val p1 = Parser.new("001")
        check(p1.parsePosixJulianDayNoLeap().unwrap() == 1)
        val p2 = Parser.new("365")
        check(p2.parsePosixJulianDayNoLeap().unwrap() == 365)
        val p3 = Parser.new("3655")
        check(p3.parsePosixJulianDayNoLeap().unwrap() == 365)
        val p4 = Parser.new("0")
        check(p4.parsePosixJulianDayNoLeap().isErr())
        val p5 = Parser.new("366")
        check(p5.parsePosixJulianDayNoLeap().isErr())
    }

    @kotlin.test.Test
    public fun parsePosixJulianDayWithLeap() {
        val p = Parser.new("0")
        check(p.parsePosixJulianDayWithLeap().unwrap() == 0)
        val p1 = Parser.new("1")
        check(p1.parsePosixJulianDayWithLeap().unwrap() == 1)
        val p2 = Parser.new("001")
        check(p2.parsePosixJulianDayWithLeap().unwrap() == 1)
        val p3 = Parser.new("365")
        check(p3.parsePosixJulianDayWithLeap().unwrap() == 365)
        val p4 = Parser.new("3655")
        check(p4.parsePosixJulianDayWithLeap().unwrap() == 365)
        val p5 = Parser.new("366")
        check(p5.parsePosixJulianDayWithLeap().isErr())
    }

    @kotlin.test.Test
    public fun parseWeekdayOfMonth() {
        val p = Parser.new("9.5.0")
        check(p.parseWeekdayOfMonth().unwrap() == Triple(9, 5, 0))
        val p1 = Parser.new("9.1.6")
        check(p1.parseWeekdayOfMonth().unwrap() == Triple(9, 1, 6))
        val p2 = Parser.new("09.1.6")
        check(p2.parseWeekdayOfMonth().unwrap() == Triple(9, 1, 6))
        val p3 = Parser.new("9")
        check(p3.parseWeekdayOfMonth().isErr())
        val p4 = Parser.new("9.")
        check(p4.parseWeekdayOfMonth().isErr())
        val p5 = Parser.new("9.5")
        check(p5.parseWeekdayOfMonth().isErr())
        val p6 = Parser.new("9.5.")
        check(p6.parseWeekdayOfMonth().isErr())
        val p7 = Parser.new("0.5.0")
        check(p7.parseWeekdayOfMonth().isErr())
        val p8 = Parser.new("13.5.0")
        check(p8.parseWeekdayOfMonth().isErr())
        val p9 = Parser.new("9.0.0")
        check(p9.parseWeekdayOfMonth().isErr())
        val p10 = Parser.new("9.6.0")
        check(p10.parseWeekdayOfMonth().isErr())
        val p11 = Parser.new("9.5.7")
        check(p11.parseWeekdayOfMonth().isErr())
    }

    @kotlin.test.Test
    public fun parsePosixTime() {
        val p = Parser.new("5")
        check(p.parsePosixTime().unwrap().second == 5 * 60 * 60)
        val p1 = Parser.new("22")
        check(p1.parsePosixTime().unwrap().second == 22 * 60 * 60)
        val p2 = Parser.new("02")
        check(p2.parsePosixTime().unwrap().second == 2 * 60 * 60)
        val p3 = Parser.new("5:45")
        check(p3.parsePosixTime().unwrap().second == 5 * 60 * 60 + 45 * 60)
        val p4 = Parser.new("5:45:12")
        check(p4.parsePosixTime().unwrap().second == 5 * 60 * 60 + 45 * 60 + 12)
        val p5 = Parser.new("5:45:129")
        check(p5.parsePosixTime().unwrap().second == 5 * 60 * 60 + 45 * 60 + 12)
        val p6 = Parser.new("5:45:12:")
        check(p6.parsePosixTime().unwrap().second == 5 * 60 * 60 + 45 * 60 + 12)
        val p7 = Parser.new("+5:45:12").copy(ianav3plus = true)
        check(p7.parsePosixTime().unwrap().second == 5 * 60 * 60 + 45 * 60 + 12)
        val p8 = Parser.new("-5:45:12").copy(ianav3plus = true)
        check(p8.parsePosixTime().unwrap().second == -(5 * 60 * 60 + 45 * 60 + 12))
        val p9 = Parser.new("-167:45:12").copy(ianav3plus = true)
        check(p9.parsePosixTime().unwrap().second == -(167 * 60 * 60 + 45 * 60 + 12))
        val p10 = Parser.new("25")
        check(p10.parsePosixTime().isErr())
        val p11 = Parser.new("12:2")
        check(p11.parsePosixTime().isErr())
        val p12 = Parser.new("12:")
        check(p12.parsePosixTime().isErr())
        val p13 = Parser.new("12:23:5")
        check(p13.parsePosixTime().isErr())
        val p14 = Parser.new("12:23:")
        check(p14.parsePosixTime().isErr())
        val p15 = Parser.new("168").copy(ianav3plus = true)
        check(p15.parsePosixTime().isErr())
        val p16 = Parser.new("-168").copy(ianav3plus = true)
        check(p16.parsePosixTime().isErr())
        val p17 = Parser.new("+168").copy(ianav3plus = true)
        check(p17.parsePosixTime().isErr())
    }

    @kotlin.test.Test
    public fun parseMonth() {
        val p = Parser.new("1")
        check(p.parseMonth().unwrap() == 1)
        // Should this be allowed? POSIX spec is unclear.
        // We allow it because our parse does stop at 2
        // digits, so this seems harmless. Namely, '001'
        // results in an error.
        val p1 = Parser.new("01")
        check(p1.parseMonth().unwrap() == 1)
        val p2 = Parser.new("12")
        check(p2.parseMonth().unwrap() == 12)
        val p3 = Parser.new("0")
        check(p3.parseMonth().isErr())
        val p4 = Parser.new("00")
        check(p4.parseMonth().isErr())
        val p5 = Parser.new("001")
        check(p5.parseMonth().isErr())
        val p6 = Parser.new("13")
        check(p6.parseMonth().isErr())
    }

    @kotlin.test.Test
    public fun parseWeek() {
        val p = Parser.new("1")
        check(p.parseWeek().unwrap() == 1)
        val p1 = Parser.new("5")
        check(p1.parseWeek().unwrap() == 5)
        val p2 = Parser.new("55")
        check(p2.parseWeek().unwrap() == 5)
        val p3 = Parser.new("0")
        check(p3.parseWeek().isErr())
        val p4 = Parser.new("6")
        check(p4.parseWeek().isErr())
        val p5 = Parser.new("00")
        check(p5.parseWeek().isErr())
        val p6 = Parser.new("01")
        check(p6.parseWeek().isErr())
        val p7 = Parser.new("05")
        check(p7.parseWeek().isErr())
    }

    @kotlin.test.Test
    public fun parseWeekday() {
        val p = Parser.new("0")
        check(p.parseWeekday().unwrap() == 0)
        val p1 = Parser.new("1")
        check(p1.parseWeekday().unwrap() == 1)
        val p2 = Parser.new("6")
        check(p2.parseWeekday().unwrap() == 6)
        val p3 = Parser.new("00")
        check(p3.parseWeekday().unwrap() == 0)
        val p4 = Parser.new("06")
        check(p4.parseWeekday().unwrap() == 0)
        val p5 = Parser.new("60")
        check(p5.parseWeekday().unwrap() == 6)
        val p6 = Parser.new("7")
        check(p6.parseWeekday().isErr())
    }

    @kotlin.test.Test
    public fun parseHourPosix() {
        val p = Parser.new("5")
        check(p.parseHourPosix().unwrap() == 5)
        val p1 = Parser.new("0")
        check(p1.parseHourPosix().unwrap() == 0)
        val p2 = Parser.new("00")
        check(p2.parseHourPosix().unwrap() == 0)
        val p3 = Parser.new("24")
        check(p3.parseHourPosix().unwrap() == 24)
        val p4 = Parser.new("100")
        check(p4.parseHourPosix().unwrap() == 10)
        val p5 = Parser.new("25")
        check(p5.parseHourPosix().isErr())
        val p6 = Parser.new("99")
        check(p6.parseHourPosix().isErr())
    }

    @kotlin.test.Test
    public fun parseHourIanav3plus() {
        val new = { input -> Parser.new(input).copy(ianav3plus = true) }
        val p = new("5")
        check(p.parseHourIanav3plus().unwrap() == 5)
        val p1 = new("0")
        check(p1.parseHourIanav3plus().unwrap() == 0)
        val p2 = new("00")
        check(p2.parseHourIanav3plus().unwrap() == 0)
        val p3 = new("000")
        check(p3.parseHourIanav3plus().unwrap() == 0)
        val p4 = new("24")
        check(p4.parseHourIanav3plus().unwrap() == 24)
        val p5 = new("100")
        check(p5.parseHourIanav3plus().unwrap() == 100)
        val p6 = new("1000")
        check(p6.parseHourIanav3plus().unwrap() == 100)
        val p7 = new("167")
        check(p7.parseHourIanav3plus().unwrap() == 167)
        val p8 = new("168")
        check(p8.parseHourIanav3plus().isErr())
        val p9 = new("999")
        check(p9.parseHourIanav3plus().isErr())
    }

    @kotlin.test.Test
    public fun parseMinute() {
        val p = Parser.new("00")
        check(p.parseMinute().unwrap() == 0)
        val p1 = Parser.new("24")
        check(p1.parseMinute().unwrap() == 24)
        val p2 = Parser.new("59")
        check(p2.parseMinute().unwrap() == 59)
        val p3 = Parser.new("599")
        check(p3.parseMinute().unwrap() == 59)
        val p4 = Parser.new("0")
        check(p4.parseMinute().isErr())
        val p5 = Parser.new("1")
        check(p5.parseMinute().isErr())
        val p6 = Parser.new("9")
        check(p6.parseMinute().isErr())
        val p7 = Parser.new("60")
        check(p7.parseMinute().isErr())
    }

    @kotlin.test.Test
    public fun parseSecond() {
        val p = Parser.new("00")
        check(p.parseSecond().unwrap() == 0)
        val p1 = Parser.new("24")
        check(p1.parseSecond().unwrap() == 24)
        val p2 = Parser.new("59")
        check(p2.parseSecond().unwrap() == 59)
        val p3 = Parser.new("599")
        check(p3.parseSecond().unwrap() == 59)
        val p4 = Parser.new("0")
        check(p4.parseSecond().isErr())
        val p5 = Parser.new("1")
        check(p5.parseSecond().isErr())
        val p6 = Parser.new("9")
        check(p6.parseSecond().isErr())
        val p7 = Parser.new("60")
        check(p7.parseSecond().isErr())
    }

    @kotlin.test.Test
    public fun parseNumberWithExactlyNDigits() {
        val p = Parser.new("1")
        check(p.parseNumberWithExactlyNDigits(1).unwrap() == 1)
        val p1 = Parser.new("12")
        check(p1.parseNumberWithExactlyNDigits(2).unwrap() == 12)
        val p2 = Parser.new("123")
        check(p2.parseNumberWithExactlyNDigits(2).unwrap() == 12)
        val p3 = Parser.new("")
        check(p3.parseNumberWithExactlyNDigits(1).isErr())
        val p4 = Parser.new("1")
        check(p4.parseNumberWithExactlyNDigits(2).isErr())
        val p5 = Parser.new("12")
        check(p5.parseNumberWithExactlyNDigits(3).isErr())
    }

    @kotlin.test.Test
    public fun parseNumberWithUptoNDigits() {
        val p = Parser.new("1")
        check(p.parseNumberWithUptoNDigits(1).unwrap() == 1)
        val p1 = Parser.new("1")
        check(p1.parseNumberWithUptoNDigits(2).unwrap() == 1)
        val p2 = Parser.new("12")
        check(p2.parseNumberWithUptoNDigits(2).unwrap() == 12)
        val p3 = Parser.new("12")
        check(p3.parseNumberWithUptoNDigits(3).unwrap() == 12)
        val p4 = Parser.new("123")
        check(p4.parseNumberWithUptoNDigits(2).unwrap() == 12)
        val p5 = Parser.new("")
        check(p5.parseNumberWithUptoNDigits(1).isErr())
        val p6 = Parser.new("a")
        check(p6.parseNumberWithUptoNDigits(1).isErr())
    }

    @kotlin.test.Test
    public fun toDstCivilDatetimeUtcRange() {
        val tz = posixTimeZone("WART4WARST,J1/-3,J365/20")
        val dstInfo = DstInfo(dst = tz.dst.asRef().unwrap(), start = date(2024, 1, 1).at(1, 0, 0, 0), end = date(2024, 12, 31).at(23, 0, 0, 0))
        check(tz.dstInfoUtc(2024) == dstInfo)
        val tz1 = posixTimeZone("WART4WARST,J1/-4,J365/21")
        val dstInfo1 = DstInfo(dst = tz1.dst.asRef().unwrap(), start = date(2024, 1, 1).at(0, 0, 0, 0), end = date(2024, 12, 31).at(23, 59, 59, 999999999))
        check(tz1.dstInfoUtc(2024) == dstInfo1)
        val tz2 = posixTimeZone("EST5EDT,M3.2.0,M11.1.0")
        val dstInfo2 = DstInfo(dst = tz2.dst.asRef().unwrap(), start = date(2024, 3, 10).at(7, 0, 0, 0), end = date(2024, 11, 3).at(6, 0, 0, 0))
        check(tz2.dstInfoUtc(2024) == dstInfo2)
    }

    @kotlin.test.Test
    public fun reasonable() {
        check(PosixTimeZone.parse("EST5".encodeToByteArray().map { it.toUByte() }).isOk())
        check(PosixTimeZone.parse("EST5EDT".encodeToByteArray().map { it.toUByte() }).isErr())
        check(PosixTimeZone.parse("EST5EDT,J1,J365".encodeToByteArray().map { it.toUByte() }).isOk())
        val tz = posixTimeZone("EST24EDT,J1,J365")
        check(tz == PosixTimeZone(stdAbbrev = "EST".into(), stdOffset = PosixOffset(second = -24 * 60 * 60), dst = PosixDst(abbrev = "EDT".into(), offset = PosixOffset(second = -23 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(1), time = PosixTime.DEFAULT), end = PosixDayTime(date = PosixDay.JulianOne(365), time = PosixTime.DEFAULT)))))
        val tz1 = posixTimeZone("EST-24EDT,J1,J365")
        check(tz1 == PosixTimeZone(stdAbbrev = "EST".into(), stdOffset = PosixOffset(second = 24 * 60 * 60), dst = PosixDst(abbrev = "EDT".into(), offset = PosixOffset(second = 25 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.JulianOne(1), time = PosixTime.DEFAULT), end = PosixDayTime(date = PosixDay.JulianOne(365), time = PosixTime.DEFAULT)))))
    }

    @kotlin.test.Test
    public fun posixDateTimeSpecToDatetime() {
        // For this test, we just keep the offset to zero to simplify things
        // a bit. We get coverage for non-zero offsets in higher level tests.
        val toDatetime =
            { daytime: PosixDayTime, year: Short ->
                daytime.toDatetime(year, IOffset.UTC)
            }
        val tz = posixTimeZone("EST5EDT,J1,J365/5:12:34")
        check(toDatetime(tz.rule().start, 2023) == date(2023, 1, 1).at(2, 0, 0, 0))
        check(toDatetime(tz.rule().end, 2023) == date(2023, 12, 31).at(5, 12, 34, 0))
        val tz1 = posixTimeZone("EST+5EDT,M3.2.0/2,M11.1.0/2")
        check(toDatetime(tz1.rule().start, 2024) == date(2024, 3, 10).at(2, 0, 0, 0))
        check(toDatetime(tz1.rule().end, 2024) == date(2024, 11, 3).at(2, 0, 0, 0))
        val tz2 = posixTimeZone("EST+5EDT,M1.1.1,M12.5.2")
        check(toDatetime(tz2.rule().start, 2024) == date(2024, 1, 1).at(2, 0, 0, 0))
        check(toDatetime(tz2.rule().end, 2024) == date(2024, 12, 31).at(2, 0, 0, 0))
        val tz3 = posixTimeZone("EST5EDT,0/0,J365/25")
        check(toDatetime(tz3.rule().start, 2024) == date(2024, 1, 1).at(0, 0, 0, 0))
        check(toDatetime(tz3.rule().end, 2024) == date(2024, 12, 31).at(23, 59, 59, 999999999))
        val tz4 = posixTimeZone("XXX3EDT4,0/0,J365/23")
        check(toDatetime(tz4.rule().start, 2024) == date(2024, 1, 1).at(0, 0, 0, 0))
        check(toDatetime(tz4.rule().end, 2024) == date(2024, 12, 31).at(23, 0, 0, 0))
        val tz5 = posixTimeZone("XXX3EDT4,0/0,365")
        check(toDatetime(tz5.rule().end, 2023) == date(2023, 12, 31).at(23, 59, 59, 999999999))
        check(toDatetime(tz5.rule().end, 2024) == date(2024, 12, 31).at(2, 0, 0, 0))
        val tz6 = posixTimeZone("XXX3EDT4,J1/-167:59:59,J365/167:59:59")
        check(toDatetime(tz6.rule().start, 2024) == date(2024, 1, 1).at(0, 0, 0, 0))
        check(toDatetime(tz6.rule().end, 2024) == date(2024, 12, 31).at(23, 59, 59, 999999999))
    }

    @kotlin.test.Test
    public fun posixDateTimeSpecTime() {
        val tz = posixTimeZone("EST5EDT,J1,J365/5:12:34")
        check(tz.rule().start.time == PosixTime.DEFAULT)
        check(tz.rule().end.time == PosixTime(second = 5 * 60 * 60 + 12 * 60 + 34))
    }

    @kotlin.test.Test
    public fun posixDateSpecToDate() {
        val tz = posixTimeZone("EST+5EDT,M3.2.0/2,M11.1.0/2")
        val start = tz.rule().start.date.toDate(2023)
        check(start == date(2023, 3, 12))
        val end = tz.rule().end.date.toDate(2023)
        check(end == date(2023, 11, 5))
        val start1 = tz.rule().start.date.toDate(2024)
        check(start1 == date(2024, 3, 10))
        val end1 = tz.rule().end.date.toDate(2024)
        check(end1 == date(2024, 11, 3))
        val tz1 = posixTimeZone("EST+5EDT,J60,J365")
        val start2 = tz1.rule().start.date.toDate(2023)
        check(start2 == date(2023, 3, 1))
        val end2 = tz1.rule().end.date.toDate(2023)
        check(end2 == date(2023, 12, 31))
        val start3 = tz1.rule().start.date.toDate(2024)
        check(start3 == date(2024, 3, 1))
        val end3 = tz1.rule().end.date.toDate(2024)
        check(end3 == date(2024, 12, 31))
        val tz2 = posixTimeZone("EST+5EDT,59,365")
        val start4 = tz2.rule().start.date.toDate(2023)
        check(start4 == date(2023, 3, 1))
        val end4 = tz2.rule().end.date.toDate(2023)
        check(end4 == null)
        val start5 = tz2.rule().start.date.toDate(2024)
        check(start5 == date(2024, 2, 29))
        val end5 = tz2.rule().end.date.toDate(2024)
        check(end5 == date(2024, 12, 31))
        val tz3 = posixTimeZone("EST+5EDT,M1.1.1,M12.5.2")
        val start6 = tz3.rule().start.date.toDate(2024)
        check(start6 == date(2024, 1, 1))
        val end6 = tz3.rule().end.date.toDate(2024)
        check(end6 == date(2024, 12, 31))
    }

    @kotlin.test.Test
    public fun posixTimeSpecToCivilTime() {
        val tz = posixTimeZone("EST5EDT,J1,J365/5:12:34")
        check(tz.dst.asRef().unwrap().rule.start.time.second == 2 * 60 * 60)
        check(tz.dst.asRef().unwrap().rule.end.time.second == 5 * 60 * 60 + 12 * 60 + 34)
        val tz1 = posixTimeZone("EST5EDT,J1/23:59:59,J365/24:00:00")
        check(tz1.dst.asRef().unwrap().rule.start.time.second == 23 * 60 * 60 + 59 * 60 + 59)
        check(tz1.dst.asRef().unwrap().rule.end.time.second == 24 * 60 * 60)
        val tz2 = posixTimeZone("EST5EDT,J1/-1,J365/167:00:00")
        check(tz2.dst.asRef().unwrap().rule.start.time.second == -1 * 60 * 60)
        check(tz2.dst.asRef().unwrap().rule.end.time.second == 167 * 60 * 60)
    }

    @kotlin.test.Test
    public fun parseIana() {
        // Ref: https://github.com/chronotope/chrono/issues/1153
        val p = PosixTimeZone.parse("CRAZY5SHORT,M12.5.0/50,0/2".encodeToByteArray().map { it.toUByte() }).unwrap()
        check(p == PosixTimeZone(stdAbbrev = "CRAZY".into(), stdOffset = PosixOffset(second = -5 * 60 * 60), dst = PosixDst(abbrev = "SHORT".into(), offset = PosixOffset(second = -4 * 60 * 60), rule = PosixRule(start = PosixDayTime(date = PosixDay.WeekdayOfMonth(month = 12, week = 5, weekday = 0), time = PosixTime(second = 50 * 60 * 60)), end = PosixDayTime(date = PosixDay.JulianZero(0), time = PosixTime(second = 2 * 60 * 60))))))
        check(PosixTimeZone.parse("America/New_York".encodeToByteArray().map { it.toUByte() }).isErr())
        check(PosixTimeZone.parse(":America/New_York".encodeToByteArray().map { it.toUByte() }).isErr())
    }
}
