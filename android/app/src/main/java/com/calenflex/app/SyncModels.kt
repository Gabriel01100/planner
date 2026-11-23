package com.calenflex.app

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class MergeCommand(val requestId: String = UUID.randomUUID().toString(), val revision: Int, val base: State, val state: State)
@Serializable data class SyncConflictBackup(val base: State?, val local: State, val remote: State, val remoteRevision: Int)
