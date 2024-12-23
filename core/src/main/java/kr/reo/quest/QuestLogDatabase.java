package kr.reo.quest;

import com.minepalm.library.PalmLibrary;
import com.minepalm.library.database.JavaDatabase;
import com.minepalm.library.database.impl.internal.MySQLDB;
import fr.skytasul.quests.api.QuestsPlugin;
import fr.skytasul.quests.api.players.PlayerAccount;
import fr.skytasul.quests.api.players.PlayerQuestEntryData;
import fr.skytasul.quests.api.utils.CustomizedObjectTypeAdapter;
import fr.skytasul.quests.players.PlayersManagerDB;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class QuestLogDatabase {

    private final String table;
    private final JavaDatabase<Connection> database;

    public QuestLogDatabase(MySQLDB db) {
        this.table = "reo_beautyquest_log";
        this.database = db.java();
    }

    // 퀘스트 로그 (기존 테이블에서 가져온거)
    // id - 서버이름 - 로그 타입 - 플레이어 UUID - 퀘스트 데이터 ...
    public void createTable() {
        database.run( connection -> {
            connection.prepareStatement("CREATE TABLE IF NOT EXISTS " + table + " (" +
                    " id INT NOT NULL AUTO_INCREMENT," +
                    " server VARCHAR(32) NOT NULL," +
                    " log_type VARCHAR(32) NOT NULL," +
                    " user_id VARCHAR(36) NOT NULL," +
                    " quest_id INT NOT NULL," +
                    " finished INT DEFAULT NULL," +
                    " timer BIGINT DEFAULT NULL," +
                    " current_branch SMALLINT DEFAULT NULL," +
                    " current_stage SMALLINT DEFAULT NULL," +
                    " additional_data LONGTEXT DEFAULT NULL," +
                    " quest_flow VARCHAR(8000) DEFAULT NULL," +
                    " time TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    " PRIMARY KEY (id)" +
                    ")").execute();
        });
    }

    public CompletableFuture<Void> insertLog(String type, UUID uuid, PlayerAccount account) {
        String insertQuestData = "INSERT INTO " + table
                + " (server, log_type, user_id, quest_id, " +
                "finished, timer, current_branch, " +
                "current_stage, additional_data, quest_flow) " +
                "VALUES (?, ?, ?, ?, " +
                "?, ?, ?, " +
                "?, ?, ?) ";
        return database.runAsync(connection -> {
            PreparedStatement ps = connection.prepareStatement(insertQuestData);
            for (@NotNull PlayerQuestEntryData questEntry : account.getQuestEntries()) {
                ps.setString(1, PalmLibrary.getName());
                ps.setString(2, type);
                ps.setString(3, uuid.toString());
                setColumns(ps, questEntry.toRecord());
                ps.addBatch();
            }
            ps.executeBatch();

        });
    }

    private void setColumns(PreparedStatement ps, QuestEntryRecord entry) throws SQLException {
        ps.setInt(4, entry.questID);
        ps.setInt(5, entry.finished);
        ps.setLong(6, entry.timer);
        ps.setInt(7, entry.branch);
        ps.setInt(8, entry.stage);
        ps.setString(9, CustomizedObjectTypeAdapter.serializeNullable(entry.additionalDatas));
        ps.setString(10, entry.questFlow.toString());
    }

    //"CREATE TABLE IF NOT EXISTS " + QUESTS_ENTRIES_TABLE + " (" +
    //					" id " + db.getHandlingColumnType().getSerialType() + " ," +
    //					" account_id INT NOT NULL," +
    //					" quest_id INT NOT NULL," +
    //					" finished INT DEFAULT NULL," +
    //					" timer BIGINT DEFAULT NULL," +
    //					" current_branch SMALLINT DEFAULT NULL," +
    //					" current_stage SMALLINT DEFAULT NULL," +
    //					" additional_datas " + db.getHandlingColumnType().getLongTextType() + " DEFAULT NULL," +
    //					" quest_flow VARCHAR(8000) DEFAULT NULL," +
    //					" PRIMARY KEY (id)" +
    //					")");
}
