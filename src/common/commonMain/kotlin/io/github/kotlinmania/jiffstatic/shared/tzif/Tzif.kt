// port-lint: source shared/tzif.rs
package io.github.kotlinmania.jiffstatic.shared.tzif

import io.github.kotlinmania.jiffstatic.shared.PosixTimeZone
import io.github.kotlinmania.jiffstatic.shared.TzifDateTime
import io.github.kotlinmania.jiffstatic.shared.TzifFixed
import io.github.kotlinmania.jiffstatic.shared.TzifIndicator
import io.github.kotlinmania.jiffstatic.shared.TzifLocalTimeType
import io.github.kotlinmania.jiffstatic.shared.TzifOwned
import io.github.kotlinmania.jiffstatic.shared.TzifTransitionInfo
import io.github.kotlinmania.jiffstatic.shared.TzifTransitionKind
import io.github.kotlinmania.jiffstatic.shared.TzifTransitions
import io.github.kotlinmania.jiffstatic.shared.TzifTransitionsOwned
import io.github.kotlinmania.jiffstatic.shared.crc32.Sum
import io.github.kotlinmania.jiffstatic.shared.util.arraystr.Abbreviation
import io.github.kotlinmania.jiffstatic.shared.util.error.Error
import io.github.kotlinmania.jiffstatic.shared.util.error.err
import io.github.kotlinmania.jiffstatic.shared.util.escape.Byte
import io.github.kotlinmania.jiffstatic.shared.util.escape.Bytes
import io.github.kotlinmania.jiffstatic.shared.util.itime.IOffset
import io.github.kotlinmania.jiffstatic.shared.util.itime.ITimestamp
import io.github.kotlinmania.rustlang.core.cmp.clamp
import io.github.kotlinmania.rustlang.core.cmp.min
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout
import io.github.kotlinmania.rustlang.core.mem.get
import io.github.kotlinmania.rustlang.core.num.checkedAdd
import io.github.kotlinmania.rustlang.core.num.checkedMul
import io.github.kotlinmania.rustlang.core.num.checkedSub
import io.github.kotlinmania.rustlang.core.ptr.constptr.add
import io.github.kotlinmania.rustlang.core.ptr.writeBytes
import io.github.kotlinmania.rustlang.core.result.Result
import io.github.kotlinmania.rustlang.core.slice.asPtr
import io.github.kotlinmania.rustlang.core.slice.chunksExact

// These are Jiff min and max timestamp (in seconds) values.
//
// The TZif parser will clamp timestamps to this range. It's
// not ideal, but Jiff can't handle values outside of this range
// and completely refusing to use TZif data with pathological
// timestamps in typically irrelevant transitions is bad juju.
//
// Ref: https://github.com/BurntSushi/jiff/issues/163
// Ref: https://github.com/BurntSushi/jiff/pull/164
public val TIMESTAMP_MIN: Long = -377705023201L

public val TIMESTAMP_MAX: Long = 253402207200L

// Similarly for offsets, although in this case, if we find
// an offset outside of this range, we do actually error. This
// is because it could result in true incorrect datetimes for
// actual transitions.
//
// But our supported offset range is `- 25: 59: 59 ..= + 25: 59: 59`.
// There's no real time zone with offsets even close to those
// boundaries.
//
// If there is pathological data that we should ignore, then
// we should wait for a real bug report in order to determine
// the right way to ignore/clamp it.
public val OFFSET_MIN: Int = -93599

public val OFFSET_MAX: Int = 93599

// When fattening TZif data, this is the year to go up to.
//
// This year was chosen because it's what the "fat" TZif data generated
// by `zic` uses.
public val FATTEN_UP_TO_YEAR: Short = 2038

// This is a "sanity" limit on the maximum number of transitions we'll
// add to TZif data when fattening them up.
//
// This is mostly just a defense-in-depth limit to avoid weird cases
// where a pathological POSIX time zone could be defined to create
// many transitions. It's not clear that this is actually possible,
// but I felt a little uneasy doing unbounded work that isn't linearly
// proportional to the input data. So, this limit is put into place for
// reasons of "good sense."
//
// For "normal" cases, there should be at most two transitions per
// year. So this limit permits 300/2=150 years of transition data.
// (Although we won't go above 2036. See above.)
public val FATTEN_MAX_TRANSITIONS: Int = 300

/**
 * Parses the given data as a TZif formatted file.
 *
 * The name given is attached to the `Tzif` value returned, but is
 * otherwise not significant.
 *
 * If the given data is not recognized to be valid TZif, then an error is
 * returned.
 *
 * In general, callers may assume that it is safe to pass arbitrary or
 * even untrusted data to this function and count on it not panicking
 * or using resources that aren't limited to a small constant factor of
 * the size of the data itself. That is, callers can reliably limit the
 * resources used by limiting the size of the data given to this parse
 * function.
 */
