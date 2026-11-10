package org.pragmatica.temper.cli.config;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.temper.cli.CliError;

import static org.pragmatica.lang.Option.option;


/// Where the controller listens: `api` is the HTTP base (`…/api/v1`) of every route, the event long-poll
/// included. The configured URL names the controller root; a trailing `/api/v1` (the form the m0-design §8
/// default used) is accepted and dropped, so both spellings reach the same routes.
public record ControllerEndpoint(URI api) {
    public static final String DEFAULT_URL = "http://bigboy:7070";
    private static final Pattern ROOT = Pattern.compile("^(https?)://([^/?#\\s]+)(?:/api/v1)?/*$");
    private static final String API_PATH = "/api/v1";

    public static Result<ControllerEndpoint> controllerEndpoint(String raw) {
        return matched(raw).toResult(CliError.Usage.FACTORY.apply("Invalid controller URL '" + raw
                                                                 + "' (expected http[s]://host[:port])"))
                      .flatMap(matcher -> controllerEndpoint(raw,
                                                             matcher.group(1),
                                                             matcher.group(2)));
    }

    private static Option<Matcher> matched(String raw) {
        return option(raw).map(String::trim)
                     .map(ROOT::matcher)
                     .filter(Matcher::matches);
    }

    private static Result<ControllerEndpoint> controllerEndpoint(String raw, String scheme, String authority) {
        return Network.parseURI(scheme + "://" + authority + API_PATH)
                      .map(ControllerEndpoint::new)
                      .mapError(cause -> CliError.BadSetting.FACTORY.apply("controller URL", raw, cause));
    }
}
