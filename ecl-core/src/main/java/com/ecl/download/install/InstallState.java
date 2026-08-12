package com.ecl.download.install;

import com.google.gson.JsonObject;

/**
 * Mutable hand-off between the phases of a version install workflow. Each phase task writes status
 * and progress here; the listener adapter turns those into the legacy {@code DownloadListener}
 * callbacks without the UI caring how the work is structured.
 */
public final class InstallState {

    private volatile String status = "";
    private volatile long progressDone;
    private volatile long progressTotal = -1L;
    private volatile boolean progressActive;
    private volatile JsonObject versionJson;

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    public long progressDone() {
        return progressDone;
    }

    public void setProgress(long done, long total) {
        this.progressDone = done;
        this.progressTotal = total;
    }

    public long progressTotal() {
        return progressTotal;
    }

    /** Whether byte progress should be forwarded; only the client-jar phase enables it. */
    public boolean progressActive() {
        return progressActive;
    }

    public void setProgressActive(boolean progressActive) {
        this.progressActive = progressActive;
    }

    public JsonObject versionJson() {
        return versionJson;
    }

    public void setVersionJson(JsonObject versionJson) {
        this.versionJson = versionJson;
    }
}