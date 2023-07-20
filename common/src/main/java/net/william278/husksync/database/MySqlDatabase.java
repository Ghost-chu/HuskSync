/*
 * This file is part of HuskSync by William278. Do not redistribute!
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  All rights reserved.
 *
 *  This source code is provided as reference to licensed individuals that have purchased the HuskSync
 *  plugin once from any of the official sources it is provided. The availability of this code does
 *  not grant you the rights to modify, re-distribute, compile or redistribute this source code or
 *  "plugin" outside this intended purpose. This license does not cover libraries developed by third
 *  parties that are utilised in the plugin.
 */

package net.william278.husksync.database;

import com.zaxxer.hikari.HikariDataSource;
import net.william278.husksync.HuskSync;
import net.william278.husksync.data.DataAdaptionException;
import net.william278.husksync.data.DataSaveCause;
import net.william278.husksync.data.UserData;
import net.william278.husksync.data.UserDataSnapshot;
import net.william278.husksync.event.DataSaveEvent;
import net.william278.husksync.player.User;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.Date;
import java.util.*;
import java.util.logging.Level;

public class MySqlDatabase extends Database {

    private static final String DATA_POOL_NAME = "HuskSyncHikariPool";
    private HikariDataSource dataSource;

    public MySqlDatabase(@NotNull HuskSync plugin) {
        super(plugin);
    }

    /**
     * Fetch the auto-closeable connection from the hikariDataSource
     *
     * @return The {@link Connection} to the MySQL database
     * @throws SQLException if the connection fails for some reason
     */
    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void initialize() throws IllegalStateException {
        // Initialize the Hikari pooled connection
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://" +
                              plugin.getSettings().getMySqlHost() +
                              ":" +
                              plugin.getSettings().getMySqlPort() +
                              "/" +
                              plugin.getSettings().getMySqlDatabase() +
                              plugin.getSettings().getMySqlConnectionParameters());

        // Authenticate with the database
        dataSource.setUsername(plugin.getSettings().getMySqlUsername());
        dataSource.setPassword(plugin.getSettings().getMySqlPassword());

        // Set connection pool options
        dataSource.setMaximumPoolSize(plugin.getSettings().getMySqlConnectionPoolSize());
        dataSource.setMinimumIdle(plugin.getSettings().getMySqlConnectionPoolIdle());
        dataSource.setMaxLifetime(plugin.getSettings().getMySqlConnectionPoolLifetime());
        dataSource.setKeepaliveTime(plugin.getSettings().getMySqlConnectionPoolKeepAlive());
        dataSource.setConnectionTimeout(plugin.getSettings().getMySqlConnectionPoolTimeout());
        dataSource.setPoolName(DATA_POOL_NAME);

        // Set additional connection pool properties
        dataSource.setDataSourceProperties(new Properties() {{
            put("cachePrepStmts", "true");
            put("prepStmtCacheSize", "250");
            put("prepStmtCacheSqlLimit", "2048");
            put("useServerPrepStmts", "true");
            put("useLocalSessionState", "true");
            put("useLocalTransactionState", "true");
            put("rewriteBatchedStatements", "true");
            put("cacheResultSetMetadata", "true");
            put("cacheServerConfiguration", "true");
            put("elideSetAutoCommits", "true");
            put("maintainTimeStats", "false");
        }});

