package fr.skytasul.quests.api;

import fr.skytasul.quests.api.questers.TopLevelQuester;
import fr.skytasul.quests.api.quests.Quest;
import fr.skytasul.quests.api.stages.StageHandler;

public interface QuestsHandler extends StageHandler {

	public default void load() {}

	public default void unload() {}

	public default void questCreate(Quest quest) {}

	public default void questRemove(Quest quest) {}

	public default void questLoaded(Quest quest) {}

	public default void questUnload(Quest quest) {}

	public default void questEdit(Quest newQuest, Quest oldQuest, boolean keepDatas) {}

	public default void questStart(TopLevelQuester quester, Quest quest) {}

	public default void questFinish(TopLevelQuester quester, Quest quest) {}

	public default void questReset(TopLevelQuester quester, Quest quest) {}

	public default void questUpdated(TopLevelQuester quester, Quest quest) {}

}
