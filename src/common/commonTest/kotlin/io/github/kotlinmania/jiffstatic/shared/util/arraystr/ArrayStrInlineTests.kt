// port-lint: tests shared/util/array_str.rs
package io.github.kotlinmania.jiffstatic.shared.util.arraystr

public class ArrayStrInlineTests {
    @kotlin.test.Test
    public fun fmtWrite() {
        val dst = ArrayStr.new("")!!
        check(dst.append("abcd").isOk())
        check(dst.append("e").isOk())
        check(dst.append("f").isErr())
    }
}
