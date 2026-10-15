package org.scpc.r2.safeshare

import org.scpc.r2.probe.Sha256

/** Stable readable IDs that remain collision-safe for Unicode and long inputs. */
internal object SafeShareId {
    fun component(value: String, maxLength: Int = 80): String {
        require(maxLength >= 24) { "ID component limit is too small" }
        // Hash every domain value, including short ASCII. Returning short ASCII
        // verbatim would let a different Unicode/long input collide with that
        // literal transformed form.
        val digest = Sha256.utf8(value).take(16)
        val prefixLimit = maxLength - digest.length - 1
        val readable = value
            .map { character ->
                if (
                    character in 'A'..'Z' || character in 'a'..'z' ||
                    character in '0'..'9' || character in "._-"
                ) {
                    character
                } else {
                    '_'
                }
            }
            .joinToString("")
            .take(prefixLimit)
            .ifEmpty { "value" }
        return "$readable-$digest"
    }
}
