package com.ecl.modrinth.model;

/** Provider-neutral content version exposed to launcher UI code. */
public record ContentVersion(String versionId, String name, String versionNumber, String versionType) {
    @Override
    public String toString() {
        String displayVersion = versionNumber == null || versionNumber.isBlank()
                ? versionId : versionNumber;
        String displayName = name == null || name.isBlank() || name.equals(displayVersion)
                ? "" : name + " · ";
        String displayType = versionType == null || versionType.isBlank()
                ? "" : " · " + versionType;
        return displayName + displayVersion + displayType;
    }
}
