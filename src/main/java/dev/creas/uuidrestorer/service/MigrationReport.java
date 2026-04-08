package dev.creas.uuidrestorer.service;

public record MigrationReport(FileState playerdata, FileState stats, FileState advancements, boolean changed) {
    public boolean hasConflict() {
        return playerdata.conflict() || stats.conflict() || advancements.conflict();
    }

    public String conflictState() {
        int conflicts = 0;
        String single = "none";

        if (playerdata.conflict()) {
            conflicts++;
            single = "playerdata";
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
        if (changed) {
            return "migrated";
        }
        if (playerdata.sourceExists() || stats.sourceExists() || advancements.sourceExists()) {
            return "pending";
        }
        if (playerdata.targetExists() || stats.targetExists() || advancements.targetExists()) {
            return "migrated";
        }
        return "no_data";
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
