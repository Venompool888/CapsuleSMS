package io.github.venompool888.capsulesms.sync;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CloudSyncConfigTest {
    @Test public void showsTheNewerOfTheUploadAndPullErrors() {
        assertEquals("pull", CloudSyncConfig.newestError("upload", 10, "pull", 20));
        assertEquals("upload", CloudSyncConfig.newestError("upload", 30, "pull", 20));
    }

    @Test public void aClearedChannelDoesNotHideTheOther() {
        assertEquals("pull", CloudSyncConfig.newestError("", 0, "pull", 20));
        assertEquals("upload", CloudSyncConfig.newestError("upload", 10, "", 0));
        assertEquals("", CloudSyncConfig.newestError("", 0, "", 0));
    }
}
