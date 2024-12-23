package kr.reo.quest;

import com.minepalm.library.PalmLibrary;
import com.minepalm.library.database.impl.internal.MySQLDB;
import fr.skytasul.quests.api.players.PlayerAccount;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class ReoQuestModule {

    private static ReoQuestModule inst;

    public static ReoQuestModule inst() {
        return inst;
    }

    private final MySQLDB questDatabase;
    private final QuestLogDatabase logDatabase;

    public ReoQuestModule(MySQLDB questDatabase, MySQLDB logDatabase) {
        this.questDatabase = questDatabase;
        this.logDatabase = new QuestLogDatabase(logDatabase);
    }

    public static void onEnable(JavaPlugin plugin) {
        MySQLDB questDatabase = PalmLibrary.getDataSource().mysql("quest");
        MySQLDB logDatabase = PalmLibrary.getDataSource().mysql("log");
        inst = new ReoQuestModule(questDatabase, logDatabase);
        inst.logDatabase.createTable();
    }

    public static void onDisable() {

    }

    public void logQuest(UUID uuid, String loggingType, PlayerAccount account) {
        logDatabase.insertLog(loggingType, uuid, account);
    }

    public MySQLDB provideQuestDatabase() {
        return questDatabase;
    }

    public void logJoin(UUID uuid, PlayerAccount account) {
        logQuest(uuid, "JOIN", account);
    }

    public void logQuit(UUID uuid, PlayerAccount account) {
        logQuest(uuid, "QUIT", account);
    }
}
