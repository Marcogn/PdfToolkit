package com.marcogn.pdftoolkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marcogn.pdftoolkit.data.settings.ViewerToolPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Settings and About can show the tool drawer's tutorial again (plan T-b): the next opening of the drawer plays it. */
@HiltViewModel
class ToolTutorialViewModel @Inject constructor(private val preferences: ViewerToolPreferences) : ViewModel() {
    fun replay() {
        viewModelScope.launch { preferences.setTutorialSeen(false) }
    }
}
