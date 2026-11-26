package com.mi17.nbabackscreen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ScoreStateHolder {
    private val _games = MutableStateFlow<List<NbaGame>>(emptyList())
    val games: StateFlow<List<NbaGame>> = _games

    private val _selectedGameId = MutableStateFlow<String?>(null)
    val selectedGameId: StateFlow<String?> = _selectedGameId

    fun updateGames(list: List<NbaGame>) {
        _games.value = list
        if (_selectedGameId.value == null && list.isNotEmpty()) {
            _selectedGameId.value = list.first().id
        }
    }

    fun selectGame(gameId: String) {
        _selectedGameId.value = gameId
    }

    fun currentGame(): NbaGame? {
        val list = _games.value
        val id = _selectedGameId.value
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }
}
