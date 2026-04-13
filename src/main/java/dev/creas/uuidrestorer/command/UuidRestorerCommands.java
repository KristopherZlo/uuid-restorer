package dev.creas.uuidrestorer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.creas.uuidrestorer.UuidRestorerController;
import dev.creas.uuidrestorer.UuidRestorerController.BindAttempt;
import dev.creas.uuidrestorer.UuidRestorerController.BindAttemptStatus;
import dev.creas.uuidrestorer.UuidRestorerMod;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.service.MigrationReport;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionPreference;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionScope;
import dev.creas.uuidrestorer.service.PlayerProfileService;
import dev.creas.uuidrestorer.service.UuidRestorerTrace;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Uuids;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class UuidRestorerCommands {
    private UuidRestorerCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
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
                    context.getSource().sendFeedback(() -> header("Reloaded").append(Text.literal(" config and bindings").formatted(Formatting.GRAY)), false);
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
            UuidRestorerTrace.log("command", "execute action=" + action + " source=" + source.getName());
            return actionHandler.run();
        } catch (Throwable throwable) {
            UuidRestorerTrace.log("command", "execute failed action=" + action + " source=" + source.getName(), throwable);
            UuidRestorerMod.LOGGER.error("UUID Restorer command '{}' failed", action, throwable);
            source.sendError(
                header("Error")
                    .append(Text.literal(" ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(throwable.getClass().getSimpleName() + ". See server log.").formatted(Formatting.RED))
            );
            return 0;
        }
    }

    private static int executeStatus(ServerCommandSource source, String nickname) {
        UuidRestorerTrace.log("command", "status nickname=" + nickname + " source=" + source.getName());
        UuidRestorerController controller = UuidRestorerMod.controller();
        Optional<PlayerBinding> binding = controller.getBinding(nickname);

        source.sendFeedback(() -> header("Status").append(Text.literal(" | ").formatted(Formatting.DARK_GRAY)).append(Text.literal(nickname).formatted(Formatting.AQUA, Formatting.BOLD)), false);
        if (binding.isEmpty()) {
            source.sendFeedback(() -> line("Stored binding", badge("NONE", Formatting.DARK_GRAY)), false);
            source.sendFeedback(() -> line("Offline UUID", uuidValue(Uuids.getOfflinePlayerUuid(nickname), Formatting.GRAY)), false);
            source.sendFeedback(() -> line("Quick action", commandButton("bind premium", "/uuidrestorer bind " + nickname, Formatting.GREEN)), false);
            return 0;
        }

        PlayerBinding playerBinding = binding.get();
        MigrationReport migration = controller.inspectBinding(source.getServer(), nickname).orElse(null);

        source.sendFeedback(() -> line("Premium UUID", playerBinding.onlineUuid == null ? badge("OFFLINE ONLY", Formatting.YELLOW) : uuidValue(playerBinding.onlineUuid, Formatting.GREEN)), false);
        source.sendFeedback(() -> line("Offline UUID", uuidValue(playerBinding.offlineUuid, Formatting.GRAY)), false);
        source.sendFeedback(() -> line("Lookup", lookupStateValue(playerBinding.normalizedLookupState())), false);
        source.sendFeedback(() -> line("Trust", trustValue(playerBinding)), false);
        source.sendFeedback(() -> line("Skin cache", cacheValue(playerBinding)), false);
        source.sendFeedback(() -> line("Migration", migration == null ? badge(playerBinding.migrationState, Formatting.GRAY) : migrationValue(migration)), false);

        source.sendFeedback(() -> Text.literal("Data Buckets").formatted(Formatting.GOLD, Formatting.BOLD), false);
        if (migration != null) {
            sendBucketState(source, "playerdata.dat", migration.playerdata());
            sendBucketState(source, "playerdata.dat_old", migration.playerdataOld());
            sendBucketState(source, "stats", migration.stats());
            sendBucketState(source, "advancements", migration.advancements());
            sendConflictHelp(source, nickname, migration);
        }
        return 1;
    }

    private static int executeVersion(ServerCommandSource source) {
        source.sendFeedback(() -> header("Version").append(Text.literal(" ").formatted(Formatting.DARK_GRAY)).append(Text.literal(UuidRestorerMod.version()).formatted(Formatting.AQUA)), false);
        return 1;
    }

    private static int executeBind(ServerCommandSource source, String nickname) {
        UuidRestorerTrace.log("command", "bind nickname=" + nickname + " source=" + source.getName());
        UuidRestorerController controller = UuidRestorerMod.controller();
        BindAttempt attempt = controller.bindNicknameDetailed(source.getServer(), nickname);
        UuidRestorerTrace.log(
            "command",
            "bind attempt nickname=" + nickname
                + " status=" + attempt.status()
                + " details=" + attempt.details()
                + " binding=" + attempt.binding().map(UuidRestorerTrace::describeBinding).orElse("empty")
        );
        if (attempt.status() == BindAttemptStatus.NOT_FOUND) {
            source.sendError(header("Bind").append(Text.literal(" ").formatted(Formatting.DARK_GRAY)).append(Text.literal("premium profile not found for " + nickname).formatted(Formatting.RED)));
            return 0;
        }
        if (attempt.status() == BindAttemptStatus.LOOKUP_FAILED) {
            source.sendError(
                header("Bind")
                    .append(Text.literal(" ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("premium lookup failed for " + nickname + ": " + attempt.details()).formatted(Formatting.RED))
            );
            return 0;
        }

        PlayerBinding bound = attempt.binding().orElseThrow();
        MigrationReport migration = controller.migrateBinding(source.getServer(), nickname)
            .or(() -> controller.inspectBinding(source.getServer(), nickname))
            .orElseThrow();
        UuidRestorerTrace.migration("command", "bind.afterMigrate", migration);

        boolean autoPreferredPremium = false;
        if (migration.hasConflict()) {
            migration = controller.resolveBindingConflict(source.getServer(), nickname, ResolutionScope.ALL, ResolutionPreference.PREMIUM)
                .orElse(migration);
            autoPreferredPremium = migration.changed() || !migration.hasConflict();
            UuidRestorerTrace.migration("command", "bind.afterResolvePremium", migration);
        }

        PlayerBinding current = controller.getBinding(nickname).orElse(bound);
        ServerPlayerEntity onlinePlayer = findOnlinePlayer(source, current, nickname);
        boolean liveSkinRefresh = false;
        boolean relogRequired = false;
        UuidRestorerTrace.log(
            "command",
            "bind.onlinePlayer nickname=" + nickname
                + " currentBinding=" + UuidRestorerTrace.describeBinding(current)
                + " onlinePlayer=" + (onlinePlayer == null ? "null" : onlinePlayer.getNameForScoreboard() + "/" + onlinePlayer.getUuid())
        );
        if (onlinePlayer != null) {
            if (Objects.equals(onlinePlayer.getUuid(), current.onlineUuid)) {
                PlayerProfileService.refreshStoredTextures(onlinePlayer);
                liveSkinRefresh = true;
            } else {
                relogRequired = true;
            }
        }

        MigrationReport finalMigration = controller.inspectBinding(source.getServer(), nickname).orElse(migration);
        UuidRestorerTrace.migration("command", "bind.finalMigration", finalMigration);
        UuidRestorerTrace.log(
            "command",
            "bind.result nickname=" + nickname
                + " liveSkinRefresh=" + liveSkinRefresh
                + " relogRequired=" + relogRequired
                + " autoPreferredPremium=" + autoPreferredPremium
        );
        source.sendFeedback(() -> header("Bind").append(Text.literal(" | ").formatted(Formatting.DARK_GRAY)).append(Text.literal(nickname).formatted(Formatting.AQUA, Formatting.BOLD)), false);
        source.sendFeedback(() -> line("Premium UUID", uuidValue(current.onlineUuid, Formatting.GREEN)), false);
        source.sendFeedback(() -> line("Profile", Text.literal(current.canonicalName).formatted(Formatting.GREEN)), false);
        source.sendFeedback(() -> line("Data policy", badge("PREMIUM PREFERRED", Formatting.GREEN, Formatting.BOLD)), false);
        if (autoPreferredPremium) {
            source.sendFeedback(() -> line("Conflicts", Text.literal("auto-resolved in favor of premium data").formatted(Formatting.GREEN)), false);
        } else if (finalMigration.hasConflict()) {
            source.sendFeedback(() -> line("Conflicts", Text.literal("still present, manual override may be needed").formatted(Formatting.RED)), false);
        } else {
            source.sendFeedback(() -> line("Conflicts", badge("NONE", Formatting.GREEN)), false);
        }
        if (liveSkinRefresh) {
            source.sendFeedback(() -> line("Live update", Text.literal("skin refresh was sent to the current session").formatted(Formatting.GREEN)), false);
        } else if (relogRequired) {
            source.sendFeedback(() -> line("Next step", Text.literal("relog once to switch UUID and inventory to premium").formatted(Formatting.YELLOW)), false);
        } else {
            source.sendFeedback(() -> line("Next step", Text.literal("join with " + nickname + " to use the premium profile").formatted(Formatting.AQUA)), false);
        }
        if (finalMigration.hasConflict()) {
            sendConflictHelp(source, nickname, finalMigration);
            return 0;
        }
        return 1;
    }

    private static int executeRefresh(ServerCommandSource source, String nickname) {
        UuidRestorerTrace.log("command", "refresh nickname=" + nickname + " source=" + source.getName());
        Optional<PlayerBinding> binding = UuidRestorerMod.controller().refreshBinding(source.getServer(), nickname);
        if (binding.isEmpty()) {
            source.sendError(header("Refresh").append(Text.literal(" ").formatted(Formatting.DARK_GRAY)).append(Text.literal("no stored binding for " + nickname).formatted(Formatting.RED)));
            return 0;
        }

        PlayerBinding playerBinding = binding.get();
        source.sendFeedback(() -> header("Refresh").append(Text.literal(" | ").formatted(Formatting.DARK_GRAY)).append(Text.literal(nickname).formatted(Formatting.AQUA, Formatting.BOLD)), false);
        source.sendFeedback(() -> line("Premium UUID", playerBinding.onlineUuid == null ? badge("OFFLINE ONLY", Formatting.YELLOW) : uuidValue(playerBinding.onlineUuid, Formatting.GREEN)), false);
        source.sendFeedback(() -> line("Lookup", lookupStateValue(playerBinding.normalizedLookupState())), false);
        source.sendFeedback(() -> line("Skin cache", cacheValue(playerBinding)), false);
        return 1;
    }

    private static int executeUnbind(ServerCommandSource source, String nickname) {
        UuidRestorerTrace.log("command", "unbind nickname=" + nickname + " source=" + source.getName());
        boolean removed = UuidRestorerMod.controller().removeBinding(nickname);
        if (!removed) {
            source.sendError(header("Unbind").append(Text.literal(" ").formatted(Formatting.DARK_GRAY)).append(Text.literal("no stored binding for " + nickname).formatted(Formatting.RED)));
            return 0;
        }

        source.sendFeedback(() -> header("Unbind").append(Text.literal(" ").formatted(Formatting.DARK_GRAY)).append(Text.literal("removed stored binding for " + nickname).formatted(Formatting.YELLOW)), false);
        return 1;
    }

    private static int executeMigrate(ServerCommandSource source, String nickname) {
        UuidRestorerTrace.log("command", "migrate nickname=" + nickname + " source=" + source.getName());
        Optional<MigrationReport> report = UuidRestorerMod.controller().migrateBinding(source.getServer(), nickname);
        if (report.isEmpty()) {
            source.sendError(header("Migrate").append(Text.literal(" ").formatted(Formatting.DARK_GRAY)).append(Text.literal("no stored binding for " + nickname).formatted(Formatting.RED)));
            return 0;
        }

        MigrationReport migrationReport = report.get();
        source.sendFeedback(() -> header("Migrate").append(Text.literal(" | ").formatted(Formatting.DARK_GRAY)).append(Text.literal(nickname).formatted(Formatting.AQUA, Formatting.BOLD)), false);
        source.sendFeedback(() -> line("Result", migrationValue(migrationReport)), false);
        sendConflictHelp(source, nickname, migrationReport);
        return migrationReport.hasConflict() ? 0 : 1;
    }

    private static int executeResolve(ServerCommandSource source, String nickname, ResolutionScope scope, ResolutionPreference preference) {
        UuidRestorerTrace.log(
            "command",
            "resolve nickname=" + nickname
                + " scope=" + scope.serializedName()
                + " preference=" + preference.serializedName()
                + " source=" + source.getName()
        );
        Optional<MigrationReport> report = UuidRestorerMod.controller().resolveBindingConflict(source.getServer(), nickname, scope, preference);
        if (report.isEmpty()) {
            source.sendError(header("Resolve").append(Text.literal(" ").formatted(Formatting.DARK_GRAY)).append(Text.literal("no stored binding for " + nickname).formatted(Formatting.RED)));
            return 0;
        }

        MigrationReport migrationReport = report.get();
        if (!migrationReport.changed()) {
            source.sendError(
                header("Resolve")
                    .append(Text.literal(" ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("no files changed for " + scope.serializedName() + " -> " + preference.serializedName()).formatted(Formatting.RED))
            );
            sendConflictHelp(source, nickname, migrationReport);
            return 0;
        }

        source.sendFeedback(() -> header("Resolve").append(Text.literal(" | ").formatted(Formatting.DARK_GRAY)).append(Text.literal(nickname).formatted(Formatting.AQUA, Formatting.BOLD)), false);
        source.sendFeedback(() -> line("Choice", badge(preference.serializedName().toUpperCase(), preference == ResolutionPreference.PREMIUM ? Formatting.GREEN : Formatting.YELLOW, Formatting.BOLD)), false);
        source.sendFeedback(() -> line("Scope", Text.literal(scope.serializedName()).formatted(Formatting.AQUA)), false);
        source.sendFeedback(() -> line("Result", migrationValue(migrationReport)), false);
        sendConflictHelp(source, nickname, migrationReport);
        return migrationReport.hasConflict() ? 0 : 1;
    }

    private static void sendBucketState(ServerCommandSource source, String scope, MigrationReport.FileState state) {
        source.sendFeedback(
            () -> Text.literal("* ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(scope).formatted(Formatting.AQUA))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(bucketBadge(state))
                .append(Text.literal("  offline=").formatted(Formatting.DARK_GRAY))
                .append(boolValue(state.sourceExists()))
                .append(Text.literal(" premium=").formatted(Formatting.DARK_GRAY))
                .append(boolValue(state.targetExists())),
            false
        );
    }

    private static void sendConflictHelp(ServerCommandSource source, String nickname, MigrationReport report) {
        if (!report.hasConflict()) {
            return;
        }

        source.sendFeedback(() -> line(
            "Advanced",
            commandButton("keep premium", "/uuidrestorer resolve " + nickname + " all premium", Formatting.GREEN)
                .append(Text.literal(" ").formatted(Formatting.DARK_GRAY))
                .append(commandButton("keep offline", "/uuidrestorer resolve " + nickname + " all offline", Formatting.YELLOW))
        ), false);
        source.sendFeedback(() -> Text.literal("* ").formatted(Formatting.DARK_GRAY)
            .append(Text.literal("offline").formatted(Formatting.YELLOW, Formatting.BOLD))
            .append(Text.literal(" moves old offline data onto the premium UUID; ").formatted(Formatting.GRAY))
            .append(Text.literal("premium").formatted(Formatting.GREEN, Formatting.BOLD))
            .append(Text.literal(" keeps the current premium UUID data.").formatted(Formatting.GRAY)), false);
    }

    private static ServerPlayerEntity findOnlinePlayer(ServerCommandSource source, PlayerBinding binding, String nickname) {
        if (binding.canonicalName != null) {
            ServerPlayerEntity canonical = source.getServer().getPlayerManager().getPlayer(binding.canonicalName);
            if (canonical != null) {
                return canonical;
            }
        }
        return source.getServer().getPlayerManager().getPlayer(nickname);
    }

    private static MutableText header(String title) {
        return Text.literal("UUID Restorer").formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal(title).formatted(Formatting.WHITE, Formatting.BOLD));
    }

    private static MutableText line(String label, MutableText value) {
        return Text.literal("* ").formatted(Formatting.DARK_GRAY)
            .append(Text.literal(label).formatted(Formatting.GRAY))
            .append(Text.literal(": ").formatted(Formatting.DARK_GRAY))
            .append(value);
    }

    private static MutableText badge(String text, Formatting... formatting) {
        return Text.literal("[" + text + "]").formatted(formatting);
    }

    private static MutableText commandButton(String label, String command, Formatting color) {
        return Text.literal("[" + label + "]")
            .formatted(color, Formatting.BOLD)
            .styled(style -> style.withClickEvent(new ClickEvent.SuggestCommand(command)));
    }

    private static MutableText uuidValue(UUID uuid, Formatting formatting) {
        return uuid == null
            ? badge("NONE", Formatting.DARK_GRAY)
            : Text.literal(uuid.toString()).formatted(formatting);
    }

    private static MutableText boolValue(boolean value) {
        return Text.literal(value ? "yes" : "no").formatted(value ? Formatting.GREEN : Formatting.DARK_GRAY);
    }

    private static MutableText lookupStateValue(PlayerBinding.LookupState state) {
        return switch (state) {
            case FOUND -> badge("FOUND", Formatting.GREEN, Formatting.BOLD);
            case NOT_FOUND -> badge("NOT FOUND", Formatting.YELLOW, Formatting.BOLD);
            case LOOKUP_FAILED -> badge("LOOKUP FAILED", Formatting.RED, Formatting.BOLD);
        };
    }

    private static MutableText trustValue(PlayerBinding binding) {
        return binding.isTrusted()
            ? badge("TRUSTED", Formatting.GREEN, Formatting.BOLD)
            : badge("AUTO", Formatting.YELLOW, Formatting.BOLD);
    }

    private static MutableText cacheValue(PlayerBinding binding) {
        return binding.hasTextures()
            ? badge("CACHED", Formatting.GREEN, Formatting.BOLD)
            : badge("MISSING", Formatting.RED, Formatting.BOLD);
    }

    private static MutableText migrationValue(MigrationReport report) {
        MutableText state = switch (report.migrationState()) {
            case "migrated" -> badge("MIGRATED", Formatting.GREEN, Formatting.BOLD);
            case "conflict" -> badge("CONFLICT", Formatting.RED, Formatting.BOLD);
            case "pending" -> badge("PENDING", Formatting.YELLOW, Formatting.BOLD);
            case "offline_only" -> badge("OFFLINE ONLY", Formatting.YELLOW, Formatting.BOLD);
            case "no_data" -> badge("NO DATA", Formatting.DARK_GRAY, Formatting.BOLD);
            default -> badge(report.migrationState().toUpperCase(), Formatting.GRAY, Formatting.BOLD);
        };

        if (!report.hasConflict()) {
            return state;
        }
        return state
            .append(Text.literal(" ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("(" + report.conflictState() + ")").formatted(Formatting.RED));
    }

    private static MutableText bucketBadge(MigrationReport.FileState state) {
        if (state.conflict()) {
            return badge("CONFLICT", Formatting.RED, Formatting.BOLD);
        }
        if (state.targetExists()) {
            return badge("PREMIUM", Formatting.GREEN, Formatting.BOLD);
        }
        if (state.sourceExists()) {
            return badge("OFFLINE", Formatting.YELLOW, Formatting.BOLD);
        }
        return badge("EMPTY", Formatting.DARK_GRAY, Formatting.BOLD);
    }

    @FunctionalInterface
    private interface CommandAction {
        int run();
    }
}
