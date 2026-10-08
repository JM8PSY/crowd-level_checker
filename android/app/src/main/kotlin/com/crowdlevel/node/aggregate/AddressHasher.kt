package com.crowdlevel.node.aggregate

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MACアドレスをソルト付き SHA-256 でハッシュ化する。
 * ソルトは Service の起動ごとに再生成するため、再起動をまたいで同じ端末を追跡することはできない。
 */
class AddressHasher(private val salt: ByteArray) {

    /** @return 16文字の小文字16進（64bit） */
    fun hash(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(address.uppercase().toByteArray(Charsets.US_ASCII))
        val bytes = digest.digest()
        return buildString(HASH_LENGTH) {
            for (i in 0 until HASH_LENGTH / 2) {
                val b = bytes[i].toInt() and 0xFF
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
    }

    companion object {
        private const val HASH_LENGTH = 16
        private const val SALT_BYTES = 16
        private val HEX = "0123456789abcdef".toCharArray()

        fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
    }
}
