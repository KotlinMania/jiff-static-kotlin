// port-lint: source shared/util/error.rs
package io.github.kotlinmania.jiffstatic.shared.util.error

/**
 * An error that can be returned when parsing.
 */
public open class Error(
    public var message: String,
) {
    public open fun clone(): Error = Error(message)

    public open fun fmt(f: StringBuilder) {
        f.append((this.message).toString())
    }

    public companion object {
        public fun fromArgs(message: Arguments): Error =
            run {
                val message1 = message.toString().intoBoxedStr()
                Error(message = message1)
            }
    }
}
