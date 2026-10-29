package org.a1tsign.vds.auth

import at.favre.lib.crypto.bcrypt.BCrypt

open class PasswordHasher {
    // Generated once when the hasher is built, then reused for every unknown username.
    private val dummyHash = BCrypt.withDefaults().hashToString(BCRYPT_COST, DUMMY_PASSWORD.toCharArray())

    open fun hash(password: String): String = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray())

    open fun verify(password: String, encoded: String?): Boolean {
        val hash = encoded ?: dummyHash
        val valid = runCatching { BCrypt.verifyer().verify(password.toCharArray(), hash).verified }.getOrDefault(false)
        return encoded != null && valid
    }

    fun passwordPolicyError(password: String): String? = when {
        password.length < MIN_PASSWORD_CHARACTERS -> "must be at least $MIN_PASSWORD_CHARACTERS characters"
        password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES -> "must be at most $MAX_PASSWORD_BYTES bytes in UTF-8"
        else -> null
    }

    companion object {
        private const val BCRYPT_COST = 10
        private const val MIN_PASSWORD_CHARACTERS = 12
        private const val MAX_PASSWORD_BYTES = 72
        private const val DUMMY_PASSWORD = "not-a-real-account-password"
    }
}
