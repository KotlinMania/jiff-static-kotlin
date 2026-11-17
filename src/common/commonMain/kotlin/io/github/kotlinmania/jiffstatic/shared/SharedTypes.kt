package io.github.kotlinmania.jiffstatic.shared

import io.github.kotlinmania.jiffstatic.shared.util.arraystr.Abbreviation

/**
 * An alias for TZif data whose backing storage has a `static` scope.
 * An alias for TZif data whose backing storage is on the heap.
 */
public typealias TzifOwned = Tzif<String, Abbreviation, List<TzifLocalTimeType>, List<Long>, List<TzifDateTime>, List<TzifDateTime>, List<TzifTransitionInfo>>

/**
 * An alias for TZif transition data whose backing storage is on the heap.
 */
public typealias TzifTransitionsOwned = TzifTransitions<List<Long>, List<TzifDateTime>, List<TzifDateTime>, List<TzifTransitionInfo>>

/**
 * This enum corresponds to the possible indicator values for standard/wall
 * and UT/local.
 *
 * Note that UT+Wall is not allowed.
 *
 * I honestly have no earthly clue what they mean. I've read the section about
 * them in RFC 8536 several times and I can't make sense of it. I've even
 * looked at data files that have these set and still can't make sense of
 * them. I've even looked at what other datetime libraries do with these, and
 * they all seem to just ignore them. Like, WTF. I've spent the last couple
 * months of my life steeped in time, and I just cannot figure this out. Am I
 * just dumb?
 *
 * Anyway, we parse them, but otherwise ignore them because that's what all
 * the cool kids do.
 *
 * The default is `LocalWall`, which also occurs when no indicators are
 * present.
 *
 * I tried again and still don't get it. Here's a dump for `Pacific / Honolulu`:
 *
 * ```text
 * $ ./scripts/jiff-debug tzif /usr/share/zoneinfo/Pacific/Honolulu
 * TIME ZONE NAME
 *   /usr/share/zoneinfo/Pacific/Honolulu
 * LOCAL TIME TYPES
 *   000: offset=-10:31:26, isDst=false, designation=LMT, indicator=local/wall
 *   001: offset=-10:30, isDst=false, designation=HST, indicator=local/wall
 *   002: offset=-09:30, isDst=true, designation=HDT, indicator=local/wall
 *   003: offset=-09:30, isDst=true, designation=HWT, indicator=local/wall
 *   004: offset=-09:30, isDst=true, designation=HPT, indicator=ut/std
 *   005: offset=-10, isDst=false, designation=HST, indicator=local/wall
 * TRANSITIONS
 *   0000: -9999-01-02T01:59:59 :: -377705023201 :: type=0, -10:31:26, isDst=false, LMT, local/wall
 *   0001: 1896-01-13T22:31:26 :: -2334101314 :: type=1, -10:30, isDst=false, HST, local/wall
 *   0002: 1933-04-30T12:30:00 :: -1157283000 :: type=2, -09:30, isDst=true, HDT, local/wall
 *   0003: 1933-05-21T21:30:00 :: -1155436200 :: type=1, -10:30, isDst=false, HST, local/wall
 *   0004: 1942-02-09T12:30:00 :: -880198200 :: type=3, -09:30, isDst=true, HWT, local/wall
 *   0005: 1945-08-14T23:00:00 :: -769395600 :: type=4, -09:30, isDst=true, HPT, ut/std
 *   0006: 1945-09-30T11:30:00 :: -765376200 :: type=1, -10:30, isDst=false, HST, local/wall
 *   0007: 1947-06-08T12:30:00 :: -712150200 :: type=5, -10, isDst=false, HST, local/wall
 * POSIX TIME ZONE STRING
 *   HST10
 * ```
 *
 * See how type 004 has a ut/std indicator? What the fuck does that mean?
 * All transitions are defined in terms of UTC. I confirmed this with `zdump`:
 *
 * ```text
 * $ zdump -v Pacific/Honolulu | rg 1945
 * Pacific/Honolulu  Tue Aug 14 22:59:59 1945 UT = Tue Aug 14 13:29:59 1945 HWT isdst=1 gmtoff=-34200
 * Pacific/Honolulu  Tue Aug 14 23:00:00 1945 UT = Tue Aug 14 13:30:00 1945 HPT isdst=1 gmtoff=-34200
 * Pacific/Honolulu  Sun Sep 30 11:29:59 1945 UT = Sun Sep 30 01:59:59 1945 HPT isdst=1 gmtoff=-34200
 * Pacific/Honolulu  Sun Sep 30 11:30:00 1945 UT = Sun Sep 30 01:00:00 1945 HST isdst=0 gmtoff=-37800
 * ```
 *
 * The times match up. All of them. The indicators don't seem to make a
 * difference. I'm clearly missing something.
 */
public sealed class TzifIndicator {
    public data object LocalWall : TzifIndicator()
    public data object LocalStandard : TzifIndicator()
    public data object UTStandard : TzifIndicator()
}

