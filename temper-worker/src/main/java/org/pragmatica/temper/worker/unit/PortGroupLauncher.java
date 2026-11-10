package org.pragmatica.temper.worker.unit;

import org.pragmatica.lang.Promise;


/// Starts a unit whose tests bind the fixed Ember ports through the bb-* queue's port group (m1-design §1.2).
/// It returns as soon as the holder runs and its ticket is enqueued; the grant, and with it the container, come
/// later and are the `GrantWatcher`'s to observe.
@FunctionalInterface
public interface PortGroupLauncher {
    Promise<UnitLauncher.Launch.Holding> enqueue(UnitLaunch launch, HoldRequest hold);
}
