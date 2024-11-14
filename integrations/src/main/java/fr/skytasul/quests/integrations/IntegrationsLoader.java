package fr.skytasul.quests.integrations;

import com.cryptomorin.xseries.XMaterial;
import fr.skytasul.quests.api.AbstractHolograms;
import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.QuestsPlugin;
import fr.skytasul.quests.api.gui.ItemUtils;
import fr.skytasul.quests.api.localization.Lang;
import fr.skytasul.quests.api.requirements.RequirementCreator;
import fr.skytasul.quests.api.rewards.RewardCreator;
import fr.skytasul.quests.api.utils.IntegrationManager;
import fr.skytasul.quests.api.utils.IntegrationManager.BQDependency;
import fr.skytasul.quests.integrations.fabled.FabledClassRequirement;
import fr.skytasul.quests.integrations.fabled.FabledLevelRequirement;
import fr.skytasul.quests.integrations.fabled.FabledXpReward;
import fr.skytasul.quests.integrations.mobs.*;
import fr.skytasul.quests.integrations.npcs.*;
import fr.skytasul.quests.integrations.placeholders.PapiMessageProcessor;
import fr.skytasul.quests.integrations.placeholders.PlaceholderRequirement;
import fr.skytasul.quests.integrations.placeholders.QuestsPlaceholders;
import fr.skytasul.quests.integrations.vault.economy.MoneyRequirement;
import fr.skytasul.quests.integrations.vault.economy.MoneyReward;
import fr.skytasul.quests.integrations.vault.permission.PermissionReward;
import fr.skytasul.quests.integrations.worldguard.BQWorldGuard;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class IntegrationsLoader {

	private static IntegrationsLoader instance;

	public static IntegrationsLoader getInstance() {
		return instance;
	}

	private IntegrationsConfiguration config;

	public IntegrationsLoader() {
		instance = this;

		config = new IntegrationsConfiguration(QuestsPlugin.getPlugin().getConfig());
		config.load();

		IntegrationManager manager = QuestsPlugin.getPlugin().getIntegrationManager();


		manager.addDependency(new BQDependency("ZNPCsPlus", this::registerZnpcsPlus));

		manager.addDependency(new BQDependency("Citizens", () -> {
			QuestsAPI.getAPI().addNpcFactory("citizens", new BQCitizens());
			QuestsAPI.getAPI().registerMobFactory(new CitizensFactory());
		}));


		// MOBS
		manager.addDependency(new BQDependency("MythicMobs", this::registerMythicMobs));

		manager.addDependency(
				new BQDependency("LevelledMobs", () -> QuestsAPI.getAPI().registerMobFactory(new BQLevelledMobs())));

		manager.addDependency(new BQDependency("WildStacker", BQWildStacker::initialize));

		manager.addDependency(new BQDependency("StackMob", BQStackMob::initialize));


		// REWARDS / REQUIREMENTS
		manager.addDependency(new BQDependency("Fabled", this::registerFabled, null, this::isFabledValid)
				.addPluginName("ProSkillAPI").addPluginName("SkillAPI") // for warning message
				);
		manager.addDependency(new BQDependency("Vault", this::registerVault));


		// HOLOGRAMS

		manager.addDependency(new BQDependency("HolographicDisplays", this::registerHolographicDisplays));
		manager.addDependency(
				new BQDependency("DecentHolograms", () -> QuestsAPI.getAPI().setHologramsManager(new BQDecentHolograms())));


		// OTHERS
		manager.addDependency(new BQDependency("PlaceholderAPI", this::registerPapi));
		manager.addDependency(new BQDependency("WorldGuard", BQWorldGuard::initialize, BQWorldGuard::unload));
		manager.addDependency(new BQDependency("TokenEnchant",
				() -> Bukkit.getPluginManager().registerEvents(new BQTokenEnchant(), QuestsPlugin.getPlugin())));

		manager.addDependency(new BQDependency("ItemsAdder", BQItemsAdder::initialize, BQItemsAdder::unload));
		manager.addDependency(new BQDependency("MMOItems", BQMMOItems::initialize, BQMMOItems::unload));
	}

	private void registerPapi() {
		QuestsPlaceholders.registerPlaceholders(
				QuestsPlugin.getPlugin().getConfig().getConfigurationSection("startedQuestsPlaceholder"));
		QuestsAPI.getAPI().getRequirements()
				.register(new RequirementCreator("placeholderRequired", PlaceholderRequirement.class,
						ItemUtils.item(XMaterial.NAME_TAG, Lang.RPlaceholder.toString()), PlaceholderRequirement::new));
		QuestsAPI.getAPI().registerMessageProcessor("placeholderapi_replace", 5, new PapiMessageProcessor());
	}

	private void registerVault() {
		QuestsAPI.getAPI().getRewards().register(new RewardCreator("moneyReward", MoneyReward.class,
				ItemUtils.item(XMaterial.EMERALD, Lang.rewardMoney.toString()), MoneyReward::new));
		QuestsAPI.getAPI().getRewards().register(new RewardCreator("permReward", PermissionReward.class,
				ItemUtils.item(XMaterial.REDSTONE_TORCH, Lang.rewardPerm.toString()), PermissionReward::new));
		QuestsAPI.getAPI().getRequirements().register(new RequirementCreator("moneyRequired", MoneyRequirement.class,
				ItemUtils.item(XMaterial.EMERALD, Lang.RMoney.toString()), MoneyRequirement::new));
	}

	private void registerHolographicDisplays() {
		AbstractHolograms<?> holograms;
		try {
			Class.forName("com.gmail.filoghost.holographicdisplays.HolographicDisplays"); // v2
			holograms = new BQHolographicDisplays2();
		} catch (ClassNotFoundException ex) {
			try {
				Class.forName("me.filoghost.holographicdisplays.plugin.HolographicDisplays"); // v3
				holograms = new BQHolographicDisplays3();
			} catch (ClassNotFoundException ex1) {
				QuestsPlugin.getPlugin().getLoggerExpanded().warning(
						"Your version of HolographicDisplays is unsupported. Please make sure you are running the LATEST dev build of HolographicDisplays.");
				return;
			}
		}
		QuestsAPI.getAPI().setHologramsManager(holograms);
	}


	private void registerFabled() {
		QuestsAPI.getAPI().getRequirements().register(new RequirementCreator("classRequired", FabledClassRequirement.class,
				ItemUtils.item(XMaterial.GHAST_TEAR, Lang.RClass.toString()), FabledClassRequirement::new));
		QuestsAPI.getAPI().getRequirements()
				.register(new RequirementCreator("skillAPILevelRequired", FabledLevelRequirement.class,
						ItemUtils.item(XMaterial.EXPERIENCE_BOTTLE, Lang.RSkillAPILevel.toString()),
						FabledLevelRequirement::new));
		QuestsAPI.getAPI().getRewards().register(new RewardCreator("skillAPI-exp", FabledXpReward.class,
				ItemUtils.item(XMaterial.EXPERIENCE_BOTTLE, Lang.RWSkillApiXp.toString()), FabledXpReward::new));
	}

	private boolean isFabledValid(Plugin plugin) {
		if (plugin.getName().equals("SkillAPI") || plugin.getName().equals("ProSkillAPI")) {
			QuestsPlugin.getPlugin().getLogger().warning(
					"SkillAPI and ProSKillAPI are no longer supported. You must upgrade to their newer version: Fabled (https://www.spigotmc.org/resources/91913)");
			return false;
		}
		return true;
	}

	private boolean isUltimateTimberValid(Plugin plugin) {
		try {
			Class.forName("com.craftaro.ultimatetimber.UltimateTimber");
		} catch (ClassNotFoundException ex) {
			QuestsPlugin.getPlugin().getLoggerExpanded().warning("Your version of UltimateTimber ("
					+ plugin.getDescription().getVersion()
					+ ") is not compatible with BeautyQuests. Please use 3.0.0 or higher.");
			return false;
		}
		return true;
	}

	private boolean isBossVersionValid(Plugin plugin) {
		try {
			Class.forName("org.mineacademy.boss.model.Boss");
		} catch (ClassNotFoundException ex) {
			QuestsPlugin.getPlugin().getLoggerExpanded().warning("Your version of Boss ("
					+ plugin.getDescription().getVersion() + ") is not compatible with BeautyQuests.");
			return false;
		}
		return true;
	}

	private void registerMythicMobs() {
		try {
			Class.forName("io.lumine.mythic.api.MythicPlugin");
			QuestsAPI.getAPI().registerMobFactory(new MythicMobs5());
			QuestsAPI.getAPI().addNpcFactory("mythicmobs", new BQMythicMobs5Npcs());
		} catch (ClassNotFoundException ex) {
			QuestsAPI.getAPI().registerMobFactory(new MythicMobs());
		}
	}

	private boolean isZnpcsVersionValid(Plugin plugin) {
		if (plugin.getClass().getName().equals("io.github.gonalez.znpcs.ServersNPC")) // NOSONAR
			return true;

		QuestsPlugin.getPlugin().getLoggerExpanded().warning("Your version of znpcs ("
				+ plugin.getDescription().getVersion() + ") is not supported by BeautyQuests.");
		return false;
	}

	private void registerZnpcsPlus() {
		try {
			Class.forName("lol.pyr.znpcsplus.api.NpcApiProvider");
			QuestsAPI.getAPI().addNpcFactory("znpcsplus", new BQZNPCsPlus());
		} catch (ClassNotFoundException ex) {
			QuestsAPI.getAPI().addNpcFactory("znpcsplus", new BQZNPCsPlusOld()); // TODO remove, old version of znpcs+

			QuestsPlugin.getPlugin().getLoggerExpanded()
					.warning("Your version of ZNPCsPlus will soon not be supported by BeautyQuests.");
		}
	}

	public IntegrationsConfiguration getConfig() {
		return config;
	}

}
