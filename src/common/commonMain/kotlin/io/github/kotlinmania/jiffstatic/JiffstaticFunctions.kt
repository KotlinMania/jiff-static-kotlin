package io.github.kotlinmania.jiffstatic

import io.github.kotlinmania.jiffstatic.shared.PosixDay
import io.github.kotlinmania.jiffstatic.shared.PosixDayTime
import io.github.kotlinmania.jiffstatic.shared.PosixDst
import io.github.kotlinmania.jiffstatic.shared.PosixOffset
import io.github.kotlinmania.jiffstatic.shared.PosixRule
import io.github.kotlinmania.jiffstatic.shared.PosixTime
import io.github.kotlinmania.jiffstatic.shared.PosixTimeZone
import io.github.kotlinmania.jiffstatic.shared.TzifDateTime
import io.github.kotlinmania.jiffstatic.shared.TzifFixed
import io.github.kotlinmania.jiffstatic.shared.TzifIndicator
import io.github.kotlinmania.jiffstatic.shared.TzifLocalTimeType
import io.github.kotlinmania.jiffstatic.shared.TzifOwned
import io.github.kotlinmania.jiffstatic.shared.TzifTransitionInfo
import io.github.kotlinmania.jiffstatic.shared.TzifTransitionKind
import io.github.kotlinmania.jiffstatic.shared.TzifTransitionsOwned
import io.github.kotlinmania.jiffstatic.shared.util.arraystr.Abbreviation
import io.github.kotlinmania.procmacro.TokenStream
import io.github.kotlinmania.quote.quote
import io.github.kotlinmania.rustlang.core.result.unwrapOrElse
import io.github.kotlinmania.syn.parseMacroInput

// Public API docs are in Jiff.
public fun include(input: TokenStream): TokenStream {
    val input1 = parseMacroInput(((input as Any?) as Include))
    return TokenStream.from(input1.quote())
}

// Public API docs are in Jiff.
public fun get(input: TokenStream): TokenStream {
    val input1 = parseMacroInput(((input as Any?) as Get))
    return TokenStream.from(input1.quote())
}

// Everything below at this point is quasi-quoting the `shared` data type
// values into `static` data structures as Rust source code.
public fun TzifOwned.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val fixed = this.fixed
    val types = this.types
    val transitions = this.transitions
    val fixed1 = fixed.quote()
    val types1 = types.iter().map({ it.quote() })
    val transitions1 = transitions.quote()
    return quote("{ static TZ : jiff :: tz :: TimeZone = jiff :: tz :: TimeZone :: __internal_from_tzif (& jiff :: shared :: TzifStatic { fixed : `#`fixed1 , types : & [`#`types1 ,* ] , transitions : `#`transitions1 ,  } . into_jiff () ) ; unsafe { TZ . copy ()  }  }", "fixed1" to fixed1, "types1" to types1, "transitions1" to transitions1)
}

public fun TzifFixed<String, Abbreviation>.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val name = this.name
    val version = this.version
    val checksum = this.checksum
    val designations = this.designations
    val posixTz = this.posixTz
    val name1 = name.asRef().unwrap()
    val posixTz1 =
        posixTz.asRef().map({ tz ->
            val tz1 = tz.quote()
            quote("Some (`#`tz1 )", "tz1" to tz1)
        }).unwrapOrElse({ quote("None") })
    return quote("jiff :: shared :: TzifFixed { name : Some (`#`name1 ) , version : `#`version , checksum : `#`checksum , designations : `#`designations , posix_tz : `#`posixTz1 ,  }", "name1" to name1, "version" to version, "checksum" to checksum, "designations" to designations, "posixTz1" to posixTz1)
}

public fun TzifTransitionsOwned.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val timestamps = this.timestamps
    val civilStarts = this.civilStarts
    val civilEnds = this.civilEnds
    val infos = this.infos
    val civilStarts1: Vec = civilStarts.iter().map({ it.quote() }).collect()
    val civilEnds1: Vec = civilEnds.iter().map({ it.quote() }).collect()
    val infos1: Vec = infos.iter().map({ it.quote() }).collect()
    return quote("jiff :: shared :: TzifTransitions { timestamps : & [`#`timestamps ,* ] , civil_starts : & [`#`civilStarts1 ,* ] , civil_ends : & [`#`civilEnds1 ,* ] , infos : & [`#`infos1 ,* ] ,  }", "timestamps" to timestamps, "civilStarts1" to civilStarts1, "civilEnds1" to civilEnds1, "infos1" to infos1)
}

public fun TzifLocalTimeType.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val offset = this.offset
    val isDst = this.isDst
    val designation = this.designation
    val indicator = this.indicator
    val desigStart = designation.first
    val desigEnd = designation.second
    val indicator1 = indicator.quote()
    return quote("jiff :: shared :: TzifLocalTimeType { offset : `#`offset , is_dst : `#`isDst , designation : (`#`desigStart , `#`desigEnd ) , indicator : `#`indicator1 ,  }", "offset" to offset, "isDst" to isDst, "desigStart" to desigStart, "desigEnd" to desigEnd, "indicator1" to indicator1)
}

