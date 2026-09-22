package dev.gorny.buymyway.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Names of shared lists that were taken away from this user (PLAN.md Phase 5, task 7), kept
 * until the home screen has said so. In memory only: a list is lost once, and the sentence
 * matters only while the app is open.
 */
class LostLists {
    private val names = MutableStateFlow<List<String>>(emptyList())
    val pending: StateFlow<List<String>> = names.asStateFlow()

    fun add(name: String) = names.update { it + name }

    fun shown(name: String) = names.update { it - name }
}
