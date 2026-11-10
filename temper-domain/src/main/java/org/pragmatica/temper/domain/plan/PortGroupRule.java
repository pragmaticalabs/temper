package org.pragmatica.temper.domain.plan;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.mvn.MvnStep;
import org.pragmatica.temper.domain.mvn.ReactorModules;


/// Whether a unit needs the port group (Q-4 ruling, bb-portgroup.py semantics): a step that requests tests
/// and whose reactor may contain a configured port-group module. A whole-reactor run, `-am` and `-amd` may
/// contain any module of the reactor, so they are members; a step with `-DskipTests` is not. With the
/// reactor known (m1-design §1.6), a module outside it cannot be built by a step on the default reactor.
///
/// An EMPTY module list disables the rule: no unit needs the port group. That is the knob for repositories
/// without port-group modules (the A4/A10 fixture repository).
public sealed interface PortGroupRule {
    static boolean needs(List<List<String>> steps, List<String> portGroupModules, Option<ReactorModules> reactor) {
        return steps.stream()
                    .map(MvnStep::mvnStep)
                    .filter(MvnStep::requestsTests)
                    .anyMatch(step -> touches(step, portGroupModules, reactor));
    }

    private static boolean touches(MvnStep step, List<String> portGroupModules, Option<ReactorModules> reactor) {
        return portGroupModules.stream()
                               .anyMatch(module -> step.mayInclude(module, reactor));
    }

    record unused() implements PortGroupRule {}
}
