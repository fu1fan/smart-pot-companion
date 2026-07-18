package com.fu1fan.smartpot.server.store

import com.fu1fan.smartpot.protocol.*
import com.fu1fan.smartpot.server.AppConfig
import com.fu1fan.smartpot.server.appJson
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit

class PostgresSmartPotStore(config: AppConfig) : SmartPotStore {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = requireNotNull(config.databaseUrl)
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 8
        minimumIdle = 1
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    private inline fun <reified T> encode(value: T) = appJson.encodeToString(value)
    private inline fun <reified T> decode(value: String): T = appJson.decodeFromString(value)

    private suspend fun <T> db(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

    private inline fun <reified T> ResultSet.decodeColumn(name: String = "data"): T = decode(getString(name))

    override suspend fun seedSpecies(species: List<PlantSpecies>) = db { connection ->
        connection.prepareStatement("INSERT INTO plant_species(id,data) VALUES (?,?::jsonb) ON CONFLICT(id) DO UPDATE SET data=EXCLUDED.data").use { statement ->
            species.forEach {
                statement.setString(1, it.id)
                statement.setString(2, encode(it))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        Unit
    }

    override suspend fun listSpecies(): List<PlantSpecies> = db { c ->
        c.prepareStatement("SELECT data FROM plant_species ORDER BY data->>'chineseName'").use { s ->
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.decodeColumn()) } }
        }
    }

    override suspend fun findSpecies(id: String): PlantSpecies? = db { c ->
        c.prepareStatement("SELECT data FROM plant_species WHERE id=?").use { s ->
            s.setString(1, id); s.executeQuery().use { if (it.next()) it.decodeColumn() else null }
        }
    }

