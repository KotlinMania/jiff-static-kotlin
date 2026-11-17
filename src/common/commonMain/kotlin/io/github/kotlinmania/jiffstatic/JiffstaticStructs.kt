package io.github.kotlinmania.jiffstatic

import io.github.kotlinmania.jiffstatic.shared.TzifOwned
import io.github.kotlinmania.procmacro.TokenStream
import io.github.kotlinmania.quote.quote
import io.github.kotlinmania.syn.LitStr
import io.github.kotlinmania.syn.Parse
import io.github.kotlinmania.syn.ParseStream
import io.github.kotlinmania.syn.Result
import io.github.kotlinmania.syn.parse
import io.github.kotlinmania.rustlang.core.result.Result as RustResult

/**
 * The entry point for the `include` macro.
 */
public open class Include(
    public var tzif: TzifOwned,
) : Parse<Include> {
    public open fun quote(): TokenStream = this.tzif.quote()

    public companion object {
        public fun fromPathOnly(path: String): Result<Include, String> {
            val NEEDLE: String = "zoneinfo/"
            val zoneinfo = path.rfind(NEEDLE) ?: return Result.Err<Include, String>("could not extract IANA time zone identifier from file path `$path` (could not find `zoneinfo` in path), please provide IANA time zone identifier as second parameter")
            val idstart = zoneinfo.saturatingAdd(NEEDLE.len())
            val id = path.substring(idstart)
            return Include.fromPathWithId(id, path)
        }

        public fun fromPathWithId(id: String, path: String): Result<Include, String> {
            val id1 = id.toString()
            val data = when (val tryResult = read(path).mapErr({ e -> "failed to read $path: $e" })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Include, String>(tryResult.errValue()) }
            val tzif =
                when (val tryResult = TzifOwned.parse(id1.clone(), data).mapErr({ e ->
                    "failed to parse TZif data from $path: $e"
                })) { is Result.Ok<*, *> -> tryResult.okValue(); is Result.Err<*, *> -> return Result.Err<Include, String>(tryResult.errValue()) }
            return Result.Ok<Include, String>(Include(tzif = tzif))
        }

        public fun parse(input: ParseStream): Result<Include> {
            val lit1 = when (val tryResult = input.parse(LitStr::parse)) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) }.value()
            if (!input.lookahead1().peek(Comma)) {
                return RustResult.Ok(when (val tryResult = Include.fromPathOnly(lit1).mapErr({ e -> input.error(e) })) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) })
            }
            when (val tryResult = input.parse(Comma::parse)) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) }
            if (input.isEmpty()) {
                return RustResult.Ok(when (val tryResult = Include.fromPathOnly(lit1).mapErr({ e -> input.error(e) })) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) })
            }
            val lit2 = when (val tryResult = input.parse(LitStr::parse)) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) }.value()
            // Permit optional trailing comma.
            if (input.lookahead1().peek(Comma)) {
                when (val tryResult = input.parse(Comma::parse)) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) }
            }
            return RustResult.Ok(when (val tryResult = Include.fromPathWithId(lit2, lit1).mapErr({ e -> input.error(e) })) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) })
        }
    }
}

/**
 * The entry point for the `get` macro.
 */
public open class Get(
    public var tzif: TzifOwned,
) : Parse<Get> {
    public open fun quote(): TokenStream = this.tzif.quote()

    public companion object {
        public fun fromId(id: String): RustResult<Get, String> {
            val (id1, data) =
                when (val tryResult = io.github.kotlinmania.jifftzdb.get(id).okOrElse({
                    "could not find time zone `$id` in bundled tzdb"
                })) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err<Get, String>(tryResult.errValue()) }
            val id2 = id1.toString()
            val tzif =
                when (val tryResult = TzifOwned.parse(id2.clone(), data).mapErr({ e ->
                    "failed to parse TZif data from bundled `$id`: $e"
                })) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err<Get, String>(tryResult.errValue()) }
            return RustResult.Ok<Get, String>(Get(tzif = tzif))
        }

        public fun parse(input: ParseStream): Result<Get> {
            val lit1 = when (val tryResult = input.parse(LitStr::parse)) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) }.value()
            if (input.lookahead1().peek(Comma)) {
                when (val tryResult = input.parse(Comma::parse)) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) }
            }
            return RustResult.Ok(when (val tryResult = Get.fromId(lit1).mapErr({ e -> input.error(e) })) { is RustResult.Ok<*, *> -> tryResult.okValue(); is RustResult.Err<*, *> -> return RustResult.Err(tryResult.errValue()) })
        }
    }
}