public fun TzifOwned.Companion.parse(name: String?, bytes: List<UByte>): Result<TzifOwned, Error> {
    val original = bytes
    val name1 = name.into()
    val (header32, rest) = when (val tryResult = Header.parse(4, bytes).mapErr({ e -> err("failed to parse 32-bit header: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<TzifOwned, Error>(tryResult.errValue()) }
    var (tzif, rest1) =
        if (header32.version == 0) {
            when (val tryResult = TzifOwned.parse32(name1, header32, rest)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<TzifOwned, Error>(tryResult.errValue()) }
        } else {
            when (val tryResult = TzifOwned.parse64(name1, header32, rest)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<TzifOwned, Error>(tryResult.errValue()) }
        }
    tzif.fatten()
    // This should come after fattening, because fattening may add new
    // transitions and we want to add civil datetimes to those.
    tzif.addCivilDatetimesToTransitions()
    when (val tryResult = tzif.verifyPosixTimeZoneConsistency()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<TzifOwned, Error>(tryResult.errValue()) }
    // Compute the checksum using the entire contents of the TZif data.
    val tzifRawLen = (rest1.asPtr().toInt()).checkedSub(original.asPtr().rawAddress.toInt())!!
    val tzifRawBytes = ((original).subList(0, tzifRawLen))
    tzif.fixed.checksum = Sum(tzifRawBytes)
    // Shrink all of our allocs so we don't keep excess capacity around.
    tzif.fixed.designations.shrinkToFit()
    tzif.types.shrinkToFit()
    tzif.transitions.timestamps.shrinkToFit()
    tzif.transitions.civilStarts.shrinkToFit()
    tzif.transitions.civilEnds.shrinkToFit()
    tzif.transitions.infos.shrinkToFit()
    return Result.Ok<TzifOwned, Error>(tzif)
}

public fun TzifOwned.Companion.parse32(name: String?, header32: Header, bytes: List<UByte>): Result<Pair<TzifOwned, List<UByte>>, Error> {
    val tzif = TzifOwned(fixed = TzifFixed(name = name, version = header32.version, checksum = 0u, designations = "", posixTz = null), types = mutableListOf(), transitions = TzifTransitions(timestamps = mutableListOf(), civilStarts = mutableListOf(), civilEnds = mutableListOf(), infos = mutableListOf()))
    val rest = when (val tryResult = tzif.parseTransitions(header32, bytes)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest1 = when (val tryResult = tzif.parseTransitionTypes(header32, rest)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest2 = when (val tryResult = tzif.parseLocalTimeTypes(header32, rest1)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest3 = when (val tryResult = tzif.parseTimeZoneDesignations(header32, rest2)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest4 = when (val tryResult = tzif.parseLeapSeconds(header32, rest3)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest5 = when (val tryResult = tzif.parseIndicators(header32, rest4)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    return Result.Ok<Pair<TzifOwned, List<UByte>>, Error>(Pair(tzif, rest5))
}

public fun TzifOwned.Companion.parse64(name: String?, header32: Header, bytes: List<UByte>): Result<Pair<TzifOwned, List<UByte>>, Error> {
    val (_, rest) = when (val tryResult = trySplitAt("V1 TZif data block", bytes, when (val tryResult = header32.dataBlockLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val (header64, rest1) = when (val tryResult = Header.parse(8, rest).mapErr({ e -> err("failed to parse 64-bit header: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val tzif = TzifOwned(fixed = TzifFixed(name = name, version = header64.version, checksum = 0u, designations = "", posixTz = null), types = mutableListOf(), transitions = TzifTransitions(timestamps = mutableListOf(), civilStarts = mutableListOf(), civilEnds = mutableListOf(), infos = mutableListOf()))
    val rest2 = when (val tryResult = tzif.parseTransitions(header64, rest1)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest3 = when (val tryResult = tzif.parseTransitionTypes(header64, rest2)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest4 = when (val tryResult = tzif.parseLocalTimeTypes(header64, rest3)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest5 = when (val tryResult = tzif.parseTimeZoneDesignations(header64, rest4)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest6 = when (val tryResult = tzif.parseLeapSeconds(header64, rest5)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest7 = when (val tryResult = tzif.parseIndicators(header64, rest6)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    val rest8 = when (val tryResult = tzif.parseFooter(header64, rest7)) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<TzifOwned, List<UByte>>, Error>(tryResult.errValue()) }
    // Note that we don't check that the TZif data is fully valid. It is
    // possible for it to contain superfluous information. For example, a
    // non-zero local time type that is never referenced by a transition.
    return Result.Ok<Pair<TzifOwned, List<UByte>>, Error>(Pair(tzif, rest8))
}

public fun TzifOwned.parseTransitions(header: Header, bytes: List<UByte>): Result<List<UByte>, Error> {
    val (bytes1, rest) = when (val tryResult = trySplitAt("transition times data block", bytes, when (val tryResult = header.transitionTimesLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    val it = bytes1.chunksExact(header.timeSize)
    // RFC 8536 says: "If there are no transitions, local time for all
    // timestamps is specified by the TZ string in the footer if present
    // and nonempty; otherwise, it is specified by time type 0."
    //
    // RFC 8536 also says: "Local time for timestamps before the first
    // transition is specified by the first time type (time type
    // 0)."
    //
    // So if there are no transitions, pushing this dummy one will result
    // in the desired behavior even when it's the only transition.
    // Similarly, since this is the minimum timestamp value, it will
    // trigger for any times before the first transition found in the TZif
    // data.
    this.transitions.addWithTypeIndex(TIMESTAMP_MIN, 0)
    while (true) {
        val matchVal = it.next()
        if (matchVal == null) break
        val chunk = matchVal
        var timestamp =
            if (header.is32bit()) {
                Long.from(fromBeBytesI32(chunk))
            } else {
                fromBeBytesI64(chunk)
            }
        if (!(TIMESTAMP_MIN <= timestamp && timestamp <= TIMESTAMP_MAX)) {
            // We really shouldn't error here just because the Unix
            // timestamp is outside what Jiff supports. Since what Jiff
            // supports is _somewhat_ arbitrary. But Jiff's supported
            // range is good enough for all realistic purposes, so we
            // just clamp an out-of-range Unix timestamp to the Jiff
            // min or max value.
            //
            // This can't result in the sorting order being wrong, but
            // it can result in a transition that is duplicative with
            // the dummy transition we inserted above. This should be
            // fine.
            val clamped = timestamp.clamp(TIMESTAMP_MIN, TIMESTAMP_MAX)
            timestamp = clamped
        }
        this.transitions.add(timestamp)
    }
    check(it.remainder().isEmpty())
    return Result.Ok<List<UByte>, Error>(rest)
}

public fun TzifOwned.parseTransitionTypes(header: Header, bytes: List<UByte>): Result<List<UByte>, Error> {
    val (bytes1, rest) = when (val tryResult = trySplitAt("transition types data block", bytes, when (val tryResult = header.transitionTypesLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    // We skip the first transition because it is our minimum dummy
    // transition.
    for ((transitionIndex, typeIndex) in ((1 until Int.MAX_VALUE).zip(bytes1))) {
        if (Int.from(typeIndex) >= header.tzhTypecnt) {
            return Result.Err<List<UByte>, Error>(err("found transition type index {type_index},\n                     but there are only {} local time types", header.tzhTypecnt))
        }
        this.transitions.infos[transitionIndex].typeIndex = typeIndex
    }
    return Result.Ok<List<UByte>, Error>(rest)
}

public fun TzifOwned.parseLocalTimeTypes(header: Header, bytes: List<UByte>): Result<List<UByte>, Error> {
    val (bytes1, rest) = when (val tryResult = trySplitAt("local time types data block", bytes, when (val tryResult = header.localTimeTypesLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    val it = bytes1.chunksExact(6)
    while (true) {
        val matchVal = it.next()
        if (matchVal == null) break
        val chunk = matchVal
        val offset = fromBeBytesI32(chunk[0 until 4])
        if (!(OFFSET_MIN <= offset && offset <= OFFSET_MAX)) {
            return Result.Err<List<UByte>, Error>(err("found local time type with out-of-bounds offset: {offset}"))
        }
        val isDst = chunk[4] == 1
        val designation = Pair(chunk[5], chunk[5])
        this.types.push(TzifLocalTimeType(offset = offset, isDst = isDst, designation = designation, indicator = TzifIndicator.LocalWall))
    }
    check(it.remainder().isEmpty())
    return Result.Ok<List<UByte>, Error>(rest)
}

public fun TzifOwned.parseTimeZoneDesignations(header: Header, bytes: List<UByte>): Result<List<UByte>, Error> {
    val (bytes1, rest) = when (val tryResult = trySplitAt("time zone designations data block", bytes, when (val tryResult = header.timeZoneDesignationsLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    this.fixed.designations = when (val tryResult = String.fromUtf8(bytes1.toVec()).mapErr({
        err("time zone designations are not valid UTF-8: {:?}", Bytes(bytes1))
    })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    // Holy hell, this is brutal. The boundary conditions are crazy.
    for ((i, typ) in this.types.iterMut().mapIndexed { index, item -> Pair(index, item) }) {
        val start = Int.from(typ.designation.first)
        val suffix = this.fixed.designations.get(start until Int.MAX_VALUE) ?: return Result.Err<List<UByte>, Error>(err("local time type {i} has designation index of {start}, but cannot be more than {}", this.fixed.designations.len()))
        val len = suffix.indexOf('\u0000').takeIf { it >= 0 } ?: return Result.Err<List<UByte>, Error>(err("local time type {i} has designation index of {start}, but could not find NUL terminator after it in designations: {:?}", this.fixed.designations))
        val end = start.checkedAdd(len) ?: return Result.Err<List<UByte>, Error>(err("local time type {i} has designation index of {start}, but its length {len} is too big"))
        typ.designation.second = when (val tryResult = UByte.tryFrom(end).mapErr({
            err("local time type {i} has designation range of {start}..{end}, but end is too big")
        })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    }
    return Result.Ok<List<UByte>, Error>(rest)
}

/**
 * This parses the leap second corrections in the TZif data.
 *
 * Note that we only parse and verify them. We don't actually use them.
 * Jiff effectively ignores leap seconds.
 */
public fun TzifOwned.parseLeapSeconds(header: Header, bytes: List<UByte>): Result<List<UByte>, Error> {
    val (bytes1, rest) = when (val tryResult = trySplitAt("leap seconds data block", bytes, when (val tryResult = header.leapSecondLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    val chunkLen = (header.timeSize.checkedAdd(4) ?: error("time_size plus 4 fits in usize"))
    val it = bytes1.chunksExact(chunkLen)
    while (true) {
        val matchVal = it.next()
        if (matchVal == null) break
        val chunk = matchVal
        val (occurBytes, corrBytes) = chunk.splitAt(header.timeSize)
        val occur =
            if (header.is32bit()) {
                Long.from(fromBeBytesI32(occurBytes))
            } else {
                fromBeBytesI64(occurBytes)
            }
        if (!(TIMESTAMP_MIN <= occur && occur <= TIMESTAMP_MAX)) {}
    }
    check(it.remainder().isEmpty())
    return Result.Ok<List<UByte>, Error>(rest)
}

public fun TzifOwned.parseIndicators(header: Header, bytes: List<UByte>): Result<List<UByte>, Error> {
    val (stdWallBytes, rest) = when (val tryResult = trySplitAt("standard/wall indicators data block", bytes, when (val tryResult = header.standardWallLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    val (utLocalBytes, rest1) = when (val tryResult = trySplitAt("UT/local indicators data block", rest, when (val tryResult = header.utLocalLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
    if (stdWallBytes.isEmpty() && !utLocalBytes.isEmpty()) {
        // This is a weird case, but technically possible only if all
        // UT/local indicators are 0. If any are 1, then it's an error,
        // because it would require the corresponding std/wall indicator
        // to be 1 too. Which it can't be, because there aren't any. So
        // we just check that they're all zeros.
        for ((i, byte) in utLocalBytes.iter().mapIndexed { index, item -> Pair(index, item) }) {
            if (byte != 0) {
                return Result.Err<List<UByte>, Error>(err("found UT/local indicator '{byte}' for local time type {i}, but it must be 0 since all std/wall indicators are 0"))
            }
        }
    } else if (!stdWallBytes.isEmpty() && utLocalBytes.isEmpty()) {
        for ((i1, byte1) in stdWallBytes.iter().mapIndexed { index, item -> Pair(index, item) }) {
            // Indexing is OK because Header guarantees that the number of
            // indicators is 0 or equal to the number of types.
            this.types[i1].indicator = if (byte1 == 0) {
                TzifIndicator.LocalWall
            } else if (byte1 == 1) {
                TzifIndicator.LocalStandard
            } else {
                return Result.Err<List<UByte>, Error>(err("found invalid std/wall indicator '{byte}' for local time type {i}, it must be 0 or 1"))
            }
        }
    } else if (!stdWallBytes.isEmpty() && !utLocalBytes.isEmpty()) {
        check(stdWallBytes.len() == utLocalBytes.len())
        val it = (stdWallBytes.iter().zip(utLocalBytes))
        for ((i2, stdwall, utlocal) in it.mapIndexed { index, item -> Pair(index, item) }) {
            // Indexing is OK because Header guarantees that the number of
            // indicators is 0 or equal to the number of types.
            this.types[i2].indicator = run {
                val matchVal = Pair(stdwall, utlocal)
                when {
                    matchVal.first == 0 && matchVal.second == 0 -> TzifIndicator.LocalWall
                    matchVal.first == 1 && matchVal.second == 0 -> TzifIndicator.LocalStandard
                    matchVal.first == 1 && matchVal.second == 1 -> TzifIndicator.UTStandard
                    matchVal.first == 0 && matchVal.second == 1 -> {
                        return Result.Err<List<UByte>, Error>(err("found illegal ut-wall combination for local time type {i}, only local-wall, local-standard and ut-standard are allowed"))
                    }
                    else -> {
                        return Result.Err<List<UByte>, Error>(err("found illegal std/wall or ut/local value for local time type {i}, each must be 0 or 1"))
                    }
                }
            }
        }
    } else {
        // If they're both empty then we don't need to do anything. Every
        // local time type record already has the correct default for this
        // case set.
        check(stdWallBytes.isEmpty())
        check(utLocalBytes.isEmpty())
    }
    return Result.Ok<List<UByte>, Error>(rest1)
}

public fun TzifOwned.parseFooter(header: Header, bytes: List<UByte>): Result<List<UByte>, Error> {
    if (bytes.isEmpty()) {
        return Result.Err<List<UByte>, Error>(err("invalid V2+ TZif footer, expected \\n, but found unexpected end of data"))
    }
    if (bytes[0] != 10u) {
        return Result.Err<List<UByte>, Error>(err("invalid V2+ TZif footer, expected {:?}, but found {:?}", Byte(10u), Byte(bytes[0])))
    }
    val bytes1 = ((bytes).subList(1, (bytes).size))
    // Only scan up to 1KB for a NUL terminator in case we somehow got
    // passed a huge block of bytes.
    val toscan = bytes1[0 until bytes1.len().min(1024)]
    val nlat = toscan.iter().indexOfFirst({ b -> b == 10u }).takeIf { it >= 0 } ?: return Result.Err<List<UByte>, Error>(err("invalid V2 TZif footer, could not find {:?} terminator in: {:?}", Byte(10u), Bytes(toscan)))
    val (bytes2, rest) = bytes1.splitAt(nlat)
    if (!bytes2.isEmpty()) {
        // We could in theory limit TZ strings to their strict POSIX
        // definition here for TZif V2, but I don't think there is any
        // harm in allowing the extensions in V2 formatted TZif data. Note
        // that the GNU tooling allow it via the `TZ` environment variable
        // even though POSIX doesn't specify it. This all seems okay to me
        // because the V3+ extension is a strict superset of functionality.
        val posixTz = when (val tryResult = PosixTimeZone.parse(bytes2).mapErr({ e -> err("{e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<List<UByte>, Error>(tryResult.errValue()) }
        this.fixed.posixTz = posixTz
    }
    return Result.Ok<List<UByte>, Error>(rest[1 until rest.size])
}

/**
 * Validates that the POSIX TZ string we parsed (if one exists) is
 * consistent with the last transition in this time zone. This is
 * required by RFC 8536.
 *
 * RFC 8536 says, "If the string is nonempty and one or more
 * transitions appear in the version 2+ data, the string MUST be
 * consistent with the last version 2+ transition."
 */
public fun TzifOwned.verifyPosixTimeZoneConsistency(): Result<Unit, Error> {
    // We need to be a little careful, since we always have at least one
    // transition (accounting for the dummy `Timestamp.MIN` transition).
    // So if we only have 1 transition and a POSIX TZ string, then we
    // should not validate it since it's equivalent to the case of 0
    // transitions and a POSIX TZ string.
    if (this.transitions.timestamps.len() <= 1) {
        return Result.Ok<Unit, Error>(Unit)
    }
    val tz = this.fixed.posixTz ?: return Result.Ok<Unit, Error>(Unit)
    val last = (this.transitions.timestamps.last() ?: error("last transition timestamp"))
    val typeIndex = (this.transitions.infos.last() ?: error("last transition info")).typeIndex
    val typ = this.types[Int.from(typeIndex)]
    val (ioff, abbrev, isDst) = tz.toOffsetInfo(ITimestamp.fromSecond(last))
    if (ioff.second != typ.offset) {
        return Result.Err<Unit, Error>(err("expected last transition to have DST offset of {expected_offset}, but got {got_offset} according to POSIX TZ string {tz}", expectedOffset = typ.offset, gotOffset = ioff.second, tz = tz))
    }
    if (isDst != typ.isDst) {
        return Result.Err<Unit, Error>(err("expected last transition to have is_dst={expected_dst}, but got is_dst={got_dst} according to POSIX TZ string {tz}", expectedDst = typ.isDst, gotDst = isDst, tz = tz))
    }
    if (abbrev != this.designation(typ)) {
        return Result.Err<Unit, Error>(err("expected last transition to have designation={expected_abbrev}, but got designation={got_abbrev} according to POSIX TZ string {tz}", expectedAbbrev = this.designation(typ), gotAbbrev = abbrev, tz = tz))
    }
    return Result.Ok<Unit, Error>(Unit)
}

/**
 * Add civil datetimes to our transitions.
 *
 * This isn't strictly necessary, but it speeds up time zone lookups when
 * the input is a civil datetime. It lets us do comparisons directly on
 * the civil datetime as given, instead of needing to convert the civil
 * datetime given to a timestamp first. (Even if we didn't do this, I
 * believe we'd still need at least one additional timestamp that is
 * offset, because TZ lookups for a civil datetime are done in local time,
 * and the timestamps in TZif data are, of course, all in UTC.)
 */
public fun TzifOwned.addCivilDatetimesToTransitions() {
    fun toDatetime(timestamp: Long, offset: Int): TzifDateTime {
        val its = ITimestamp(second = timestamp, nanosecond = 0)
        val ioff = IOffset(second = offset)
        val dt = its.toDatetime(ioff)
        return TzifDateTime.new(dt.date.year, dt.date.month, dt.date.day, dt.time.hour, dt.time.minute, dt.time.second)
    }
    val trans = this.transitions
    for (i in 0 until trans.timestamps.len()) {
        val timestamp = trans.timestamps[i]
        val offset = run {
            val typeIndex = trans.infos[i].typeIndex
            this.types[Int.from(typeIndex)].offset
        }
        val prevOffset = run {
            val typeIndex1 = trans.infos[i.saturatingSub(1)].typeIndex
            this.types[Int.from(typeIndex1)].offset
        }
        if (prevOffset == offset) {
            // Equivalent offsets means there can never be any ambiguity.
            val start = toDatetime(timestamp, prevOffset)
            trans.infos[i].kind = TzifTransitionKind.Unambiguous
            trans.civilStarts[i] = start
        } else if (prevOffset < offset) {
            // When the offset of the previous transition is less, that
            // means there is some non-zero amount of time that is
            // "skipped" when moving to the next transition. Thus, we have
            // a gap. The start of the gap is the offset which gets us the
            // earliest time, i.e., the smaller of the two offsets.
            trans.infos[i].kind = TzifTransitionKind.Gap
            trans.civilStarts[i] = toDatetime(timestamp, prevOffset)
            trans.civilEnds[i] = toDatetime(timestamp, offset)
        } else {
            // When the offset of the previous transition is greater, that
            // means there is some non-zero amount of time that will be
            // replayed on a wall clock in this time zone. Thus, we have
            // a fold. The start of the gold is the offset which gets us
            // the earliest time, i.e., the smaller of the two offsets.
            check(prevOffset > offset)
            trans.infos[i].kind = TzifTransitionKind.Fold
            trans.civilStarts[i] = toDatetime(timestamp, offset)
            trans.civilEnds[i] = toDatetime(timestamp, prevOffset)
        }
    }
}

/**
 * Fatten up this TZif data with additional transitions.
 *
 * These additional transitions often make time zone lookups faster, and
 * they smooth out the performance difference between using "slim" and
 * "fat" tzdbs.
 */
public fun TzifOwned.fatten() {
    // Note that this is a crate feature for *both* `jiff` and
    // `jiff - static`.
    if (!false) {
        return
    }
    val posixTz = this.fixed.posixTz.clone() ?: return
    val last = (this.transitions.timestamps.last() ?: error("last transition"))
    var i = 0
    var prev = ITimestamp.fromSecond(last)
    while (true) {
        if (i > FATTEN_MAX_TRANSITIONS) {
            return
        }
        i += 1
        prev = when (val matchVal = this.addTransition(posixTz, prev)) {
            null -> { break }
            else -> {
                val next = matchVal
                next
            }
        }
    }
}

/**
 * If there's a transition strictly after the given timestamp for the
 * given POSIX time zone, then add it to this TZif data.
 */
public fun TzifOwned.addTransition(posixTz: PosixTimeZone<Abbreviation>, prev: ITimestamp): ITimestamp? {
    val (its, ioff, abbrev, isDst) = (posixTz.nextTransition(prev) ?: return null)
    if (its.toDatetime(IOffset.UTC).date.year >= FATTEN_UP_TO_YEAR) {
        return null
    }
    val typeIndex = (this.findOrCreateLocalTimeType(ioff, abbrev, isDst) ?: return null)
    this.transitions.addWithTypeIndex(its.second, typeIndex)
    return its
}

/**
 * Look for a local time type matching the data given.
 *
 * If one could not be found, then one is created and its index is
 * returned.
 *
 * If one could not be found and one could not be created (e.g., the index
 * would overflow `UByte`), then `None` is returned.
 */
public fun TzifOwned.findOrCreateLocalTimeType(offset: IOffset, abbrev: String, isDst: Boolean): UByte? {
    for ((i, typ) in this.types.iter().mapIndexed { index, item -> Pair(index, item) }) {
        if (offset.second == typ.offset && abbrev == this.designation(typ) && isDst == typ.isDst) {
            return UByte.tryFrom(i).ok()
        }
    }
    val i1 = (UByte.tryFrom(this.types.len()).ok() ?: return null)
    val designation = (this.findOrCreateDesignation(abbrev) ?: return null)
    this.types.push(TzifLocalTimeType(offset = offset.second, isDst = isDst, designation = designation, indicator = TzifIndicator.LocalWall))
    return i1
}

/**
 * Look for a designation (i.e., time zone abbreviation) matching the data
 * given, and return its range into `self.fixed.designations`.
 *
 * If one could not be found, then one is created and its range is
 * returned.
 *
 * If one could not be found and one could not be created (e.g., the range
 * would overflow `UByte`), then `None` is returned.
 */
public fun TzifOwned.findOrCreateDesignation(needle: String): Pair<UByte, UByte>? {
    var start = 0
    while (true) {
        val matchVal = this.fixed.designations[start until this.fixed.designations.size].indexOf('\u0000').takeIf { it >= 0 }
        if (matchVal == null) break
        val offset = matchVal
        val end = start + offset
        val abbrev = this.fixed.designations[start until end]
        if (needle == abbrev) {
            return Pair((start.tryInto().ok() ?: return null), (end.tryInto().ok() ?: return null))
        }
        start = end + 1
    }
    this.fixed.designations.pushStr(needle)
    this.fixed.designations.push('\u0000')
    val end1 = start + needle.length
    return Pair((start.tryInto().ok() ?: return null), (end1.tryInto().ok() ?: return null))
}

public fun TzifOwned.designation(typ: TzifLocalTimeType): String {
    val range = Int.from(typ.designation.first) until Int.from(typ.designation.second)
    // OK because we verify that the designation range on every local
    // time type is a valid range into `self.designations`.
    return this.fixed.designations[range]
}

/**
 * Add a single transition with the given timestamp.
 *
 * This also fills in the other columns (civil starts, civil ends and
 * infos) with sensible default values. It is expected that callers will
 * later fill them in.
 */
public fun TzifTransitionsOwned.add(timestamp: Long) {
    this.addWithTypeIndex(timestamp, 0)
}

/**
 * Like `TzifTransitionsOwned.add`, but let's the caller provide a type
 * index if it is known.
 */
public fun TzifTransitionsOwned.addWithTypeIndex(timestamp: Long, typeIndex: UByte) {
    this.timestamps.push(timestamp)
    this.civilStarts.push(TzifDateTime.ZERO)
    this.civilEnds.push(TzifDateTime.ZERO)
    this.infos.push(TzifTransitionInfo(typeIndex = typeIndex, kind = TzifTransitionKind.Unambiguous))
}

/**
 * The header for a TZif formatted file.
 *
 * V2+ TZif format have two headers: one for V1 data, and then a second
 * following the V1 data block that describes another data block which uses
 * 64-bit timestamps. The two headers both have the same format and both
 * use 32-bit big-endian encoded integers.
 */
public open class Header(
    /**
     * The size of the timestamps encoded in the data block.
     *
     * This is guaranteed to be either 4 (for V1) or 8 (for the 64-bit header
     * block in V2+).
     */
    public var timeSize: Int,
    /**
     * The file format version.
     *
     * Note that this is either a NUL byte (for version 1), or an ASCII byte
     * corresponding to the version number. That is, `0x32` for `2`, `0x33`
     * for `3` or `0x34` for `4`. Note also that just because zoneinfo might
     * have been recently generated does not mean it uses the latest format
     * version. It seems like newer versions are only compiled by `zic` when
     * they are needed. For example, `America / NewYork` on my system (as of
     * `2024 - 03 - 25`) has version `0x32`, but `Asia / Jerusalem` has version
     * `0x33`.
     */
    public var version: UByte,
    /**
     * Number of UT/local indicators stored in the file.
     *
     * This is checked to be either equal to `0` or equal to `tzhTypecnt`.
     */
    public var tzhTtisutcnt: Int,
    /**
     * The number of standard/wall indicators stored in the file.
     *
     * This is checked to be either equal to `0` or equal to `tzhTypecnt`.
     */
    public var tzhTtisstdcnt: Int,
    /**
     * The number of leap seconds for which data entries are stored in the
     * file.
     */
    public var tzhLeapcnt: Int,
    /**
     * The number of transition times for which data entries are stored in
     * the file.
     */
    public var tzhTimecnt: Int,
    /**
     * The number of local time types for which data entries are stored in the
     * file.
     *
     * This is checked to be at least `1`.
     */
    public var tzhTypecnt: Int,
    /**
     * The number of bytes of time zone abbreviation strings stored in the
     * file.
     *
     * This is checked to be at least `1`.
     */
    public var tzhCharcnt: Int,
) {
    /**
     * Returns true if this header is for a 32-bit data block.
     *
     * When false, it is guaranteed that this header is for a 64-bit data
     * block.
     */
    public open fun is32bit(): Boolean = this.timeSize == 4

    /**
     * Returns the size of the data block, in bytes, for this header.
     *
     * This returns an error if the arithmetic required to compute the
     * length would overflow.
     *
     * This is useful for, e.g., skipping over the 32-bit V1 data block in
     * V2+ TZif formatted files.
     */
    public open fun dataBlockLen(): Result<Int, Error> {
        val a = when (val tryResult = this.transitionTimesLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
        val b = when (val tryResult = this.transitionTypesLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
        val c = when (val tryResult = this.localTimeTypesLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
        val d = when (val tryResult = this.timeZoneDesignationsLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
        val e = when (val tryResult = this.leapSecondLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
        val f = when (val tryResult = this.standardWallLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
        val g = when (val tryResult = this.utLocalLen()) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Int, Error>(tryResult.errValue()) }
        return (a.checkedAdd(b)?.let({ z -> z.checkedAdd(c) })?.let({ z -> z.checkedAdd(d) })?.let({ z -> z.checkedAdd(e) })?.let({ z -> z.checkedAdd(f) })?.let({ z -> z.checkedAdd(g) })?.let { Result.Ok<Any, Error>(it) } ?: Result.Err<Any, Error>(err("length of data block in V{} tzfile is too big", this.version)))
    }

    public open fun transitionTimesLen(): Result<Int, Error> = (this.tzhTimecnt.checkedMul(this.timeSize)?.let { Result.Ok<Int, Error>(it) } ?: Result.Err<Int, Error>(err("tzh_timecnt value {} is too big", this.tzhTimecnt)))

    public open fun transitionTypesLen(): Result<Int, Error> = Result.Ok<Int, Error>(this.tzhTimecnt)

    public open fun localTimeTypesLen(): Result<Int, Error> = (this.tzhTypecnt.checkedMul(6)?.let { Result.Ok<Int, Error>(it) } ?: Result.Err<Int, Error>(err("tzh_typecnt value {} is too big", this.tzhTypecnt)))

    public open fun timeZoneDesignationsLen(): Result<Int, Error> = Result.Ok<Int, Error>(this.tzhCharcnt)

    public open fun leapSecondLen(): Result<Int, Error> {
        val recordLen = (this.timeSize.checkedAdd(4) ?: error("4-or-8 plus 4 always fits in usize"))
        return (this.tzhLeapcnt.checkedMul(recordLen)?.let { Result.Ok<Int, Error>(it) } ?: Result.Err<Int, Error>(err("tzh_leapcnt value {} is too big", this.tzhLeapcnt)))
    }

    public open fun standardWallLen(): Result<Int, Error> = Result.Ok<Int, Error>(this.tzhTtisstdcnt)

    public open fun utLocalLen(): Result<Int, Error> = Result.Ok<Int, Error>(this.tzhTtisutcnt)

    public companion object {
        public fun typeLayout(): TypeLayout<Header> =
            object : TypeLayout<Header> {
                override val alignBytes: Long = 8L
                override val sizeBytes: Long = 64L
                override fun readNative(addr: Long): Header =
                    Header(
                        (PrimitiveLayouts.USIZE64).readNative(addr),
                        (PrimitiveLayouts.U8).readNative(addr + 8L),
                        (PrimitiveLayouts.USIZE64).readNative(addr + 16L),
                        (PrimitiveLayouts.USIZE64).readNative(addr + 24L),
                        (PrimitiveLayouts.USIZE64).readNative(addr + 32L),
                        (PrimitiveLayouts.USIZE64).readNative(addr + 40L),
                        (PrimitiveLayouts.USIZE64).readNative(addr + 48L),
                        (PrimitiveLayouts.USIZE64).readNative(addr + 56L),
                    )
                override fun writeNative(addr: Long, value: Header) {
                    (PrimitiveLayouts.USIZE64).writeNative(addr, value.timeSize)
                    (PrimitiveLayouts.U8).writeNative(addr + 8L, value.version)
                    (PrimitiveLayouts.USIZE64).writeNative(addr + 16L, value.tzhTtisutcnt)
                    (PrimitiveLayouts.USIZE64).writeNative(addr + 24L, value.tzhTtisstdcnt)
                    (PrimitiveLayouts.USIZE64).writeNative(addr + 32L, value.tzhLeapcnt)
                    (PrimitiveLayouts.USIZE64).writeNative(addr + 40L, value.tzhTimecnt)
                    (PrimitiveLayouts.USIZE64).writeNative(addr + 48L, value.tzhTypecnt)
                    (PrimitiveLayouts.USIZE64).writeNative(addr + 56L, value.tzhCharcnt)
                }
                override fun readBytes(buf: ByteArray, offset: Int): Header =
                    Header(
                        (PrimitiveLayouts.USIZE64).readBytes(buf, offset),
                        (PrimitiveLayouts.U8).readBytes(buf, offset + 8),
                        (PrimitiveLayouts.USIZE64).readBytes(buf, offset + 16),
                        (PrimitiveLayouts.USIZE64).readBytes(buf, offset + 24),
                        (PrimitiveLayouts.USIZE64).readBytes(buf, offset + 32),
                        (PrimitiveLayouts.USIZE64).readBytes(buf, offset + 40),
                        (PrimitiveLayouts.USIZE64).readBytes(buf, offset + 48),
                        (PrimitiveLayouts.USIZE64).readBytes(buf, offset + 56),
                    )
                override fun writeBytes(buf: ByteArray, offset: Int, value: Header) {
                    (PrimitiveLayouts.USIZE64).writeBytes(buf, offset, value.timeSize)
                    (PrimitiveLayouts.U8).writeBytes(buf, offset + 8, value.version)
                    (PrimitiveLayouts.USIZE64).writeBytes(buf, offset + 16, value.tzhTtisutcnt)
                    (PrimitiveLayouts.USIZE64).writeBytes(buf, offset + 24, value.tzhTtisstdcnt)
                    (PrimitiveLayouts.USIZE64).writeBytes(buf, offset + 32, value.tzhLeapcnt)
                    (PrimitiveLayouts.USIZE64).writeBytes(buf, offset + 40, value.tzhTimecnt)
                    (PrimitiveLayouts.USIZE64).writeBytes(buf, offset + 48, value.tzhTypecnt)
                    (PrimitiveLayouts.USIZE64).writeBytes(buf, offset + 56, value.tzhCharcnt)
                }
            }

        /**
         * Parse the header record from the given bytes.
         *
         * Upon success, return the header and all bytes after the header.
         *
         * The given `timeSize` must be 4 or 8, corresponding to either the
         * V1 header block or the V2+ header block, respectively.
         */
        public fun parse(timeSize: Int, bytes: List<UByte>): Result<Pair<Header, List<UByte>>, Error> {
            check(timeSize == 4 || timeSize == 8) { "time size must be 4 or 8" }
            if (bytes.size < 44) {
                return Result.Err<Pair<Header, List<UByte>>, Error>(err("invalid header: too short"))
            }
            val (magic, rest) = bytes.splitAt(4)
            if (magic != "TZif".encodeToByteArray()) {
                return Result.Err<Pair<Header, List<UByte>>, Error>(err("invalid header: magic bytes mismatch"))
            }
            val (version, rest1) = rest.splitAt(1)
            val (reserved, rest2) = rest1.splitAt(15)
            val (tzhTtisutcntBytes, rest3) = rest2.splitAt(4)
            val (tzhTtisstdcntBytes, rest4) = rest3.splitAt(4)
            val (tzhLeapcntBytes, rest5) = rest4.splitAt(4)
            val (tzhTimecntBytes, rest6) = rest5.splitAt(4)
            val (tzhTypecntBytes, rest7) = rest6.splitAt(4)
            val (tzhCharcntBytes, rest8) = rest7.splitAt(4)
            val tzhTtisutcnt = when (val tryResult = fromBeBytesU32ToUsize(tzhTtisutcntBytes).mapErr({ e -> err("failed to parse tzh_ttisutcnt: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<Header, List<UByte>>, Error>(tryResult.errValue()) }
            val tzhTtisstdcnt = when (val tryResult = fromBeBytesU32ToUsize(tzhTtisstdcntBytes).mapErr({ e -> err("failed to parse tzh_ttisstdcnt: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<Header, List<UByte>>, Error>(tryResult.errValue()) }
            val tzhLeapcnt = when (val tryResult = fromBeBytesU32ToUsize(tzhLeapcntBytes).mapErr({ e -> err("failed to parse tzh_leapcnt: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<Header, List<UByte>>, Error>(tryResult.errValue()) }
            val tzhTimecnt = when (val tryResult = fromBeBytesU32ToUsize(tzhTimecntBytes).mapErr({ e -> err("failed to parse tzh_timecnt: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<Header, List<UByte>>, Error>(tryResult.errValue()) }
            val tzhTypecnt = when (val tryResult = fromBeBytesU32ToUsize(tzhTypecntBytes).mapErr({ e -> err("failed to parse tzh_typecnt: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<Header, List<UByte>>, Error>(tryResult.errValue()) }
            val tzhCharcnt = when (val tryResult = fromBeBytesU32ToUsize(tzhCharcntBytes).mapErr({ e -> err("failed to parse tzh_charcnt: {e}") })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Pair<Header, List<UByte>>, Error>(tryResult.errValue()) }
            if (tzhTtisutcnt != 0 && tzhTtisutcnt != tzhTypecnt) {
                return Result.Err<Pair<Header, List<UByte>>, Error>(err("expected tzh_ttisutcnt={tzh_ttisutcnt} to be zero or equal to tzh_typecnt={tzh_typecnt}"))
            }
            if (tzhTtisstdcnt != 0 && tzhTtisstdcnt != tzhTypecnt) {
                return Result.Err<Pair<Header, List<UByte>>, Error>(err("expected tzh_ttisstdcnt={tzh_ttisstdcnt} to be zero or equal to tzh_typecnt={tzh_typecnt}"))
            }
            if (tzhTypecnt < 1) {
                return Result.Err<Pair<Header, List<UByte>>, Error>(err("expected tzh_typecnt={tzh_typecnt} to be at least 1"))
            }
            if (tzhCharcnt < 1) {
                return Result.Err<Pair<Header, List<UByte>>, Error>(err("expected tzh_charcnt={tzh_charcnt} to be at least 1"))
            }
            val header = Header(timeSize = timeSize, version = version[0], tzhTtisutcnt = tzhTtisutcnt, tzhTtisstdcnt = tzhTtisstdcnt, tzhLeapcnt = tzhLeapcnt, tzhTimecnt = tzhTimecnt, tzhTypecnt = tzhTypecnt, tzhCharcnt = tzhCharcnt)
            return Result.Ok<Pair<Header, List<UByte>>, Error>(Pair(header, rest8))
        }
    }
}

/**
 * Splits the given slice of bytes at the index given.
 *
 * If the index is out of range (greater than `bytes.len()`) then an error is
 * returned. The error message will include the `what` string given, which is
 * meant to describe the thing being split.
 */
public fun trySplitAt(what: String, bytes: List<UByte>, at: Int): Result<Pair<List<UByte>, List<UByte>>, Error> =
    if (at > bytes.size) {
        Result.Err<Pair<List<UByte>, List<UByte>>, Error>(err("expected at least {at} bytes for {what}, but found only {} bytes", bytes.size))
    } else {
        Result.Ok<Pair<List<UByte>, List<UByte>>, Error>(bytes.splitAt(at))
    }

/**
 * Interprets the given slice as an unsigned 32-bit big endian integer,
 * attempts to convert it to a `Int` and returns it.
 *
 * # Panics
 *
 * When `bytes.len() != 4`.
 *
 * # Errors
 *
 * This errors if the `UInt` parsed from the given bytes cannot fit in a
 * `Int`.
 */
public fun fromBeBytesU32ToUsize(bytes: List<UByte>): Result<Int, Error> {
    val n = fromBeBytesU32(bytes)
    return Int.tryFrom(n).mapErr({
        err("failed to parse integer {n} (too big, max allowed is {}", Int.MAX_VALUE)
    })
}

/**
 * Interprets the given slice as an unsigned 32-bit big endian integer and
 * returns it.
 *
 * # Panics
 *
 * When `bytes.len() != 4`.
 */
public fun fromBeBytesU32(bytes: List<UByte>): UInt = run { val bytesTmp0 = bytes; ((bytesTmp0[0].toUInt() shl 24) or (bytesTmp0[1].toUInt() shl 16) or (bytesTmp0[2].toUInt() shl 8) or bytesTmp0[3].toUInt()) }

/**
 * Interprets the given slice as a signed 32-bit big endian integer and
 * returns it.
 *
 * # Panics
 *
 * When `bytes.len() != 4`.
 */
public fun fromBeBytesI32(bytes: List<UByte>): Int = run { val bytesTmp1 = bytes; ((bytesTmp1[0].toInt() shl 24) or (bytesTmp1[1].toInt() shl 16) or (bytesTmp1[2].toInt() shl 8) or bytesTmp1[3].toInt()) }

/**
 * Interprets the given slice as a signed 64-bit big endian integer and
 * returns it.
 *
 * # Panics
 *
 * When `bytes.len() != 8`.
 */
public fun fromBeBytesI64(bytes: List<UByte>): Long = run { val bytesTmp2 = bytes; ((bytesTmp2[0].toLong() shl 56) or (bytesTmp2[1].toLong() shl 48) or (bytesTmp2[2].toLong() shl 40) or (bytesTmp2[3].toLong() shl 32) or (bytesTmp2[4].toLong() shl 24) or (bytesTmp2[5].toLong() shl 16) or (bytesTmp2[6].toLong() shl 8) or bytesTmp2[7].toLong()) }
