package com.ecl.modrinth.download;

import com.ecl.modrinth.model.ContentProject;
import com.ecl.modrinth.model.ContentVersion;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;

/** Maps Modrinth API models to the legacy content-library DTOs. */
final class ModrinthContentMapper {
    private ModrinthContentMapper() {
    }

    static ContentProject toProject(ModProject project, String projectType) {
        return new ContentProject(project.projectId(), project.slug(), project.title(), project.author(),
                project.description(), project.iconUrl() == null ? null : project.iconUrl().toString(),
                project.downloads(), project.follows(), projectType);
    }

    static ContentVersion toProjectVersion(ModVersion version) {
        return new ContentVersion(version.id(), version.name(), version.versionNumber(),
                version.versionType());
    }
}
