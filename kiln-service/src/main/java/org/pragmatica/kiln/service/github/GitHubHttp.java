package org.pragmatica.kiln.service.github;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// GitHub's REST API, read only (kiln-f1-design §3.5). Built in WP6 (`JdkGitHubHttp`). [#post] exists for GraphQL
/// queries only (#102), which are reads sent as a POST (maintainer ruling: allowed); `JdkGitHubHttp` sends one only to
/// `/graphql` and only without a mutation ([GraphQlRead]).
public interface GitHubHttp {
    Cause NO_POST = Causes.cause("this GitHub port sends no POST");
    /// `GET <pathAndQuery>`, with `If-None-Match: <etag>` when one is given.
    Promise<GhAnswer> get(String pathAndQuery, Option<String> etag);

    /// `POST <path>` with a JSON body: a GraphQL query (`/graphql`). Default: refused, so a port without it is never
    /// asked to send one.
    default Promise<GhAnswer> post(String path, String json) {
        return GitHubReadError.BadPath.FACTORY.apply(path, NO_POST).promise();
    }

    /// Whether a request can be sent now; a failure means it would be refused locally, so it must not be counted
    /// against the budget. Default: always.
    default Promise<Unit> ready() {
        return Promise.unitPromise();
    }

    /// How the requests are authenticated, shown by the budget view. A port that says nothing reads as the owner's token.
    default Credential.Mode mode() {
        return Credential.Mode.TOKEN;
    }
}
