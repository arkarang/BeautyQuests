package fr.skytasul.quests.requirements;

import org.bukkit.entity.Player;
import fr.skytasul.quests.api.localization.Lang;
import fr.skytasul.quests.api.requirements.AbstractRequirement;
import fr.skytasul.quests.api.requirements.TargetNumberRequirement;
import fr.skytasul.quests.api.utils.ComparisonMethod;
import fr.skytasul.quests.utils.compatibility.SkillAPI;

public class SkillAPILevelRequirement extends TargetNumberRequirement {
	
	public SkillAPILevelRequirement() {
		this(null, null, 0, ComparisonMethod.GREATER_OR_EQUAL);
	}
	
	public SkillAPILevelRequirement(String customDescription, String customReason, double target,
			ComparisonMethod comparison) {
		super(customDescription, customReason, target, comparison);
	}

	@Override
	public double getPlayerTarget(Player p) {
		return SkillAPI.getLevel(p);
	}
	
	@Override
	protected String getPlaceholderName() {
		return "level";
	}

	@Override
	protected String getDefaultReason(Player player) {
		return Lang.REQUIREMENT_LEVEL.format(this);
	}
	
	@Override
	public Class<? extends Number> numberClass() {
		return Integer.class;
	}
	
	@Override
	public void sendHelpString(Player p) {
		Lang.CHOOSE_XP_REQUIRED.send(p);
	}
	
	@Override
	public String getDefaultDescription(Player p) {
		return Lang.RDLevel.format(this);
	}

	@Override
	public AbstractRequirement clone() {
		return new SkillAPILevelRequirement(getCustomDescription(), getCustomReason(), target, comparison);
	}

}
