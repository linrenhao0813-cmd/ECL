package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;

import java.util.concurrent.CompletableFuture;
import java.util.Set;

public interface ModDependencyResolver {
    CompletableFuture<DependencyResolutionResult> resolve(
            ModInstanceContext instance,
            ModVersion rootVersion
    );

    CompletableFuture<DependencyResolutionResult> resolve(
            ModInstanceContext instance,
            ModVersion rootVersion,
            Set<String> selectedOptionalProjectIds
    );

    CompletableFuture<DependencyResolutionResult> resolve(
            ModInstanceContext instance,
            ModVersion rootVersion,
            Set<String> selectedOptionalProjectIds,
            ReleaseChannel releaseChannel
    );
}