public fun TzifIndicator.quote(): io.github.kotlinmania.procmacro2.TokenStream =
    when (this) {
        TzifIndicator.LocalWall -> quote("jiff :: shared :: TzifIndicator :: LocalWall")
        TzifIndicator.LocalStandard -> quote("jiff :: shared :: TzifIndicator :: LocalStandard")
        TzifIndicator.UTStandard -> quote("jiff :: shared :: TzifIndicator :: UTStandard")
    }

public fun TzifTransitionInfo.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val typeIndex = this.typeIndex
    val kind = this.kind
    val kind1 = kind.quote()
    return quote("jiff :: shared :: TzifTransitionInfo { type_index : `#`typeIndex , kind : `#`kind1 ,  }", "typeIndex" to typeIndex, "kind1" to kind1)
}

public fun TzifTransitionKind.quote(): io.github.kotlinmania.procmacro2.TokenStream =
    when (this) {
        TzifTransitionKind.Unambiguous -> quote("jiff :: shared :: TzifTransitionKind :: Unambiguous")
        TzifTransitionKind.Gap -> quote("jiff :: shared :: TzifTransitionKind :: Gap")
        TzifTransitionKind.Fold -> quote("jiff :: shared :: TzifTransitionKind :: Fold")
    }

public fun TzifDateTime.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val year = this.year()
    val month = this.month()
    val day = this.day()
    val hour = this.hour()
    val minute = this.minute()
    val second = this.second()
    return quote("jiff :: shared :: TzifDateTime :: new (`#`year , `#`month , `#`day , `#`hour , `#`minute , `#`second , )", "year" to year, "month" to month, "day" to day, "hour" to hour, "minute" to minute, "second" to second)
}

public fun PosixTimeZone<Abbreviation>.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val stdAbbrev = this.stdAbbrev
    val stdOffset = this.stdOffset
    val dst = this.dst
    val stdAbbrev1 = stdAbbrev
    val stdOffset1 = stdOffset.quote()
    val dst1 =
        dst.asRef().map({ dst1 ->
            val dst2 = dst1.quote()
            quote("Some (`#`dst2 )", "dst2" to dst2)
        }).unwrapOrElse({ quote("None") })
    return quote("jiff :: shared :: PosixTimeZone { std_abbrev : `#`stdAbbrev1 , std_offset : `#`stdOffset1 , dst : `#`dst1 ,  }", "stdAbbrev1" to stdAbbrev1, "stdOffset1" to stdOffset1, "dst1" to dst1)
}

public fun PosixDst<Abbreviation>.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val abbrev = this.abbrev
    val offset = this.offset
    val rule = this.rule
    val abbrev1 = abbrev
    val offset1 = offset.quote()
    val rule1 = rule.quote()
    return quote("jiff :: shared :: PosixDst { abbrev : `#`abbrev1 , offset : `#`offset1 , rule : `#`rule1 ,  }", "abbrev1" to abbrev1, "offset1" to offset1, "rule1" to rule1)
}

public fun PosixRule.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val start = this.start.quote()
    val end = this.end.quote()
    return quote("jiff :: shared :: PosixRule { start : `#`start , end : `#`end  }", "start" to start, "end" to end)
}

public fun PosixDayTime.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val date = this.date
    val time = this.time
    val date1 = date.quote()
    val time1 = time.quote()
    return quote("jiff :: shared :: PosixDayTime { date : `#`date1 , time : `#`time1  }", "date1" to date1, "time1" to time1)
}

public fun PosixDay.quote(): io.github.kotlinmania.procmacro2.TokenStream =
    when (this) {
        is PosixDay.JulianOne -> {
            val (day) = this as PosixDay.JulianOne
            quote("jiff :: shared :: PosixDay :: JulianOne (`#`day )", "day" to day)
        }
        is PosixDay.JulianZero -> {
            val (day1) = this as PosixDay.JulianZero
            quote("jiff :: shared :: PosixDay :: JulianZero (`#`day1 )", "day1" to day1)
        }
        is PosixDay.WeekdayOfMonth -> {
            val month = (this as PosixDay.WeekdayOfMonth).month
            val week = (this as PosixDay.WeekdayOfMonth).week
            val weekday = (this as PosixDay.WeekdayOfMonth).weekday
            quote("jiff :: shared :: PosixDay :: WeekdayOfMonth { month : `#`month , week : `#`week , weekday : `#`weekday ,  }", "month" to month, "week" to week, "weekday" to weekday)
        }
    }

public fun PosixTime.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val second = this.second
    return quote("jiff :: shared :: PosixTime { second : `#`second  }", "second" to second)
}

public fun PosixOffset.quote(): io.github.kotlinmania.procmacro2.TokenStream {
    val second = this.second
    return quote("jiff :: shared :: PosixOffset { second : `#`second  }", "second" to second)
}
