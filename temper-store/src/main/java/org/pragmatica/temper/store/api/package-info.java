/// The store's contract (m0-design §5.3): repository interfaces, the row records they exchange, and
/// [org.pragmatica.temper.store.api.StoreError]. The controller codes against this package only; the Postgres
/// implementation lives in `org.pragmatica.temper.store.pg`, which also opens the store (`Postgres.connect`) and
/// maps these types to and from the wire forms (ruling R-10: this package names no protocol or adapter type).
package org.pragmatica.temper.store.api;
