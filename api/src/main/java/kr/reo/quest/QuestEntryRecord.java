package kr.reo.quest;

import java.util.Map;
import java.util.StringJoiner;

public class QuestEntryRecord {
    public final int questID;

    public final int finished;
    public final long timer;
    public final int branch;
    public final int stage;
    public final Map<String, Object> additionalDatas;
    public final StringJoiner questFlow;

    public QuestEntryRecord(int questID, int finished, long timer, int branch, int stage, Map<String, Object> additionalDatas, StringJoiner questFlow) {
        this.questID = questID;
        this.finished = finished;
        this.timer = timer;
        this.branch = branch;
        this.stage = stage;
        this.additionalDatas = additionalDatas;
        this.questFlow = questFlow;
    }
}