    override suspend fun listPots(): List<PotProfile> = db { c ->
        c.prepareStatement("SELECT data FROM pots ORDER BY data->>'createdAt'").use { s ->
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.decodeColumn()) } }
        }
    }

    override suspend fun findPot(id: String): PotProfile? = findPotWhere("id::text", id)
    override suspend fun findPotByDevice(deviceId: String): PotProfile? = findPotWhere("device_id", deviceId)

    private suspend fun findPotWhere(column: String, value: String): PotProfile? = db { c ->
        c.prepareStatement("SELECT data FROM pots WHERE $column=?").use { s ->
            s.setString(1, value); s.executeQuery().use { if (it.next()) it.decodeColumn() else null }
        }
    }

    override suspend fun savePot(pot: PotProfile): PotProfile = db { c ->
        c.prepareStatement("INSERT INTO pots(id,device_id,data) VALUES (?::uuid,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET device_id=EXCLUDED.device_id,data=EXCLUDED.data").use { s ->
            s.setString(1, pot.id); s.setString(2, pot.deviceId); s.setString(3, encode(pot)); s.executeUpdate()
        }
        pot
    }

    override suspend fun saveTelemetry(potId: String, telemetry: DeviceTelemetry) = db { c ->
        val bucket = Instant.parse(telemetry.recordedAt).truncatedTo(ChronoUnit.MINUTES)
        c.prepareStatement("INSERT INTO telemetry_minute(pot_id,bucket,data) VALUES (?::uuid,?::timestamptz,?::jsonb) ON CONFLICT(pot_id,bucket) DO UPDATE SET data=EXCLUDED.data").use { s ->
            s.setString(1, potId); s.setString(2, bucket.toString()); s.setString(3, encode(telemetry)); s.executeUpdate()
        }
        Unit
    }

    override suspend fun latestTelemetry(potId: String): DeviceTelemetry? = db { c ->
        c.prepareStatement("SELECT data FROM telemetry_minute WHERE pot_id=?::uuid ORDER BY bucket DESC LIMIT 1").use { s ->
            s.setString(1, potId); s.executeQuery().use { if (it.next()) it.decodeColumn() else null }
        }
    }

    override suspend fun telemetryHistory(potId: String, limit: Int): List<DeviceTelemetry> = db { c ->
        c.prepareStatement("SELECT data FROM telemetry_minute WHERE pot_id=?::uuid ORDER BY bucket DESC LIMIT ?").use { s ->
            s.setString(1, potId); s.setInt(2, limit.coerceIn(1, 10_080));
            s.executeQuery().use { rs -> buildList<DeviceTelemetry> { while (rs.next()) add(rs.decodeColumn()) }.reversed() }
        }
    }

    override suspend fun pruneTelemetryBefore(cutoff: String) = db { c ->
        c.prepareStatement("DELETE FROM telemetry_minute WHERE bucket < ?::timestamptz").use { s ->
            s.setString(1, cutoff)
            s.executeUpdate()
        }
        Unit
    }

    override suspend fun saveReportedState(state: DeviceReportedState) = db { c ->
        c.prepareStatement("INSERT INTO device_state(device_id,reported,last_seen_at) VALUES (?,?::jsonb,?::timestamptz) ON CONFLICT(device_id) DO UPDATE SET reported=EXCLUDED.reported,last_seen_at=EXCLUDED.last_seen_at").use { s ->
            s.setString(1, state.deviceId); s.setString(2, encode(state)); s.setString(3, state.reportedAt); s.executeUpdate()
        }; Unit
    }

    override suspend fun saveDesiredState(state: DeviceDesiredState) = db { c ->
        c.prepareStatement("INSERT INTO device_state(device_id,desired) VALUES (?,?::jsonb) ON CONFLICT(device_id) DO UPDATE SET desired=EXCLUDED.desired").use { s ->
            s.setString(1, state.deviceId); s.setString(2, encode(state)); s.executeUpdate()
        }; Unit
    }

    override suspend fun setOnline(deviceId: String, online: Boolean, changedAt: String) = db { c ->
        c.prepareStatement("INSERT INTO device_state(device_id,online,last_seen_at) VALUES (?,?,?::timestamptz) ON CONFLICT(device_id) DO UPDATE SET online=EXCLUDED.online,last_seen_at=EXCLUDED.last_seen_at").use { s ->
            s.setString(1, deviceId); s.setBoolean(2, online); s.setString(3, changedAt); s.executeUpdate()
        }; Unit
    }

    override suspend fun deviceState(deviceId: String): StoredDeviceState = db { c ->
        c.prepareStatement("SELECT reported,desired,online,last_seen_at FROM device_state WHERE device_id=?").use { s ->
            s.setString(1, deviceId)
            s.executeQuery().use { rs ->
                if (!rs.next()) StoredDeviceState() else StoredDeviceState(
                    reported = rs.getString("reported")?.let { decode(it) },
                    desired = rs.getString("desired")?.let { decode(it) },
                    online = rs.getBoolean("online"),
                    lastSeenAt = rs.getObject("last_seen_at")?.toString(),
                )
            }
        }
    }

    override suspend fun listAlerts(potId: String, activeOnly: Boolean): List<PlantAlert> = db { c ->
        val sql = "SELECT data FROM alerts WHERE pot_id=?::uuid" +
            (if (activeOnly) " AND status='ACTIVE'" else "") +
            " ORDER BY data->>'startedAt' DESC"
        c.prepareStatement(sql).use { s -> s.setString(1, potId); s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.decodeColumn()) } } }
    }

    override suspend fun saveAlert(alert: PlantAlert) = saveJsonRecord(
        "INSERT INTO alerts(id,pot_id,type,status,data) VALUES (?::uuid,?::uuid,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,data=EXCLUDED.data",
        alert.id, alert.potId, alert.type.name, alert.status.name, encode(alert),
    )

    override suspend fun listCareLogs(potId: String): List<CareLog> = listPotJson("care_logs", potId, "occurred_at DESC")
    override suspend fun saveCareLog(log: CareLog) = saveJsonRecord("INSERT INTO care_logs(id,pot_id,occurred_at,data) VALUES (?::uuid,?::uuid,?::timestamptz,?::jsonb) ON CONFLICT(id) DO UPDATE SET data=EXCLUDED.data", log.id, log.potId, log.occurredAt, encode(log))
    override suspend fun listReminders(potId: String): List<CareReminder> = listPotJson("reminders", potId, "due_at")
    override suspend fun saveReminder(reminder: CareReminder) = saveJsonRecord("INSERT INTO reminders(id,pot_id,due_at,status,data) VALUES (?::uuid,?::uuid,?::timestamptz,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,data=EXCLUDED.data", reminder.id, reminder.potId, reminder.dueAt, reminder.status.name, encode(reminder))
    override suspend fun listMemories(potId: String): List<UserMemory> = listPotJson("memories", potId, "created_at")
    override suspend fun saveMemory(memory: UserMemory) = saveJsonRecord("INSERT INTO memories(id,pot_id,created_at,data) VALUES (?::uuid,?::uuid,?::timestamptz,?::jsonb)", memory.id, memory.potId, memory.createdAt, encode(memory))

    override suspend fun listMessages(potId: String, limit: Int): List<ChatMessage> = db { c ->
        c.prepareStatement("SELECT data FROM chat_messages WHERE pot_id=?::uuid ORDER BY created_at DESC LIMIT ?").use { s ->
            s.setString(1, potId); s.setInt(2, limit.coerceIn(1, 200)); s.executeQuery().use { rs -> buildList<ChatMessage> { while (rs.next()) add(rs.decodeColumn()) }.reversed() }
        }
    }

    override suspend fun saveMessage(message: ChatMessage) = saveJsonRecord("INSERT INTO chat_messages(id,pot_id,created_at,data) VALUES (?::uuid,?::uuid,?::timestamptz,?::jsonb)", message.id, message.potId, message.createdAt, encode(message))
    override suspend fun affinity(potId: String): AffinityState = db { c -> c.prepareStatement("SELECT data FROM affinity WHERE pot_id=?::uuid").use { s -> s.setString(1, potId); s.executeQuery().use { if (it.next()) it.decodeColumn() else AffinityState() } } }
    override suspend fun saveAffinity(potId: String, affinity: AffinityState) = saveJsonRecord("INSERT INTO affinity(pot_id,data) VALUES (?::uuid,?::jsonb) ON CONFLICT(pot_id) DO UPDATE SET data=EXCLUDED.data", potId, encode(affinity))

    override suspend fun addAffinityEvent(potId: String, eventKey: String, points: Int, occurredAt: String): Boolean = db { c ->
        c.prepareStatement("INSERT INTO affinity_events(id,pot_id,event_key,points,occurred_at) VALUES (gen_random_uuid(),?::uuid,?,?,?::timestamptz) ON CONFLICT(pot_id,event_key) DO NOTHING").use { s ->
            s.setString(1, potId); s.setString(2, eventKey); s.setInt(3, points); s.setString(4, occurredAt); s.executeUpdate() == 1
        }
    }

    override suspend fun listDiaries(potId: String): List<PlantDiary> = listPotJson("diaries", potId, "diary_date DESC")
    override suspend fun saveDiary(diary: PlantDiary): Boolean = db { c ->
        c.prepareStatement("INSERT INTO diaries(id,pot_id,diary_date,data) VALUES (?::uuid,?::uuid,?::date,?::jsonb) ON CONFLICT(pot_id,diary_date) DO NOTHING").use { s ->
            s.setString(1, diary.id); s.setString(2, diary.potId); s.setString(3, diary.diaryDate); s.setString(4, encode(diary)); s.executeUpdate() == 1
        }
    }

    override suspend fun listFocusSessions(potId: String, since: String?): List<FocusSession> = db { c ->
        val sql = buildString {
            append("SELECT data FROM focus_sessions WHERE pot_id=?::uuid")
            if (since != null) append(" AND completed_at>=?::timestamptz")
            append(" ORDER BY completed_at")
        }
        c.prepareStatement(sql).use { s ->
            s.setString(1, potId)
            if (since != null) s.setString(2, since)
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.decodeColumn<FocusSession>()) } }
        }
    }

    override suspend fun saveFocusSession(session: FocusSession) = saveJsonRecord(
        "INSERT INTO focus_sessions(id,pot_id,completed_at,data) VALUES (?::uuid,?::uuid,?::timestamptz,?::jsonb) ON CONFLICT(id) DO UPDATE SET completed_at=EXCLUDED.completed_at,data=EXCLUDED.data",
        session.id, session.potId, session.completedAt, encode(session),
    )

    override suspend fun saveShareCode(code: ShareCode, potId: String) = db { c ->
        c.prepareStatement("INSERT INTO share_codes(code,pot_id,expires_at) VALUES (?,?::uuid,?::timestamptz) ON CONFLICT(code) DO UPDATE SET pot_id=EXCLUDED.pot_id,expires_at=EXCLUDED.expires_at,redeemed_by=NULL").use { s ->
            s.setString(1, code.code); s.setString(2, potId); s.setString(3, code.expiresAt); s.executeUpdate()
        }; Unit
    }

    override suspend fun redeemShareCode(code: String, actorName: String, now: String): Pair<String, ShareCode>? = db { c ->
        c.autoCommit = false
        try {
            val result = c.prepareStatement("SELECT pot_id::text,expires_at FROM share_codes WHERE code=? AND expires_at>?::timestamptz AND redeemed_by IS NULL FOR UPDATE").use { s ->
                s.setString(1, code); s.setString(2, now); s.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getString(1) to ShareCode(code, rs.getObject(2).toString())
                }
            }
            if (result != null) c.prepareStatement("UPDATE share_codes SET redeemed_by=? WHERE code=?").use { s -> s.setString(1, actorName); s.setString(2, code); s.executeUpdate() }
            c.commit(); result
        } catch (error: Throwable) {
            c.rollback(); throw error
        } finally { c.autoCommit = true }
    }

    private suspend inline fun <reified T> listPotJson(table: String, potId: String, orderBy: String): List<T> = db { c ->
        c.prepareStatement("SELECT data FROM $table WHERE pot_id=?::uuid ORDER BY $orderBy").use { s ->
            s.setString(1, potId); s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.decodeColumn()) } }
        }
    }

    private suspend fun saveJsonRecord(sql: String, vararg values: Any?) = db { c ->
        c.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }; Unit
    }

    override fun close() = dataSource.close()
}
