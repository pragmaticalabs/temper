package org.pragmatica.temper.worker;

import org.pragmatica.temper.testsupport.HermeticChildEnvironmentContract;

/// Issue #121: a poisoned host variable does not reach a child launched through the shared helper.
class HermeticChildEnvironmentTest extends HermeticChildEnvironmentContract {}
