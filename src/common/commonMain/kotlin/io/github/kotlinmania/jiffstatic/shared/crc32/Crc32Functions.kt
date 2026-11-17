package io.github.kotlinmania.jiffstatic.shared.crc32

import io.github.kotlinmania.jiffstatic.shared.crc32.table.TABLE
import io.github.kotlinmania.jiffstatic.shared.crc32.table.TABLE16
import io.github.kotlinmania.rustlang.core.num.wrappingShl
import io.github.kotlinmania.rustlang.core.num.wrappingShr

/**
 * Returns the "masked" CRC32 checksum of the slice using the Castagnoli
 * polynomial.
 *
 * This "masked" checksum is the same one used by the Snappy frame format.
 * Masking is supposed to make the checksum robust with respect to data that
 * contains the checksum itself.
 */
public fun sum(buf: List<UByte>): UInt {
    val sum = slice16(0u, buf)
    return ((sum.wrappingShr(15u) or sum.wrappingShl(17u)) + 0xA282EAD8u)
}

/**
 * Returns the CRC32 checksum of `buf` using the Castagnoli polynomial.
 *
 * This computes the checksum by looking at 16 bytes from the given slice
 * per iteration.
 */
public fun slice16(prev: UInt, buf: List<UByte>): UInt {
    var buf1 = buf
    var crc: UInt = prev.inv()
    while (buf1.size >= 16) {
        crc = crc xor run { val bytesTmp0 = ((buf1).subList(0, 4)); ((bytesTmp0[3].toUInt() shl 24) or (bytesTmp0[2].toUInt() shl 16) or (bytesTmp0[1].toUInt() shl 8) or bytesTmp0[0].toUInt()) }
        crc = TABLE16[0][Int.from(buf1[15])] xor TABLE16[1][Int.from(buf1[14])] xor TABLE16[2][Int.from(buf1[13])] xor TABLE16[3][Int.from(buf1[12])] xor TABLE16[4][Int.from(buf1[11])] xor TABLE16[5][Int.from(buf1[10])] xor TABLE16[6][Int.from(buf1[9])] xor TABLE16[7][Int.from(buf1[8])] xor TABLE16[8][Int.from(buf1[7])] xor TABLE16[9][Int.from(buf1[6])] xor TABLE16[10][Int.from(buf1[5])] xor TABLE16[11][Int.from(buf1[4])] xor TABLE16[12][Int.from((crc shr 24).toUByte())] xor TABLE16[13][Int.from((crc shr 16).toUByte())] xor TABLE16[14][Int.from((crc shr 8).toUByte())] xor TABLE16[15][Int.from((crc).toUByte())]
        buf1 = ((buf1).subList(16, (buf1).size))
    }
    for (b in buf1) {
        crc = TABLE[Int.from((crc.toUByte()) xor b)] xor (crc shr 8)
    }
    return crc.inv()
}
