package dev.creas.uuidrestorer.service;

public record MigrationReport(
    FileState playerdata,
    FileState playerdataOld,
    FileState stats,
    FileState advancements,
    boolean changed,
    boolean onlineTargetAvailable
) {
    public boolean hasConflict() {
        return playerdata.conflict()
            || playerdataOld.conflict()
            || stats.conflict()
            || advancements.conflict();
    }

    public String conflictState() {
        int conflicts = 0;
        String single = "none";

        if (playerdata.conflict()) {
            conflicts++;
            single = "playerdata";
        }
        if (playerdataOld.conflict()) {
            conflicts++;
            single = "playerdata_old";
        }
        if (stats.conflict()) {
            conflicts++;
            single = "stats";
        }
        if (advancements.conflict()) {
            conflicts++;
            single = "advancements";
        }

        if (conflicts == 0) {
            return "none";
        }
        if (conflicts == 1) {
            return single;
        }
        return "multiple";
    }

    public String migrationState() {
        if (hasConflict()) {
            return "conflict";
        }
        if (!onlineTargetAvailable) {
            return anySourceExists() ? "offline_only" : "no_data";
        }
        if (changed) {
            return "migrated";
        }
        if (anySourceExists()) {
            return "pending";
        }
        if (anyTargetExists()) {
            return "migrated";
        }
        return "no_data";
    }

    private boolean anySourceExists() {
        return playerdata.sourceExists()
            || playerdataOld.sourceExists()
            || stats.sourceExists()
            || advancements.sourceExists();
    }

    private boolean anyTargetExists() {
        return playerdata.targetExists()
            || playerdataOld.targetExists()
            || stats.targetExists()
            || advancements.targetExists();
    }

    public record FileState(boolean enabled, boolean sourceExists, boolean targetExists) {
        public boolean conflict() {
            return enabled && sourceExists && targetExists;
        }

        public boolean canMove() {
            return enabled && sourceExists && !targetExists;
        }
    }
}
