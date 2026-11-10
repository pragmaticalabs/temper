package org.pragmatica.kiln.core.scan;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;


/// A local branch of a checkout with its tip (spec §5.2, "every local branch with its tip").
public record BranchTip(BranchName name, Sha tip) {}
