package fr.skytasul.quests.players;

import com.minepalm.library.database.JavaDatabase;
import com.minepalm.library.database.impl.internal.MySQLDB;
import fr.skytasul.quests.api.QuestsPlugin;
import fr.skytasul.quests.api.data.SQLDataSaver;
import fr.skytasul.quests.api.data.SavableData;
import fr.skytasul.quests.api.players.PlayerAccount;
import fr.skytasul.quests.api.players.PlayerQuestEntryData;
import fr.skytasul.quests.api.pools.QuestPool;
import fr.skytasul.quests.api.quests.Quest;
import fr.skytasul.quests.api.stages.StageController;
import fr.skytasul.quests.api.utils.CustomizedObjectTypeAdapter;
import fr.skytasul.quests.players.accounts.AbstractAccount;
import fr.skytasul.quests.utils.HikariDataSourceWrapper;
import fr.skytasul.quests.utils.QuestUtils;
import fr.skytasul.quests.utils.ThrowingConsumer;
import org.apache.commons.lang.StringUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class PlayersManagerDB extends AbstractPlayersManager {

	/**
	 * 테이블 명: player_accounts
	 */
	public final String ACCOUNTS_TABLE;
	/**
	 * 테이블 명: player_quests
	 */
	public final String QUESTS_ENTRIES_TABLE;
	/**
	 * 테이블 명: player_pools (안씀)
	 */
	public final String POOLS_DATAS_TABLE;

	//private final HikariDataSourceWrapper db;
	private final JavaDatabase<Connection> palmLibrary;

	private final Map<SavableData<?>, SQLDataSaver<?>> accountDatas = new HashMap<>();
	private String getAccountDatas;
	private String resetAccountDatas;

	/* Accounts statements */
	private String getAccountsIDs;
	private String insertAccount;
	private String deleteAccount;

	/* Quest datas statements */
	private String insertQuestData;
	private String removeQuestData;
	private String getQuestsData;

	private String removeExistingQuestDatas;
	private String removeExistingPoolDatas;

	private String updateFinished;
	private String updateTimer;
	private String updateBranch;
	private String updateStage;
	private String updateDatas;
	private String updateFlow;

	/* Pool datas statements */
	private String insertPoolData;
	private String removePoolData;
	private String getPoolData;
	private String getPoolAccountData;

	private String updatePoolLastGive;
	private String updatePoolCompletedQuests;

	public PlayersManagerDB(ConfigurationSection tableSection, MySQLDB palmLibraryDatabase) {
		//this.db = db;
		this.palmLibrary = palmLibraryDatabase.java();
		ACCOUNTS_TABLE = tableSection.getString("tables.playerAccounts");
		QUESTS_ENTRIES_TABLE = tableSection.getString("tables.playerQuests");
		POOLS_DATAS_TABLE = tableSection.getString("tables.playerPools");
	}

	//public HikariDataSourceWrapper getDatabase() {
	//	return db;
	//}

	@Override
	public void addAccountData(SavableData<?> data) {
		super.addAccountData(data);
		accountDatas.put(data,
				new SQLDataSaver<>(data, "UPDATE " + ACCOUNTS_TABLE + " SET " + data.getColumnName() + " = ? WHERE id = ?"));
		getAccountDatas = accountDatas.keySet()
				.stream()
				.map(SavableData::getColumnName)
				.collect(Collectors.joining(", ", "SELECT ", " FROM " + ACCOUNTS_TABLE + " WHERE id = ?"));
		resetAccountDatas = accountDatas.values()
				.stream()
				.map(x -> x.getWrappedData().getColumnName() + " = " + x.getDefaultValueString())
				.collect(Collectors.joining(", ", "UPDATE " + ACCOUNTS_TABLE + " SET ", " WHERE id = ?"));
	}

	private void retrievePlayerQuestEntries(Connection con, PlayerAccountImplementation acc) throws SQLException {

		try (PreparedStatement statement = con.prepareStatement(getQuestsData)) {
			statement.setInt(1, acc.index);
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				int questID = result.getInt("quest_id");

				//TODO: 플레이어 퀘스트 데이터를 입력하는 부분인데, 로컬에서 저장하지 않고 매번 데이터베이스로부터 불러옴.
				// 해당 부분 퀘스트 수정할때 로컬에서 저장하고, 데이터 갱신 부랑 로컬 캐싱 부분을 나누어야함.
				acc.currentQuests.put(questID, new MySQLPlayerQuestDataEntry(acc, questID, result));
			}
			result.close();
		}

		try (PreparedStatement statement = con.prepareStatement(getPoolData)) {
			statement.setInt(1, acc.index);
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				int poolID = result.getInt("pool_id");
				String completedQuests = result.getString("completed_quests");
				if (StringUtils.isEmpty(completedQuests)) completedQuests = null;
				acc.poolDatas.put(poolID, new PlayerPoolDatasDB(acc, poolID, result.getLong("last_give"), completedQuests == null ? new HashSet<>() : Arrays.stream(completedQuests.split(";")).map(Integer::parseInt).collect(Collectors.toSet())));
			}
			result.close();
		}

		if (getAccountDatas != null) {
			try (PreparedStatement statement = con.prepareStatement(getAccountDatas)) {
				statement.setInt(1, acc.index);
				ResultSet result = statement.executeQuery();
				result.next();
				for (SQLDataSaver<?> data : accountDatas.values()) {
					acc.additionalDatas.put(data.getWrappedData(), data.getFromResultSet(result));
				}
				result.close();
			}
		}

	}

	@Override
	public CompletableFuture<PlayerAccount> load(AccountFetchRequest request) {
		return palmLibrary.executeAsync(connection -> {
			String uuid = request.getUniqueId().toString();
			try (PreparedStatement statement = connection.prepareStatement(getAccountsIDs)) {
				statement.setString(1, uuid);
				ResultSet result = statement.executeQuery();
				while (result.next()) {
					AbstractAccount abs = createAccountFromIdentifier(result.getString("identifier"));
					if (abs.isCurrent()) {
						PlayerAccountImplementation account = new PlayerAccountDB(abs, result.getInt("id"));
						result.close();
						retrievePlayerQuestEntries(connection, account);
						request.loaded(account, "database");
						return account;
					}
				}
			}

			if (request.mustCreateMissing()) {
				try (PreparedStatement statement =
							 connection.prepareStatement(insertAccount, Statement.RETURN_GENERATED_KEYS)) {
					AbstractAccount absacc = super.createAbstractAccount(request.getUniqueId());
					statement.setString(1, absacc.getIdentifier());
					statement.setString(2, uuid);
					statement.executeUpdate();
					ResultSet result = statement.getGeneratedKeys();
					if (!result.next())
						throw new SQLException("The plugin has not been able to create a player account.");
					int index = result.getInt(1); // some drivers don't return a ResultSet with correct column names
					request.created(new PlayerAccountDB(absacc, index));
				}
			} else {
				request.notLoaded();
			}
			return request.getAccount();
		});
	}

	@Override
	protected CompletableFuture<Void> removeAccount(PlayerAccountImplementation acc) {
		return palmLibrary.runAsync(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(deleteAccount)) {
				statement.setInt(1, acc.index);
				statement.executeUpdate();
			}
		});
	}

	@Override
	public PlayerQuestEntryDataImplementation createPlayerQuestDatas(PlayerAccountImplementation acc, Quest quest) {
		return new MySQLPlayerQuestDataEntry(acc, quest.getId());
	}

	@Override
	public CompletableFuture<Void> playerQuestDataRemoved(PlayerQuestEntryDataImplementation datas) {
		return palmLibrary.runAsync(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(removeQuestData)) {
				((MySQLPlayerQuestDataEntry) datas).stop();
				statement.setInt(1, datas.acc.index);
				statement.setInt(2, datas.questID);
				statement.executeUpdate();
			}
		});
	}

	@Override
	public PlayerPoolDatasImplementation createPlayerPoolDatas(PlayerAccountImplementation acc, QuestPool pool) {
		return new PlayerPoolDatasDB(acc, pool.getId());
	}

	@Override
	public CompletableFuture<Void> playerPoolDataRemoved(PlayerPoolDatasImplementation datas) {
		return palmLibrary.runAsync(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(removePoolData)) {
				statement.setInt(1, datas.acc.index);
				statement.setInt(2, datas.poolID);
				statement.executeUpdate();
			}
		});
	}

	@Override
	public CompletableFuture<Integer> removeQuestDatas(Quest quest) {
		return palmLibrary.executeAsync(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(removeExistingQuestDatas)) {
				for (PlayerAccountImplementation acc : cachedAccounts.values()) {
					MySQLPlayerQuestDataEntry datas = (MySQLPlayerQuestDataEntry) acc.removeQuestDatasSilently(quest.getId());
					if (datas != null) datas.stop();
				}
				statement.setInt(1, quest.getId());
				int amount = statement.executeUpdate();
				QuestsPlugin.getPlugin().getLoggerExpanded().debug("Removed " + amount + " in-database quest datas for quest " + quest.getId());
				return amount;
			}
		});
	}

	@Override
	public CompletableFuture<Integer> removePoolDatas(QuestPool pool) {
		return palmLibrary.executeAsync(connection -> {
			PreparedStatement statement = connection.prepareStatement(removeExistingPoolDatas);
			for (PlayerAccountImplementation acc : cachedAccounts.values()) {
				acc.removePoolDatasSilently(pool.getId());
			}
			statement.setInt(1, pool.getId());
			int amount = statement.executeUpdate();
			QuestsPlugin.getPlugin().getLoggerExpanded()
					.debug("Removed " + amount + " in-database pool datas for pool " + pool.getId());
			return amount;
		});
	}

	public CompletableFuture<Boolean> hasAccounts(Player p) {
		return palmLibrary.executeAsync(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(getAccountsIDs)) {
				statement.setString(1, p.getUniqueId().toString());
				ResultSet result = statement.executeQuery();
				boolean has = result.next();
				result.close();
				return has;
			}
		});
	}

	@Override
	public void load() {
		super.load();
		try {
			createTables();

			getAccountsIDs = "SELECT id, identifier FROM " + ACCOUNTS_TABLE + " WHERE player_uuid = ?";
			insertAccount = "INSERT INTO " + ACCOUNTS_TABLE + " (identifier, player_uuid) VALUES (?, ?)";
			deleteAccount = "DELETE FROM " + ACCOUNTS_TABLE + " WHERE id = ?";

			insertQuestData = "INSERT INTO " + QUESTS_ENTRIES_TABLE + " (account_id, quest_id) VALUES (?, ?)";
			removeQuestData = "DELETE FROM " + QUESTS_ENTRIES_TABLE + " WHERE account_id = ? AND quest_id = ?";
			getQuestsData = "SELECT * FROM " + QUESTS_ENTRIES_TABLE + " WHERE account_id = ?";

			removeExistingQuestDatas = "DELETE FROM " + QUESTS_ENTRIES_TABLE + " WHERE quest_id = ?";
			removeExistingPoolDatas = "DELETE FROM " + POOLS_DATAS_TABLE + " WHERE pool_id = ?";

			updateFinished = prepareDatasStatement("finished");
			updateTimer = prepareDatasStatement("timer");
			updateBranch = prepareDatasStatement("current_branch");
			updateStage = prepareDatasStatement("current_stage");
			updateDatas = prepareDatasStatement("additional_datas");
			updateFlow = prepareDatasStatement("quest_flow");

			insertPoolData = "INSERT INTO " + POOLS_DATAS_TABLE + " (account_id, pool_id) VALUES (?, ?)";
			removePoolData = "DELETE FROM " + POOLS_DATAS_TABLE + " WHERE account_id = ? AND pool_id = ?";
			getPoolData = "SELECT * FROM " + POOLS_DATAS_TABLE + " WHERE account_id = ?";
			getPoolAccountData = "SELECT 1 FROM " + POOLS_DATAS_TABLE + " WHERE account_id = ? AND pool_id = ?";

			updatePoolLastGive = "UPDATE " + POOLS_DATAS_TABLE + " SET last_give = ? WHERE account_id = ? AND pool_id = ?";
			updatePoolCompletedQuests =
					"UPDATE " + POOLS_DATAS_TABLE + " SET completed_quests = ? WHERE account_id = ? AND pool_id = ?";
		}catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private String prepareDatasStatement(String column) throws SQLException {
		return "UPDATE " + QUESTS_ENTRIES_TABLE + " SET " + column + " = ? WHERE id = ?";
	}

	@Override
	public void save() {
		cachedAccounts.values().forEach(x -> saveAccount(x, false));
	}

	private void createTables() throws SQLException {
		palmLibrary.run( connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("CREATE TABLE IF NOT EXISTS " + ACCOUNTS_TABLE + " ("
						+ " id " + HikariDataSourceWrapper.DataType.MySQL.getSerialType() + " ,"
						+ " identifier TEXT NOT NULL ,"
						+ " player_uuid CHAR(36) NOT NULL ,"
						+ accountDatas.values().stream().map(data -> " " + data.getColumnDefinition() + " ,").collect(Collectors.joining())
						+ " PRIMARY KEY (id)"
						+ " )");
				statement.execute("CREATE TABLE IF NOT EXISTS " + QUESTS_ENTRIES_TABLE + " (" +
						" id " + HikariDataSourceWrapper.DataType.MySQL.getSerialType() + " ," +
						" account_id INT NOT NULL," +
						" quest_id INT NOT NULL," +
						" finished INT DEFAULT NULL," +
						" timer BIGINT DEFAULT NULL," +
						" current_branch SMALLINT DEFAULT NULL," +
						" current_stage SMALLINT DEFAULT NULL," +
						" additional_datas " + HikariDataSourceWrapper.DataType.MySQL.getLongTextType() + " DEFAULT NULL," +
						" quest_flow VARCHAR(8000) DEFAULT NULL," +
						" PRIMARY KEY (id)" +
						")");
				statement.execute("CREATE TABLE IF NOT EXISTS " + POOLS_DATAS_TABLE + " ("
						+ " id " + HikariDataSourceWrapper.DataType.MySQL.getSerialType() + " ,"
						+ "account_id INT NOT NULL, "
						+ "pool_id INT NOT NULL, "
						+ "last_give BIGINT DEFAULT NULL, "
						+ "completed_quests VARCHAR(1000) DEFAULT NULL, "
						+ "PRIMARY KEY (id)"
						+ ")");

				/*
				upgradeTable(connection, QUESTS_ENTRIES_TABLE, columns -> {
					if (!columns.contains("quest_flow")) { // 0.19
						statement.execute("ALTER TABLE " + QUESTS_ENTRIES_TABLE
								+ " ADD COLUMN quest_flow VARCHAR(8000) DEFAULT NULL");
						QuestsPlugin.getPlugin().getLoggerExpanded().info("Updated database with quest_flow column.");
					}

					if (!columns.contains("additional_datas") || columns.contains("stage_0_datas")) { // 0.20
						// tests for stage_0_datas: it's in the case the server crashed/stopped during the migration process.
						if (!columns.contains("additional_datas")) {
							statement.execute("ALTER TABLE " + QUESTS_ENTRIES_TABLE
									+ " ADD COLUMN additional_datas " + HikariDataSourceWrapper.DataType.MySQL.getLongTextType()
									+ " DEFAULT NULL AFTER current_stage");
							QuestsPlugin.getPlugin().getLoggerExpanded().info("Updated table " + QUESTS_ENTRIES_TABLE + " with additional_datas column.");
						}

						QuestUtils.runAsync(this::migrateOldQuestDatas);
					}
				});

				upgradeTable(connection, ACCOUNTS_TABLE, columns -> {
					for (SQLDataSaver<?> data : accountDatas.values()) {
						if (!columns.contains(data.getWrappedData().getColumnName().toLowerCase())) {
							statement.execute("ALTER TABLE " + ACCOUNTS_TABLE
									+ " ADD COLUMN " + data.getColumnDefinition());
							QuestsPlugin.getPlugin().getLoggerExpanded().info("Updated database by adding the missing " + data.getWrappedData().getColumnName() + " column in the player accounts table.");
						}
					}
				});
				*/
			}
		});

	}

	private void upgradeTable(Connection connection, String tableName, ThrowingConsumer<List<String>, SQLException> columnsConsumer) throws SQLException {
		//List<String> columns = new ArrayList<>(14);
		//try (ResultSet set = connection.getMetaData().getColumns(db.getDatabase(), null, tableName, null)) {
		//	while (set.next()) {
		//		columns.add(set.getString("COLUMN_NAME").toLowerCase());
		//	}
		//}
		//if (columns.isEmpty()) {
		//	QuestsPlugin.getPlugin().getLoggerExpanded().severe("Cannot check integrity of SQL table " + tableName);
		//}else {
		//	columnsConsumer.accept(columns);
		//}
	}

	private void migrateOldQuestDatas() {

	}

	public static synchronized String migrate(HikariDataSourceWrapper db, PlayersManagerYAML yaml) throws SQLException {
		return "not supported";
	}

	@Override
	public CompletableFuture<Void> unloadAccount(PlayerAccountImplementation acc) {
		return saveAccount(acc, true);
	}

	public CompletableFuture<Void> saveAccount(PlayerAccountImplementation acc, boolean stop) {
		// batch 활용해서 한번에 insert.
			String insertQuestData = "INSERT INTO " + QUESTS_ENTRIES_TABLE
							+ " (account_id, quest_id, finished, timer, current_branch, current_stage, additional_datas, quest_flow) " +
							"VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
							"ON DUPLICATE KEY UPDATE finished = VALUES(finished), timer = VALUES(timer), current_branch = VALUES(current_branch), current_stage = VALUES(current_stage), additional_datas = VALUES(additional_datas), quest_flow = VALUES(quest_flow)";
		//			try (PreparedStatement insertStatement = connection.prepareStatement(insertQuestData)) {
		//				insertStatement.setInt(1, acc.index);
		//				insertStatement.setInt(2, questID);
		//				insertStatement.setInt(3, finished);
		//				insertStatement.setLong(4, timer);
		//				insertStatement.setInt(5, branch);
		//				insertStatement.setInt(6, stage);
		//				insertStatement.setString(7, CustomizedObjectTypeAdapter.serializeNullable(additionalDatas));
		//				insertStatement.setString(8, questFlow.toString());
		//				insertStatement.execute();
		//					QuestsPlugin.getPlugin().getLoggerExpanded().debug("Created row " + handlingRowId + " for quest " + questID + ", account " + acc.index);
		//				} catch (SQLException ex) {
		//				QuestsPlugin.getPlugin().getLoggerExpanded().severe("An error occurred while inserting a player's quest datas.", ex);
		//			}
		return palmLibrary.runAsync(connection -> {
			PreparedStatement ps = connection.prepareStatement(insertQuestData);
			for (@NotNull PlayerQuestEntryData entry : acc.getQuestEntries()) {
				MySQLPlayerQuestDataEntry data = (MySQLPlayerQuestDataEntry) entry;
				ps.setInt(1, acc.index);
				ps.setInt(2, data.questID);
				ps.setInt(3, data.finished);
				ps.setLong(4, data.timer);
				ps.setInt(5, data.branch);
				ps.setInt(6, data.stage);
				ps.setString(7, CustomizedObjectTypeAdapter.serializeNullable(data.additionalDatas));
				ps.setString(8, data.questFlow.toString());
				ps.addBatch();
			}
			ps.executeBatch();
		});
		//acc.getQuestEntries()
		//	.stream()
		//	.map(MySQLPlayerQuestDataEntry.class::cast)
		//		.forEach(x -> x.flushAll(stop));
//
	}

	protected static String getCompletedQuestsString(Set<Integer> completedQuests) {
		return completedQuests.isEmpty() ? null : completedQuests.stream().map(x -> Integer.toString(x)).collect(Collectors.joining(";"));
	}

	/**
	 * 각 퀘스트 개별 데이터 저장 클래스
	 */
	public class MySQLPlayerQuestDataEntry extends PlayerQuestEntryDataImplementation {

		private static final int DATA_QUERY_TIMEOUT = 15;
		private static final int DATA_FLUSHING_TIME = 10;

		//private Map<String, Entry<BukkitRunnable, Object>> cachedDatas = new HashMap<>(5);
		private boolean disabled = false;
		private int handlingRowId = -1;

		public MySQLPlayerQuestDataEntry(PlayerAccountImplementation acc, int questID) {
			super(acc, questID);
		}

		public MySQLPlayerQuestDataEntry(PlayerAccountImplementation acc, int questID, ResultSet result) throws SQLException {
			super(
					acc,
					questID,
					result.getLong("timer"),
					result.getInt("finished"),
					result.getInt("current_branch"),
					result.getInt("current_stage"),
					CustomizedObjectTypeAdapter.deserializeNullable(result.getString("additional_datas"), Map.class),
					result.getString("quest_flow"));
			this.handlingRowId = result.getInt("id");
		}

		@Override
		public void incrementFinished() {
			super.incrementFinished();
			//setDataStatement(updateFinished, getTimesFinished(), false);
		}

		@Override
		public void setTimer(long timer) {
			super.setTimer(timer);
			//setDataStatement(updateTimer, timer, false);
		}

		@Override
		public void setBranch(int branch) {
			super.setBranch(branch);
			//setDataStatement(updateBranch, branch, false);
		}

		@Override
		public void setStage(int stage) {
			super.setStage(stage);
			//setDataStatement(updateStage, stage, false);
		}

		@Override
		public <T> T setAdditionalData(String key, T value) {
			T additionalData = super.setAdditionalData(key, value);
			//setDataStatement(updateDatas, super.additionalDatas.isEmpty() ? null : CustomizedObjectTypeAdapter.serializeNullable(super.additionalDatas), true);
			return additionalData;
		}

		@Override
		public void addQuestFlow(StageController finished) {
			super.addQuestFlow(finished);
			//setDataStatement(updateFlow, getQuestFlow(), true);
		}

		@Override
		public void resetQuestFlow() {
			super.resetQuestFlow();
			//setDataStatement(updateFlow, null, true);
		}

		//private void setDataStatement(String dataStatement, Object data, boolean allowNull) {
		//	if (disabled) return;
		//	try (Connection connection = db.getConnection()) {
		//		if (handlingRowId == -1) createDataRow(connection);
		//		try (PreparedStatement statement = connection.prepareStatement(dataStatement)) {
		//			statement.setObject(1, data);
		//			statement.setInt(2, handlingRowId);
		//			//statement.setQueryTimeout(DATA_QUERY_TIMEOUT);
		//			statement.executeUpdate();
		//			if (data == null && !allowNull) {
		//				QuestsPlugin.getPlugin().getLoggerExpanded().warning("Setting an illegal NULL value in statement \"" + dataStatement + "\" for account " + acc.index + " and quest " + questID);
		//			}
		//		}
		//	} catch (Exception ex) {
		//		QuestsPlugin.getPlugin().getLoggerExpanded().severe("An error occurred while updating a player's quest datas.", ex);
		//	}
		//}

		protected void flushAll(Connection con, boolean stop) {
			if (stop) disabled = true;
			// TODO: 플레이어 퀘스트 데이터를 일괄 Insert 해야하는 부분.
			insertAll(con);
		}

		protected void stop() {
			disabled = true;
		}

		private void insertAll(Connection connection) {
			String insertQuestData = "INSERT INTO " + QUESTS_ENTRIES_TABLE
					+ " (account_id, quest_id, finished, timer, current_branch, current_stage, additional_datas, quest_flow) " +
					"VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
					"ON DUPLICATE KEY UPDATE finished = VALUES(finished), timer = VALUES(timer), current_branch = VALUES(current_branch), current_stage = VALUES(current_stage), additional_datas = VALUES(additional_datas), quest_flow = VALUES(quest_flow)";
			try (PreparedStatement insertStatement = connection.prepareStatement(insertQuestData)) {
				insertStatement.setInt(1, acc.index);
				insertStatement.setInt(2, questID);
				insertStatement.setInt(3, finished);
				insertStatement.setLong(4, timer);
				insertStatement.setInt(5, branch);
				insertStatement.setInt(6, stage);
				insertStatement.setString(7, CustomizedObjectTypeAdapter.serializeNullable(additionalDatas));
				insertStatement.setString(8, questFlow.toString());
				insertStatement.execute();
					QuestsPlugin.getPlugin().getLoggerExpanded().debug("Created row " + handlingRowId + " for quest " + questID + ", account " + acc.index);
				} catch (SQLException ex) {
				QuestsPlugin.getPlugin().getLoggerExpanded().severe("An error occurred while inserting a player's quest datas.", ex);
			}
		}

		private void createDataRow(Connection connection) throws SQLException {
			QuestsPlugin.getPlugin().getLoggerExpanded().debug("Inserting DB row of quest " + questID + " for account " + acc.index);
			try (PreparedStatement insertStatement = connection.prepareStatement(insertQuestData, new String[] {"id"})) {
				insertStatement.setInt(1, acc.index);
				insertStatement.setInt(2, questID);
				int affectedLines = insertStatement.executeUpdate();
				if (affectedLines != 1)
					throw new DataException("No row inserted");
				ResultSet generatedKeys = insertStatement.getGeneratedKeys();
				if (!generatedKeys.next())
					throw new DataException("Generated keys ResultSet is empty");
				handlingRowId = generatedKeys.getInt(1);
				QuestsPlugin.getPlugin().getLoggerExpanded().debug("Created row " + handlingRowId + " for quest " + questID + ", account " + acc.index);
			}
		}

	}

	public class PlayerPoolDatasDB extends PlayerPoolDatasImplementation {

		public PlayerPoolDatasDB(PlayerAccountImplementation acc, int poolID) {
			super(acc, poolID);
		}

		public PlayerPoolDatasDB(PlayerAccountImplementation acc, int poolID, long lastGive, Set<Integer> completedQuests) {
			super(acc, poolID, lastGive, completedQuests);
		}

		@Override
		public void setLastGive(long lastGive) {
			super.setLastGive(lastGive);
			updateData(updatePoolLastGive, lastGive);
		}

		@Override
		public void updatedCompletedQuests() {
			updateData(updatePoolCompletedQuests, getCompletedQuestsString(getCompletedQuests()));
		}

		private void updateData(String dataStatement, Object data) {
			palmLibrary.runAsync(connection -> {
				try (PreparedStatement statement = connection.prepareStatement(getPoolAccountData)) {
					statement.setInt(1, acc.index);
					statement.setInt(2, poolID);
					if (!statement.executeQuery().next()) { // if result set empty => need to insert data then update
						try (PreparedStatement insertStatement = connection.prepareStatement(insertPoolData)) {
							insertStatement.setInt(1, acc.index);
							insertStatement.setInt(2, poolID);
							insertStatement.executeUpdate();
						}
					}
				}
				try (PreparedStatement statement = connection.prepareStatement(dataStatement)) {
					statement.setObject(1, data);
					statement.setInt(2, acc.index);
					statement.setInt(3, poolID);
					statement.executeUpdate();
				}
			});
		}

	}

	public class PlayerAccountDB extends PlayerAccountImplementation {

		public PlayerAccountDB(AbstractAccount account, int index) {
			super(account, index);
		}

		@Override
		public <T> void setData(SavableData<T> data, T value) {
			super.setData(data, value);

			SQLDataSaver<T> dataSaver = (SQLDataSaver<T>) accountDatas.get(data);
			palmLibrary.runAsync(connection -> {
				try (PreparedStatement statement = connection.prepareStatement(dataSaver.getUpdateStatement())) {
					dataSaver.setInStatement(statement, 1, value);
					statement.setInt(2, index);
					statement.executeUpdate();
				}
			});
		}

		@Override
		public void resetEntries() {
			super.resetEntries();

			if (resetAccountDatas != null) {
				palmLibrary.runAsync(connection -> {
					try (PreparedStatement statement = connection.prepareStatement(resetAccountDatas)) {
						statement.setInt(1, index);
						statement.executeUpdate();
					}
				});
			}
		}

	}

}
