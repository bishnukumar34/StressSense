package com.bishnu.stresswelfarebridge.ui.commander

import com.bishnu.stresswelfarebridge.auth.Session
import com.bishnu.stresswelfarebridge.commander.Access
import com.bishnu.stresswelfarebridge.commander.CommanderOverview
import com.bishnu.stresswelfarebridge.commander.PersonnelDirectory
import com.bishnu.stresswelfarebridge.commander.PersonnelRecord
import com.bishnu.stresswelfarebridge.commander.StressZone

/** Where the commander currently is. Back navigation walks this stack. */
sealed class CommanderRoute {
    data object Zones : CommanderRoute()
    data class ZoneList(val zone: StressZone) : CommanderRoute()
    data class PersonnelDetail(val accountId: String) : CommanderRoute()
}

/** Everything the commander screens render, with explicit loading / empty / error states. */
data class CommanderUiState(
    val loading: Boolean = false,
    val overview: CommanderOverview? = null,
    val error: String? = null,
    val refreshedAtMs: Long? = null
) {
    val hasData: Boolean get() = (overview?.totalPersonnel ?: 0) > 0
    fun count(zone: StressZone): Int = overview?.count(zone) ?: 0
    fun members(zone: StressZone): List<PersonnelRecord> = overview?.zones?.get(zone).orEmpty()
}

/**
 * Loads the commander's data on demand.
 *
 * Reads go through [PersonnelDirectory], which authorises every call, so this controller cannot be
 * used to reach personnel data with a non-commander session — it simply receives a
 * [CommanderUiState] carrying the denial message.
 *
 * Deliberately synchronous: the store is on-device JSON, so there is no network round trip to fake
 * and no result is ever invented while waiting. "Refreshing" re-reads the same real records.
 */
class CommanderController(private val directory: PersonnelDirectory) {

    fun load(session: Session?): CommanderUiState = when (val access = directory.commanderOverview(session)) {
        is Access.Denied -> CommanderUiState(loading = false, overview = null, error = access.reason)
        is Access.Allowed -> CommanderUiState(
            loading = false,
            overview = access.value,
            error = null,
            refreshedAtMs = access.value.lastUpdatedMs
        )
    }

    fun zoneMembers(session: Session?, zone: StressZone): Access<List<PersonnelRecord>> =
        directory.zoneMembers(session, zone)

    fun personnelDetail(session: Session?, accountId: String): Access<PersonnelRecord> =
        directory.personnelDetail(session, accountId)
}
