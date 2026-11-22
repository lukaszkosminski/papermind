package pl.lk.papermind.ui.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import pl.lk.papermind.settings.AppSettings
import pl.lk.papermind.settings.SettingsRepository

class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}
