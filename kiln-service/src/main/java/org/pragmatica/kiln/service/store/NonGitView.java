package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.kiln.core.model.CheckoutPath;


/// One non-git directory under a root (Q-4), as the drill-down lists it.
public record NonGitView(String root, CheckoutPath path, Instant listedAt) {}
