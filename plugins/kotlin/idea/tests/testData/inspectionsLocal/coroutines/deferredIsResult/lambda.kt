// WITH_STDLIB
// PROBLEM: none
package kotlinx.coroutines

suspend fun barAsync(): Deferred<String>? {
    return null
}

suspend fun foo() {
    " ".let <caret>{ barAsync() }
}