/**
 * The kind of a transition.
 *
 * This is used when trying to determine the offset for a local datetime. It
 * indicates how the corresponding civil datetimes in `civilStarts` and
 * `civilEnds` should be interpreted. That is, there are three possible
 * cases:
 *
 * 1. The offset of this transition is equivalent to the offset of the
 * previous transition. That means there are no ambiguous civil datetimes
 * between the transitions. This can occur, e.g., when the time zone
 * abbreviation changes.
 * 2. The offset of the transition is greater than the offset of the previous
 * transition. That means there is a "gap" in local time between the
 * transitions. This typically corresponds to entering daylight saving time.
 * It is usually, but not always, 1 hour.
 * 3. The offset of the transition is less than the offset of the previous
 * transition. That means there is a "fold" in local time where time is
 * repeated. This typically corresponds to leaving daylight saving time. It
 * is usually, but not always, 1 hour.
 *
 * # More explanation
 *
 * This, when combined with `civilStarts` and `civilEnds` in
 * `TzifTransitions`, explicitly represents ambiguous wall clock times that
 * occur at the boundaries of transitions.
 *
 * The start of the wall clock time is always the earlier possible wall clock
 * time that could occur with this transition's corresponding offset. For a
 * gap, it's the previous transition's offset. For a fold, it's the current
 * transition's offset.
 *
 * For example, DST for `America / NewYork` began on `2024 - 03 - 10T07: 00: 00 + 00`.
 * The offset prior to this instant in time is `- 05`, corresponding
 * to standard time (EST). Thus, in wall clock time, DST began at
 * `2024 - 03 - 10T02: 00: 00`. And since this is a DST transition that jumps ahead
 * an hour, the start of DST also corresponds to the start of a gap. That is,
 * the times `02: 00: 00` through `02: 59: 59` never appear on a clock for this
 * hour. The question is thus: which offset should we apply to `02: 00: 00`?
 * We could apply the offset from the earlier transition `- 05` and get
 * `2024 - 03 - 10T01: 00: 00 - 05` (that's `2024 - 03 - 10T06: 00: 00 + 00`), or we could
 * apply the offset from the later transition `- 04` and get
 * `2024 - 03 - 10T03: 00: 00 - 04` (that's `2024 - 03 - 10T07: 00: 00 + 00`).
 *
 * So in the above, we would have a `Gap` variant where `start` (inclusive) is
 * `2024 - 03 - 10T02: 00: 00` and `end` (exclusive) is `2024 - 03 - 10T03: 00: 00`.
 *
 * The fold case is the same idea, but where the same time is repeated.
 * For example, in `America / NewYork`, standard time began on
 * `2024 - 11 - 03T06: 00: 00 + 00`. The offset prior to this instant in time
 * is `- 04`, corresponding to DST (EDT). Thus, in wall clock time, DST
 * ended at `2024 - 11 - 03T02: 00: 00`. However, since this is a fold, the
 * actual set of ambiguous times begins at `2024 - 11 - 03T01: 00: 00` and
 * ends at `2024 - 11 - 03T01: 59: 59.999999999`. That is, the wall clock time
 * `2024 - 11 - 03T02: 00: 00` is unambiguous.
 *
 * So in the fold case above, we would have a `Fold` variant where
 * `start` (inclusive) is `2024 - 11 - 03T01: 00: 00` and `end` (exclusive) is
 * `2024 - 11 - 03T02: 00: 00`.
 *
 * Since this gets bundled in with the sorted sequence of transitions, we'll
 * use the "start" time in all three cases as our target of binary search.
 * Once we land on a transition, we'll know our given wall clock time is
 * greater than or equal to its start wall clock time. At that point, to
 * determine if there is ambiguity, we merely need to determine if the given
 * wall clock time is less than the corresponding `end` time. If it is, then
 * it falls in a gap or fold. Otherwise, it's unambiguous.
 *
 * Note that we could compute these datetime values while searching for the
 * correct transition, but there's a fair bit of math involved in going
 * between timestamps (which is what TZif gives us) and calendar datetimes
 * (which is what we're given as input). It is also necessary that we offset
 * the timestamp given in TZif at some point, since it is in UTC and the
 * datetime given is in wall clock time. So I decided it would be worth
 * pre-computing what we need in terms of what the input is. This way, we
 * don't need to do any conversions, or indeed, any arithmetic at all, for
 * time zone lookups. We *could* store these as transitions, but then the
 * input datetime would need to be converted to a timestamp before searching
 * the transitions.
 */
public sealed class TzifTransitionKind {
    /**
     * This transition cannot possibly lead to an unambiguous offset because
     * its offset is equivalent to the offset of the previous transition.
     *
     * Has an entry in `civilStarts`, but corresponding entry in `civilEnds`
     * is always zeroes (i.e., meaningless).
     */
    public data object Unambiguous : TzifTransitionKind()
    /**
     * This occurs when this transition's offset is strictly greater than the
     * previous transition's offset. This effectively results in a "gap" of
     * time equal to the difference in the offsets between the two
     * transitions.
     *
     * Has an entry in `civilStarts` for when the gap starts (inclusive) in
     * local time. Also has an entry in `civilEnds` for when the fold ends
     * (exclusive) in local time.
     */
    public data object Gap : TzifTransitionKind()
    /**
     * This occurs when this transition's offset is strictly less than the
     * previous transition's offset. This results in a "fold" of time where
     * the two transitions have an overlap where it is ambiguous which one
     * applies given a wall clock time. In effect, a span of time equal to the
     * difference in the offsets is repeated.
     *
     * Has an entry in `civilStarts` for when the fold starts (inclusive) in
     * local time. Also has an entry in `civilEnds` for when the fold ends
     * (exclusive) in local time.
     */
    public data object Fold : TzifTransitionKind()
}

public sealed class PosixDay {
    /**
     * Julian day in a year, no counting for leap days.
     *
     * Valid range is `1 ..= 365`.
     */
    public data class JulianOne(public var value: Short) : PosixDay()
    /**
     * Julian day in a year, counting for leap days.
     *
     * Valid range is `0 ..= 365`.
     */
    public data class JulianZero(public var value: Short) : PosixDay()
    /**
     * The nth weekday of a month.
     */
    public data class WeekdayOfMonth(public var month: Byte, public var week: Byte, public var weekday: Byte) : PosixDay()

    public fun rustEquals(other: PosixDay): Boolean = this == other
}
