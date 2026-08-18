package com.hop.repository

import com.hop.data.ReportedPostDao
import com.hop.data.ReportedPostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wraps [ReportedPostDao] -- hides a reported post locally, on this device
 * only. See [ReportedPostEntity]'s doc: this is NOT the real distributed
 * "don't relay" flag-counting mechanism (PRD §4.6, ADR 0004), which needs a
 * relay protocol (Phase 2) and an attested-device threshold that don't exist
 * yet. Don't let any caller of [report] imply real distributed suppression.
 *
 * `open` for the same reason as [PostRepository]: lets a JVM unit test
 * subclass with an in-memory fake instead of needing a mocking library.
 */
open class ReportRepository(private val dao: ReportedPostDao) {

    open suspend fun report(clipHash: String) {
        dao.insert(ReportedPostEntity(clipHash = clipHash, reportedAtMs = System.currentTimeMillis()))
    }

    open fun observeReportedClipHashes(): Flow<Set<String>> = dao.observeAll().map { it.toSet() }
}
