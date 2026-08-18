package com.hop.repository

import com.hop.data.BlockedSenderDeviceDao
import com.hop.data.BlockedSenderDeviceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wraps [BlockedSenderDeviceDao] -- see that entity's doc comment for why
 * this is the weaker, install-local block identity confirmed for this slice,
 * explicitly separate from the ADR-0004-grade
 * `BlockedIdentityDao`/`BlockedIdentityEntity`, which this repository does
 * NOT use. Real attested-key blocking is deferred to the messaging slice.
 *
 * `open` for the same reason as [PostRepository]: lets a JVM unit test
 * subclass with an in-memory fake instead of needing a mocking library.
 */
open class BlockRepository(private val dao: BlockedSenderDeviceDao) {

    open suspend fun block(senderDeviceId: String) {
        dao.insert(
            BlockedSenderDeviceEntity(
                senderDeviceId = senderDeviceId,
                blockedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    open fun observeBlockedSenderIds(): Flow<Set<String>> = dao.observeAll().map { it.toSet() }
}