        // Prepare database schema; make tables if they don't exist
        try (Connection connection = dataSource.getConnection()) {
            final String[] databaseSchema = getSchemaStatements("database/mysql_schema.sql");
            try (Statement statement = connection.createStatement()) {
                for (String tableCreationStatement : databaseSchema) {
                    statement.execute(tableCreationStatement);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to create database tables. Please ensure you are running MySQL v8.0+ " +
                                                "and that your connecting user account has privileges to create tables.", e);
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to establish a connection to the MySQL database. " +
                                            "Please check the supplied database credentials in the config file", e);
        }
    }

    @Override
    public void ensureUser(@NotNull User user) {
        getUser(user.uuid).ifPresentOrElse(
                existingUser -> {
                    if (!existingUser.username.equals(user.username)) {
                        // Update a user's name if it has changed in the database
                        try (Connection connection = getConnection()) {
                            try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                                    UPDATE `%users_table%`
                                    SET `username`=?
                                    WHERE `uuid`=?"""))) {

                                statement.setString(1, user.username);
                                statement.setString(2, existingUser.uuid.toString());
                                statement.executeUpdate();
                            }
                            plugin.log(Level.INFO, "Updated " + user.username + "'s name in the database (" + existingUser.username + " -> " + user.username + ")");
                        } catch (SQLException e) {
                            plugin.log(Level.SEVERE, "Failed to update a user's name on the database", e);
                        }
                    }
                },
                () -> {
                    // Insert new player data into the database
                    try (Connection connection = getConnection()) {
                        try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                                INSERT INTO `%users_table%` (`uuid`,`username`)
                                VALUES (?,?);"""))) {

                            statement.setString(1, user.uuid.toString());
                            statement.setString(2, user.username);
                            statement.executeUpdate();
                        }
                    } catch (SQLException e) {
                        plugin.log(Level.SEVERE, "Failed to insert a user into the database", e);
                    }
                });
    }

    @Override
    public Optional<User> getUser(@NotNull UUID uuid) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                    SELECT `uuid`, `username`
                    FROM `%users_table%`
                    WHERE `uuid`=?"""))) {

                statement.setString(1, uuid.toString());

                final ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    return Optional.of(new User(UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("username")));
                }
            }
        } catch (SQLException e) {
            plugin.log(Level.SEVERE, "Failed to fetch a user from uuid from the database", e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<User> getUserByName(@NotNull String username) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                    SELECT `uuid`, `username`
                    FROM `%users_table%`
                    WHERE `username`=?"""))) {
                statement.setString(1, username);

                final ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    return Optional.of(new User(UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("username")));
                }
            }
        } catch (SQLException e) {
            plugin.log(Level.SEVERE, "Failed to fetch a user by name from the database", e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<UserDataSnapshot> getCurrentUserData(@NotNull User user) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                    SELECT `version_uuid`, `timestamp`, `save_cause`, `pinned`, `data`
                    FROM `%user_data_table%`
                    WHERE `player_uuid`=?
                    ORDER BY `timestamp` DESC
                    LIMIT 1;"""))) {
                statement.setString(1, user.uuid.toString());
                final ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    final Blob blob = resultSet.getBlob("data");
                    final byte[] dataByteArray = blob.getBytes(1, (int) blob.length());
                    blob.free();
                    return Optional.of(new UserDataSnapshot(
                            UUID.fromString(resultSet.getString("version_uuid")),
                            Date.from(resultSet.getTimestamp("timestamp").toInstant()),
                            DataSaveCause.getCauseByName(resultSet.getString("save_cause")),
                            resultSet.getBoolean("pinned"),
                            plugin.getDataAdapter().fromBytes(dataByteArray)));
                }
            }
        } catch (SQLException | DataAdaptionException e) {
            plugin.log(Level.SEVERE, "Failed to fetch a user's current user data from the database", e);
        }
        return Optional.empty();
    }

    @Override
    @NotNull
    public List<UserDataSnapshot> getUserData(@NotNull User user) {
        final List<UserDataSnapshot> retrievedData = new ArrayList<>();
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                    SELECT `version_uuid`, `timestamp`, `save_cause`, `pinned`, `data`
                    FROM `%user_data_table%`
                    WHERE `player_uuid`=?
                    ORDER BY `timestamp` DESC;"""))) {
                statement.setString(1, user.uuid.toString());
                final ResultSet resultSet = statement.executeQuery();
                while (resultSet.next()) {
                    final Blob blob = resultSet.getBlob("data");
                    final byte[] dataByteArray = blob.getBytes(1, (int) blob.length());
                    blob.free();
                    final UserDataSnapshot data = new UserDataSnapshot(
                            UUID.fromString(resultSet.getString("version_uuid")),
                            Date.from(resultSet.getTimestamp("timestamp").toInstant()),
                            DataSaveCause.getCauseByName(resultSet.getString("save_cause")),
                            resultSet.getBoolean("pinned"),
                            plugin.getDataAdapter().fromBytes(dataByteArray));
                    retrievedData.add(data);
                }
                return retrievedData;
            }
        } catch (SQLException | DataAdaptionException e) {
            plugin.log(Level.SEVERE, "Failed to fetch a user's current user data from the database", e);
        }
        return retrievedData;
    }

    @Override
    public Optional<UserDataSnapshot> getUserData(@NotNull User user, @NotNull UUID versionUuid) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                    SELECT `version_uuid`, `timestamp`, `save_cause`, `pinned`, `data`
                    FROM `%user_data_table%`
                    WHERE `player_uuid`=? AND `version_uuid`=?
                    ORDER BY `timestamp` DESC
                    LIMIT 1;"""))) {
                statement.setString(1, user.uuid.toString());
                statement.setString(2, versionUuid.toString());
                final ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    final Blob blob = resultSet.getBlob("data");
                    final byte[] dataByteArray = blob.getBytes(1, (int) blob.length());
                    blob.free();
                    return Optional.of(new UserDataSnapshot(
                            UUID.fromString(resultSet.getString("version_uuid")),
                            Date.from(resultSet.getTimestamp("timestamp").toInstant()),
                            DataSaveCause.getCauseByName(resultSet.getString("save_cause")),
                            resultSet.getBoolean("pinned"),
                            plugin.getDataAdapter().fromBytes(dataByteArray)));
                }
            }
        } catch (SQLException | DataAdaptionException e) {
            plugin.log(Level.SEVERE, "Failed to fetch specific user data by UUID from the database", e);
        }
        return Optional.empty();
    }

    @Override
    protected void rotateUserData(@NotNull User user) {
        final List<UserDataSnapshot> unpinnedUserData = getUserData(user).stream()
                .filter(dataSnapshot -> !dataSnapshot.pinned()).toList();
        if (unpinnedUserData.size() > plugin.getSettings().getMaxUserDataSnapshots()) {
            try (Connection connection = getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                        DELETE FROM `%user_data_table%`
                        WHERE `player_uuid`=?
                        AND `pinned` IS FALSE
                        ORDER BY `timestamp` ASC
                        LIMIT %entry_count%;""".replace("%entry_count%",
                        Integer.toString(unpinnedUserData.size() - plugin.getSettings().getMaxUserDataSnapshots()))))) {
                    statement.setString(1, user.uuid.toString());
                    statement.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.log(Level.SEVERE, "Failed to prune user data from the database", e);
            }
        }
    }

    @Override
    public boolean deleteUserData(@NotNull User user, @NotNull UUID versionUuid) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                    DELETE FROM `%user_data_table%`
                    WHERE `player_uuid`=? AND `version_uuid`=?
                    LIMIT 1;"""))) {
                statement.setString(1, user.uuid.toString());
                statement.setString(2, versionUuid.toString());
                return statement.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.log(Level.SEVERE, "Failed to delete specific user data from the database", e);
        }
        return false;
    }

    @Override
    public void setUserData(@NotNull User user, @NotNull UserData userData,
                            @NotNull DataSaveCause saveCause) {
        final DataSaveEvent dataSaveEvent = (DataSaveEvent) plugin.getEventCannon()
                .fireDataSaveEvent(user, userData, saveCause).join();
        if (!dataSaveEvent.isCancelled()) {
            final UserData finalData = dataSaveEvent.getUserData();
            try (Connection connection = getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                        INSERT INTO `%user_data_table%`
                        (`player_uuid`,`version_uuid`,`timestamp`,`save_cause`,`data`)
                        VALUES (?,UUID(),NOW(),?,?);"""))) {
                    statement.setString(1, user.uuid.toString());
                    statement.setString(2, saveCause.name());
                    statement.setBlob(3, new ByteArrayInputStream(
                            plugin.getDataAdapter().toBytes(finalData)));
                    statement.executeUpdate();
                }
            } catch (SQLException | DataAdaptionException e) {
                plugin.log(Level.SEVERE, "Failed to set user data in the database", e);
            }
        }
        this.rotateUserData(user);
    }

    @Override
    public void pinUserData(@NotNull User user, @NotNull UUID versionUuid, boolean pinned) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(formatStatementTables("""
                    UPDATE `%user_data_table%`
                    SET `pinned`=?
                    WHERE `player_uuid`=? AND `version_uuid`=?
                    LIMIT 1;"""))) {
                statement.setBoolean(1, pinned);
                statement.setString(2, user.uuid.toString());
                statement.setString(3, versionUuid.toString());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.log(Level.SEVERE, "Failed to pin user data in the database", e);
        }
    }

    @Override
    public void wipeDatabase() {
        try (Connection connection = getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(formatStatementTables("DELETE FROM `%user_data_table%`;"));
            }
        } catch (SQLException e) {
            plugin.log(Level.SEVERE, "Failed to wipe the database", e);
        }
    }

    @Override
    public void terminate() {
        if (dataSource != null) {
            if (!dataSource.isClosed()) {
                dataSource.close();
            }
        }
    }

}
