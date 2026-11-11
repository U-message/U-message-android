package org.telegram.messenger;

import android.app.backup.BackupAgentHelper;
import android.app.backup.BackupManager;

public class BackupAgent extends BackupAgentHelper {

    private static BackupManager backupManager;

    @Override
    public void onCreate() {
        // U message: login tokens (saved_tokens, saved_tokens_login) are deliberately NOT backed up, so a cloud
        // backup or device transfer cannot restore a logged-in session elsewhere. See xml/backup_rules.xml too.
    }

    public static void requestBackup() {
        if (backupManager == null) {
            backupManager = new BackupManager(ApplicationLoader.applicationContext);
        }
        backupManager.dataChanged();
    }
}
