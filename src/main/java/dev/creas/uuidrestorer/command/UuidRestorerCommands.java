package dev.creas.uuidrestorer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.creas.uuidrestorer.UuidRestorerController.BindAttempt;
import dev.creas.uuidrestorer.UuidRestorerController.BindAttemptStatus;
import dev.creas.uuidrestorer.UuidRestorerController;
import dev.creas.uuidrestorer.UuidRestorerMod;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.service.MigrationReport;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionPreference;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionScope;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;

import java.util.Optional;

public final class UuidRestorerCommands {
    private UuidRestorerCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(createRoot("uuidrestorer"));
        dispatcher.register(createRoot("uuidrestore"));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> createRoot(String name) {
        return CommandManager.literal(name)
            .then(CommandManager.literal("version")
                .executes(context -> executeSafely(context.getSource(), "version", () -> executeVersion(context.getSource()))))
            .then(CommandManager.literal("reload")
                .executes(context -> executeSafely(context.getSource(), "reload", () -> {
                    UuidRestorerMod.controller().reload();
                    context.getSource().sendFeedback(() -> Text.literal("UUID Restorer config and bindings reloaded."), false);
                    return 1;
                })))
            .then(CommandManager.literal("status")
                .then(CommandManager.argument("nick", StringArgumentType.word())
                    .executes(context -> executeSafely(context.getSource(), "status", () -> executeStatus(context.getSource(), StringArgumentType.getString(context, "nick"))))))
            .then(CommandManager.literal("bind")
                .then(CommandManager.argument("nick", StringArgumentType.word())
                    .executes(context -> executeSafely(context.getSource(), "bind", () -> executeBind(context.getSource(), StringArgumentType.getString(context, "nick"))))))
            .then(CommandManager.literal("refresh")
                .then(CommandManager.argument("nick", StringArgumentType.word())
                    .executes(context -> executeSafely(context.getSource(), "refresh", () -> executeRefresh(context.getSource(), StringArgumentType.getString(context, "nick"))))))
            .then(CommandManager.literal("unbind")
                .then(CommandManager.argument("nick", StringArgumentType.word())
                    .executes(context -> executeSafely(context.getSource(), "unbind", () -> executeUnbind(context.getSource(), StringArgumentType.getString(context, "nick"))))))
            .then(CommandManager.literal("migrate")
                .then(CommandManager.argument("nick", StringArgumentType.word())
                    .executes(context -> executeSafely(context.getSource(), "migrate", () -> executeMigrate(context.getSource(), StringArgumentType.getString(context, "nick"))))))
            .then(CommandManager.literal("resolve")
                .then(CommandManager.argument("nick", StringArgumentType.word())
                    .then(createResolveNode(ResolutionScope.PLAYERDATA))
                    .then(createResolveNode(ResolutionScope.STATS))
                    .then(createResolveNode(ResolutionScope.ADVANCEMENTS))
                    .then(createResolveNode(ResolutionScope.ALL))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> createResolveNode(ResolutionScope scope) {
        return CommandManager.literal(scope.serializedName())
            .then(CommandManager.literal(ResolutionPreference.OFFLINE.serializedName())
                .executes(context -> executeSafely(
                    context.getSource(),
                    "resolve",
                    () -> executeResolve(
                        context.getSource(),
                        StringArgumentType.getString(context, "nick"),
                        scope,
                        ResolutionPreference.OFFLINE
                    ))))
            .then(CommandManager.literal(ResolutionPreference.PREMIUM.serializedName())
                .executes(context -> executeSafely(
                    context.getSource(),
                    "resolve",
                    () -> executeResolve(
                        context.getSource(),
                        StringArgumentType.getString(context, "nick"),
                        scope,
                        ResolutionPreference.PREMIUM
                    ))));
    }

    private static int executeSafely(ServerCommandSource source, String action, CommandAction actionHandler) {
        try {
            return actionHandler.run();
        } catch (Throwable throwable) {
            UuidRestorerMod.LOGGER.error("UUID Restorer command '{}' failed", action, throwable);
            source.sendError(Text.literal("UUID Restorer command failed: " + throwable.getClass().getSimpleName() + ". See server log."));
            return 0;
        }
    }

    private static int executeStatus(ServerCommandSource source, String nickname) {
        UuidRestorerController controller = UuidRestorerMod.controller();
        Optional<PlayerBinding> binding = controller.getBinding(nickname);
        if (binding.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No binding for " + nickname + ". Offline UUID: " + Uuids.getOfflinePlayerUuid(nickname)), false);
            return 0;
        }

        Optional<MigrationReport> report = controller.inspectBinding(source.getServer(), nickname);
        PlayerBinding playerBinding = binding.get();
        source.sendFeedback(() -> Text.literal("Binding " + nickname + " -> " + playerBinding.canonicalName + " [" + playerBinding.onlineUuid + "]"), false);
        source.sendFeedback(() -> Text.literal("Offline UUID: " + playerBinding.offlineUuid), false);
        source.sendFeedback(() -> Text.literal("Binding source: " + playerBinding.normalizedBindingSource() + " (" + playerBinding.trustLabel() + ")"), false);
        source.sendFeedback(() -> Text.literal("Cached textures: " + (playerBinding.hasTextures() ? "present" : "missing")), false);
        source.sendFeedback(() -> Text.literal("Migration state: " + playerBinding.migrationState + ", conflict: " + playerBinding.conflictState), false);
        report.ifPresent(migration -> {
            sendBucketState(source, ResolutionScope.PLAYERDATA, migration.playerdata());
            sendBucketState(source, ResolutionScope.STATS, migration.stats());
            sendBucketState(source, ResolutionScope.ADVANCEMENTS, migration.advancements());
            sendConflictHelp(source, nickname, migration);
        });
        return 1;
    }

    private static int executeVersion(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("UUID Restorer " + UuidRestorerMod.version()), false);
        return 1;
    }

    private static int executeBind(ServerCommandSource source, String nickname) {
        BindAttempt attempt = UuidRestorerMod.controller().bindNicknameDetailed(source.getServer(), nickname);
        if (attempt.status() == BindAttemptStatus.NOT_FOUND) {
            source.sendError(Text.literal("Premium profile not found for " + nickname + "."));
            return 0;
        }
        if (attempt.status() == BindAttemptStatus.LOOKUP_FAILED) {
            source.sendError(Text.literal("Premium lookup failed for " + nickname + ": " + attempt.details()));
            return 0;
        }

        PlayerBinding playerBinding = attempt.binding().orElseThrow();
        source.sendFeedback(() -> Text.literal("Bound " + nickname + " to premium UUID " + playerBinding.onlineUuid), false);
        source.sendFeedback(() -> Text.literal("Binding source: " + playerBinding.normalizedBindingSource() + " (" + playerBinding.trustLabel() + ")"), false);
        source.sendFeedback(() -> Text.literal("Conflict state: " + playerBinding.conflictState + ", migration state: " + playerBinding.migrationState), false);
        UuidRestorerMod.controller().inspectBinding(source.getServer(), nickname).ifPresent(migration -> sendConflictHelp(source, nickname, migration));
        return 1;
    }

    private static int executeRefresh(ServerCommandSource source, String nickname) {
        Optional<PlayerBinding> binding = UuidRestorerMod.controller().refreshBinding(source.getServer(), nickname);
        if (binding.isEmpty()) {
            source.sendError(Text.literal("No refreshable binding for " + nickname + "."));
            return 0;
        }

        PlayerBinding playerBinding = binding.get();
        source.sendFeedback(() -> Text.literal("Refreshed " + nickname + " -> " + playerBinding.canonicalName + " [" + playerBinding.onlineUuid + "]"), false);
        source.sendFeedback(() -> Text.literal("Cached textures: " + (playerBinding.hasTextures() ? "present" : "missing")), false);
        source.sendFeedback(() -> Text.literal("Binding source: " + playerBinding.normalizedBindingSource() + " (" + playerBinding.trustLabel() + ")"), false);
        return 1;
    }

    private static int executeUnbind(ServerCommandSource source, String nickname) {
        boolean removed = UuidRestorerMod.controller().removeBinding(nickname);
        if (!removed) {
            source.sendError(Text.literal("No binding for " + nickname + "."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Removed binding for " + nickname + "."), false);
        return 1;
    }

    private static int executeMigrate(ServerCommandSource source, String nickname) {
        Optional<MigrationReport> report = UuidRestorerMod.controller().migrateBinding(source.getServer(), nickname);
        if (report.isEmpty()) {
            source.sendError(Text.literal("No binding for " + nickname + "."));
            return 0;
        }

        MigrationReport migrationReport = report.get();
        source.sendFeedback(() -> Text.literal("Migration state: " + migrationReport.migrationState() + ", conflict: " + migrationReport.conflictState()), false);
        sendConflictHelp(source, nickname, migrationReport);
        return migrationReport.hasConflict() ? 0 : 1;
    }

    private static int executeResolve(ServerCommandSource source, String nickname, ResolutionScope scope, ResolutionPreference preference) {
        Optional<MigrationReport> report = UuidRestorerMod.controller().resolveBindingConflict(source.getServer(), nickname, scope, preference);
        if (report.isEmpty()) {
            source.sendError(Text.literal("No binding for " + nickname + "."));
            return 0;
        }

        MigrationReport migrationReport = report.get();
        if (!migrationReport.changed()) {
            source.sendError(Text.literal("No applicable files were changed for " + scope.serializedName() + " using " + preference.serializedName() + ". Check /uuidrestorer status " + nickname + "."));
            sendConflictHelp(source, nickname, migrationReport);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Resolved " + scope.serializedName() + " in favor of " + preference.serializedName() + " data."), false);
        source.sendFeedback(() -> Text.literal("Migration state: " + migrationReport.migrationState() + ", conflict: " + migrationReport.conflictState()), false);
        sendConflictHelp(source, nickname, migrationReport);
        return migrationReport.hasConflict() ? 0 : 1;
    }

    private static void sendBucketState(ServerCommandSource source, ResolutionScope scope, MigrationReport.FileState state) {
        source.sendFeedback(
            () -> Text.literal(
                scope.serializedName()
                    + " offline=" + state.sourceExists()
                    + " premium=" + state.targetExists()
                    + " conflict=" + state.conflict()
            ),
            false
        );
    }

    private static void sendConflictHelp(ServerCommandSource source, String nickname, MigrationReport report) {
        if (!report.hasConflict()) {
            return;
        }

        source.sendFeedback(() -> Text.literal("Resolve with: /uuidrestorer resolve " + nickname + " <playerdata|stats|advancements|all> <offline|premium>"), false);
        if (report.playerdata().conflict()) {
            source.sendFeedback(() -> Text.literal("playerdata conflict: /uuidrestorer resolve " + nickname + " playerdata <offline|premium>"), false);
        }
        if (report.stats().conflict()) {
            source.sendFeedback(() -> Text.literal("stats conflict: /uuidrestorer resolve " + nickname + " stats <offline|premium>"), false);
        }
        if (report.advancements().conflict()) {
            source.sendFeedback(() -> Text.literal("advancements conflict: /uuidrestorer resolve " + nickname + " advancements <offline|premium>"), false);
        }
        source.sendFeedback(() -> Text.literal("offline = keep old offline UUID data and move it onto the premium UUID; premium = keep the current premium UUID data."), false);
    }

    @FunctionalInterface
    private interface CommandAction {
        int run();
    }
}
