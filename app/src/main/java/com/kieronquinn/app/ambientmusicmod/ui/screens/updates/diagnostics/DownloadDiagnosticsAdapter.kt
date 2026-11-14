package com.kieronquinn.app.ambientmusicmod.ui.screens.updates.diagnostics

import com.kieronquinn.app.ambientmusicmod.model.settings.BaseSettingsItem
import com.kieronquinn.app.ambientmusicmod.ui.base.settings.BaseSettingsAdapter
import com.kieronquinn.app.ambientmusicmod.ui.views.LifecycleAwareRecyclerView

class DownloadDiagnosticsAdapter(
    recyclerView: LifecycleAwareRecyclerView,
    override var items: List<BaseSettingsItem>
) : BaseSettingsAdapter(recyclerView, items)
