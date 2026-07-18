package com.fu1fan.smartpot.server.service

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.store.SmartPotStore
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class CareService(
    private val store: SmartPotStore,
    private val affinity: AffinityService,
) {
    suspend fun add(pot: PotProfile, request: CreateCareLogRequest, actorName: String): CareLog {
        require(request.note.length <= 500) { "备注不能超过 500 个字符" }
        val occurred = request.occurredAt?.let(Instant::parse) ?: Instant.now()
        val log = CareLog(UUID.randomUUID().toString(), pot.id, request.type, occurred.toString(), request.note.trim(), actorName)
        store.saveCareLog(log)
        val interval = when (request.type) {
            CareType.WATER -> pot.species.wateringIntervalDays
            CareType.FERTILIZE -> pot.species.fertilizingIntervalDays
            CareType.PRUNE -> pot.species.pruningIntervalDays
            CareType.REPOT -> pot.species.repottingIntervalDays
            CareType.NEW_LEAF -> null
            CareType.OTHER -> null
        }
        interval?.let {
            store.saveReminder(
                CareReminder(
                    UUID.randomUUID().toString(),
                    pot.id,
                    request.type,
                    nextTitle(request.type),
                    occurred.plus(it.toLong(), ChronoUnit.DAYS).toString(),
                ),
            )
        }
        affinity.award(pot.id, "care:${log.id}", if (request.type == CareType.WATER) 3 else 2, occurred)
        return log
    }

    private fun nextTitle(type: CareType) = when (type) {
        CareType.WATER -> "检查是否需要浇水"
        CareType.FERTILIZE -> "检查是否需要施肥"
        CareType.PRUNE -> "检查是否需要修剪"
        CareType.REPOT -> "检查是否需要换盆"
        else -> "养护提醒"
    }
}
