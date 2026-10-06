package com.brenninho.streamingservice.protocol

import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * Short code a host shares so viewers can join the stream.
 *
 * The alphabet leaves out look-alike characters (0/O, 1/I) so codes are easy to read aloud.
 */
@JvmInline
value class RoomCode(val value: String) {
    companion object {
        const val LENGTH = 6
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Pass a cryptographically secure [random] (the server does) for codes that must be unguessable. */
        fun generate(random: Random = Random.Default): RoomCode =
            RoomCode(buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } })

        /** Returns the normalized code, or null when [input] is not a valid code. */
        fun parse(input: String): RoomCode? {
            val normalized = input.trim().uppercase()
            return if (normalized.length == LENGTH && normalized.all { it in ALPHABET }) RoomCode(normalized) else null
        }

        /** Same seed, same code. Lets everyone in a Discord Activity land in one room without talking. */
        fun fromSeed(seed: String): RoomCode {
            var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
            for (byte in seed.encodeToByteArray()) {
                hash = (hash xor (byte.toLong() and 0xFF)) * 0x100000001b3L
            }
            return RoomCode(buildString {
                repeat(LENGTH) {
                    append(ALPHABET[(hash and 31).toInt()])
                    hash = hash ushr 5
                }
            })
        }
    }
}
