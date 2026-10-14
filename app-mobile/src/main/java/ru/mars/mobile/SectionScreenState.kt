package ru.mars.mobile

sealed class SectionScreenState(val kind: String) {
    data object Loading : SectionScreenState("LOADING")
    data class Empty(val message: String) : SectionScreenState("EMPTY")
    data class Error(val message: String) : SectionScreenState("ERROR")
    data class Offline(val message: String) : SectionScreenState("OFFLINE")
}
