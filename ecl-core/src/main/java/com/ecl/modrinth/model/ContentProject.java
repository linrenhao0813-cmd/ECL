package com.ecl.modrinth.model;

import com.ecl.util.TextUtil;

/** Provider-neutral content project exposed to launcher UI code. */
public record ContentProject(String projectId, String slug, String title, String author,
                             String description, String iconUrl, long downloads, long follows,
                             String projectType) {
    public ContentProject {
        projectType = projectType == null ? "" : projectType;
    }

    public ContentProject(String projectId, String slug, String title, String author,
                          String description, long downloads, long follows) {
        this(projectId, slug, title, author, description, null, downloads, follows, "");
    }

    public String getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getAuthor() { return author; }
    public String getIconUrl() { return iconUrl; }
    public long getDownloads() { return downloads; }
    public long getFollows() { return follows; }
    public String getProjectType() { return projectType; }

    @Override
    public String toString() {
        return title + (author == null || author.isBlank() ? "" : " / " + author)
                + "    下载 " + TextUtil.formatCount(downloads);
    }
}
