package com.moneymanager.session

import com.moneymanager.domain.model.DbLocation
import com.moneymanager.remotestorage.sync.RemoteDatabaseBinding
import com.moneymanager.remotestorage.sync.RemoteDatabaseController

/** The cloud binding [location] is the working copy of; a remembered binding for another file doesn't count. */
fun RemoteDatabaseController.bindingFor(location: DbLocation): RemoteDatabaseBinding? =
    activeBinding()?.takeIf { it.localCachePath == location.path }
