package fr.skytasul.quests.utils;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.bukkit.configuration.ConfigurationSection;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.skytasul.quests.BeautyQuests;
import fr.skytasul.quests.api.QuestsPlugin;

public class HikariDataSourceWrapper implements Closeable {

	private final ConfigurationSection config;
	private final String databaseName;

	private final DataType handlingColumnType;

	private final DataSource source;

	public HikariDataSourceWrapper(ConfigurationSection config) {
		this.config = config;
		this.databaseName = config.getString("database");

		HikariConfig hikariConfig = new HikariConfig("/hikari.properties");

		String connectionString = config.getString("connectionString", "");
		if (connectionString == null || connectionString.isEmpty())
			connectionString = "jdbc:mysql://" + config.getString("host") + ":" + config.getInt("port") + "/" + databaseName;

		Matcher matcher = Pattern.compile("^jdbc:(\\w+):\\/\\/").matcher(connectionString);
		if (matcher.find()) {
			switch (matcher.group(1).toLowerCase()) {
				case "mysql":
					handlingColumnType = DataType.MySQL;
					break;
				case "postgresql":
					handlingColumnType = DataType.PostgreSQL;
					break;
				default:
					QuestsPlugin.getPlugin().getLogger().warning("Unsupported database provider: " + matcher.group(1));
					handlingColumnType = DataType.MySQL;
					break;
			}
		} else {
			QuestsPlugin.getPlugin().getLogger().warning("Malformed database connection string!");
			handlingColumnType = DataType.MySQL;
		}

		hikariConfig.setJdbcUrl(connectionString);
		hikariConfig.setUsername(config.getString("username"));
		hikariConfig.setPassword(config.getString("password"));
		hikariConfig.setPoolName("BeautyQuests-SQL-pool");
		hikariConfig.setConnectionTimeout(20_000);
		hikariConfig.setMaximumPoolSize(1);

		boolean ssl = config.getBoolean("ssl");
		hikariConfig.addDataSourceProperty("verifyServerCertificate", ssl);
		hikariConfig.addDataSourceProperty("useSSL", ssl);

		source = new HikariDataSource(hikariConfig);
		QuestsPlugin.getPlugin().getLoggerExpanded().debug("Initialized database source. Type: " + handlingColumnType.name());
	}

	public void testConnection() throws SQLException {
		try (Connection connection = source.getConnection()) {
			if (!connection.isValid(0))
				throw new SQLException("Could not establish database connection.");
		}
	}

	public String getDatabase() {
		return databaseName;
	}

	public ConfigurationSection getConfig() {
		return config;
	}

	public DataType getHandlingColumnType() {
		return handlingColumnType;
	}

	@Override
	public void close() {
		QuestsPlugin.getPlugin().getLoggerExpanded().info("Closing database pool...");
		try {
			((Closeable) source).close();
		}catch (IOException ex) {
			QuestsPlugin.getPlugin().getLoggerExpanded().severe("An error occurred while closing database pool.", ex);
		}
	}

	public Connection getConnection() throws SQLException {
		return source.getConnection();
	}

	//public static HikariDataSourceWrapper getInstance(){
	//	return BeautyQuests.getInstance().getBQDatabase();
	//}

	public enum DataType {
		MySQL("INT NOT NULL AUTO_INCREMENT", "TINYINT", "LONGTEXT"), PostgreSQL("SERIAL", "BOOLEAN", "TEXT");

		private final String serialType;
		private final String booleanType;
		private final String longTextType;

		private DataType(String serialType, String booleanType, String longTextType) {
			this.serialType = serialType;
			this.booleanType = booleanType;
			this.longTextType = longTextType;
		}

		public String getSerialType() {
			return serialType;
		}

		public String getBooleanType() {
			return booleanType;
		}

		public String getLongTextType() {
			return longTextType;
		}
	}

}
