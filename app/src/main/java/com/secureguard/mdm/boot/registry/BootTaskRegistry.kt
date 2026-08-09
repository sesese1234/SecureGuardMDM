package com.secureguard.mdm.boot.registry

import com.secureguard.mdm.boot.api.BootTask
import com.secureguard.mdm.boot.impl.FirewallVpnBootTask
import com.secureguard.mdm.boot.impl.NetfreeWatchdogBootTask
import com.secureguard.mdm.boot.impl.ShowToastOnBootTask
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BootTaskRegistry @Inject constructor(
    showToastOnBootTask: ShowToastOnBootTask,
    netfreeWatchdogBootTask: NetfreeWatchdogBootTask,
    firewallVpnBootTask: FirewallVpnBootTask
) {
    val allBootTasks: List<BootTask> = listOf(
        showToastOnBootTask,
        netfreeWatchdogBootTask,
        firewallVpnBootTask
    )
}