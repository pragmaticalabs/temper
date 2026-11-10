package org.pragmatica.kiln.core.github;

import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.lang.Option;


/// One required `{context, app_id}` (spec §4.3). No app id: any app's run, or a commit status, may answer it.
public record RequiredCheck(CheckContext context, Option<AppId> appId) {
    /// Joins a context to its pinned app in a [#key]: U+001F (unit separator), a control character no [CheckContext] can
    /// hold, so no two checks share a key (an unpinned `ci@15368` and `ci` pinned to 15368 stay apart).
    public static final String SEPARATOR = "\u001F";

    /// Joins a context to its pinned app in a [#label].
    public static final String AT = "@";

    /// The cell key, kiln-core's one spelling of it: `context` when no app is pinned, `context` U+001F `appId` when one
    /// is. One context can hold two cells (two apps, or one app and none), and the key keeps them apart.
    /// [org.pragmatica.kiln.core.summary.PrSnapshot] cells and a cell [org.pragmatica.kiln.core.summary.Change]'s
    /// subject are keyed by it. Never printed: a rendering shows [#label(String)].
    public String key() {
        return appId.map(app -> context.value() + SEPARATOR + app.value())
                    .or(context.value());
    }

    /// The display form: `context`, or `context@appId` when an app is pinned.
    public String label() {
        return label(key());
    }

    /// The display form of a [#key]: its separator shown as `@`.
    public static String label(String key) {
        return key.replace(SEPARATOR, AT);
    }
}
