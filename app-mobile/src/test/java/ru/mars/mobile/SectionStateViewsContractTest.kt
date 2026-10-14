package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionStateViewsContractTest {
    @Test
    fun stateNamesRemainStableForSectionMapping() {
        val states = listOf(
            SectionScreenState.Loading,
            SectionScreenState.Empty("empty"),
            SectionScreenState.Error("error"),
            SectionScreenState.Offline("offline"),
        )
        assertEquals(listOf("LOADING", "EMPTY", "ERROR", "OFFLINE"), states.map { it.kind })
    }
}
