package fr.skytasul.quests.players;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.skytasul.quests.BeautyQuests;
import fr.skytasul.quests.QuestsListener;
import fr.skytasul.quests.api.QuestsPlugin;
import fr.skytasul.quests.api.data.SavableData;
import fr.skytasul.quests.api.events.accounts.PlayerAccountLeaveEvent;
import fr.skytasul.quests.api.players.PlayerAccount;
import fr.skytasul.quests.api.players.PlayersManager;
import fr.skytasul.quests.api.pools.QuestPool;
import fr.skytasul.quests.api.quests.Quest;
import fr.skytasul.quests.players.accounts.AbstractAccount;
import fr.skytasul.quests.players.accounts.UUIDAccount;
import fr.skytasul.quests.utils.DebugUtils;
import kr.reo.quest.ReoQuestModule;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractPlayersManager implements PlayersManager {

	protected final @NotNull Map<UUID, PlayerAccountImplementation> cachedAccounts = new HashMap<>();
	protected final @NotNull Set<@NotNull SavableData<?>> accountDatas = new HashSet<>();
	private boolean loaded = false;

	public abstract CompletableFuture<PlayerAccount> load(@NotNull AccountFetchRequest request);

	public abstract CompletableFuture<Void> unloadAccount(@NotNull PlayerAccountImplementation acc);

	protected abstract @NotNull CompletableFuture<Void> removeAccount(@NotNull PlayerAccountImplementation acc);

	public abstract @NotNull CompletableFuture<Integer> removeQuestDatas(@NotNull Quest quest);

	public abstract @NotNull CompletableFuture<Integer> removePoolDatas(@NotNull QuestPool pool);

	public abstract @NotNull PlayerQuestEntryDataImplementation createPlayerQuestDatas(@NotNull PlayerAccountImplementation acc,
																					   @NotNull Quest quest);

	public abstract @NotNull PlayerPoolDatasImplementation createPlayerPoolDatas(@NotNull PlayerAccountImplementation acc,
			@NotNull QuestPool pool);

	public @NotNull CompletableFuture<Void> playerQuestDataRemoved(@NotNull PlayerQuestEntryDataImplementation datas) {
		return CompletableFuture.completedFuture(null);
	}

	public @NotNull CompletableFuture<Void> playerPoolDataRemoved(@NotNull PlayerPoolDatasImplementation datas) {
		return CompletableFuture.completedFuture(null);
	}

	public void load() {
		if (loaded) throw new IllegalStateException("Already loaded");
		loaded = true;
	}

	public boolean isLoaded() {
		return loaded;
	}

	@Override
	public abstract void save();

	@Override
	public void addAccountData(@NotNull SavableData<?> data) {
		if (loaded)
			throw new IllegalStateException("Cannot add account data after players manager has been loaded");
		if (PlayerAccountImplementation.FORBIDDEN_DATA_ID.contains(data.getId()))
			throw new IllegalArgumentException("Forbidden account data id " + data.getId());
		if (accountDatas.stream().anyMatch(x -> x.getId().equals(data.getId())))
			throw new IllegalArgumentException("Another account data already exists with the id " + data.getId());
		if (data.getDataType().isPrimitive())
			throw new IllegalArgumentException("Primitive account data types are not supported");
		accountDatas.add(data);
		QuestsPlugin.getPlugin().getLoggerExpanded().debug("Registered account data " + data.getId());
	}

	@Override
	public @NotNull Collection<@NotNull SavableData<?>> getAccountDatas() {
		return accountDatas;
	}

	protected @NotNull AbstractAccount createAbstractAccount(@NotNull Player p) {
		return new UUIDAccount(p.getUniqueId());
	}

	protected @NotNull AbstractAccount createAbstractAccount(@NotNull UUID uuid) {
		return new UUIDAccount(uuid);
	}

	protected @NotNull String getIdentifier(@NotNull OfflinePlayer p) {
		return p.getUniqueId().toString();
	}

	protected @Nullable AbstractAccount createAccountFromIdentifier(@NotNull String identifier) {
		try {
			UUID uuid = UUID.fromString(identifier);
			return new UUIDAccount(uuid);
		} catch (IllegalArgumentException ex) {
			QuestsPlugin.getPlugin().getLoggerExpanded().warning("Account identifier " + identifier + " is not valid.");
		}

		return null;
	}

	public void reorpgLoad(UUID uuid) {
		long time = System.currentTimeMillis();
		QuestsPlugin.getPlugin().getLoggerExpanded().debug("Loading player " + uuid.toString() + "...");
		cachedAccounts.remove(uuid);
		tryLoad(uuid, time);
	}

	public void loadPlayer(@NotNull Player p) {
		cachedPlayerNames.put(p.getUniqueId(), p.getName());

		long time = System.currentTimeMillis();
		QuestsPlugin.getPlugin().getLoggerExpanded().debug("Loading player " + p.getName() + "...");
		cachedAccounts.remove(p.getUniqueId());
		for (int i = 1; i >= 0; i--) {
			try {
				if (!tryLoad(p, time))
					return;
			} catch (Exception ex) {
				QuestsPlugin.getPlugin().getLoggerExpanded().severe("An error ocurred while trying to load datas of " + p.getName() + ".", ex);
			}
			if (i > 0)
				QuestsPlugin.getPlugin().getLoggerExpanded().severe("Doing " + i + " more attempt.");
		}
		QuestsPlugin.getPlugin().getLoggerExpanded().severe("Datas of " + p.getName() + " have failed to load. This may cause MANY issues.");

	}

	private void tryLoad(@NotNull UUID uuid, long time) {

		AccountFetchRequest request = new AccountFetchRequest(uuid, time, true, true);
		try {
			PlayerAccount account = load(request).get(5000L, java.util.concurrent.TimeUnit.MILLISECONDS);
			ReoQuestModule.inst().logJoin(uuid, account);
		} catch (Exception e) {
			e.printStackTrace();
			QuestsPlugin.getPlugin().getLoggerExpanded().severe("An error occurred while loading datas of " + uuid + ".", e);
			return;
		}

		if (request.isAccountCreated())
			QuestsPlugin.getPlugin().getLoggerExpanded().debug(
					"New account registered for " + uuid + " (" + request.getAccount().abstractAcc.getIdentifier()
							+ "), index " + request.getAccount().index + " via " + DebugUtils.stackTraces(2, 4));

		cachedAccounts.put(uuid, request.getAccount());
		QuestsListener.addAccountRequest(request);

		String loadMessage =
				"Completed load of " + uuid + " (" + request.getAccount().debugName() + ") datas within "
						+ (System.currentTimeMillis() - time) + " ms (" + request.getAccount().getQuestEntries().size()
						+ " quests, " + request.getAccount().getPoolDatas().size() + " pools)";

		if (request.getLoadedFrom() != null)
			loadMessage += " | Loaded from " + request.getLoadedFrom();

		QuestsPlugin.getPlugin().getLoggerExpanded().debug(loadMessage);

	}

	private boolean tryLoad(@NotNull Player p, long time) {

		AccountFetchRequest request = new AccountFetchRequest(p.getUniqueId(), time, true, true);
		load(request);

		if (!request.isFinished() || request.getAccount() == null) {
			QuestsPlugin.getPlugin().getLoggerExpanded().severe("The account of " + p.getName() + " has not been properly loaded.");
			return true;
		}

		if (!p.isOnline()) {
			if (request.isAccountCreated()) {
				QuestsPlugin.getPlugin().getLoggerExpanded().debug(
						"New account registered for " + p.getName() + "... but deleted as player left before loading.");
				removeAccount(request.getAccount()).whenComplete(
						QuestsPlugin.getPlugin().getLoggerExpanded().logError("An error occurred while removing newly created account"));
			}
			return false;
		}

		if (request.isAccountCreated())
			QuestsPlugin.getPlugin().getLoggerExpanded().debug(
					"New account registered for " + p.getName() + " (" + request.getAccount().abstractAcc.getIdentifier()
							+ "), index " + request.getAccount().index + " via " + DebugUtils.stackTraces(2, 4));

		if (!request.getAccount().getOfflinePlayer().equals(p)) {
			QuestsPlugin.getPlugin().getLogger()
					.severe("UUID mismatch between player " + p.getName() + " (" + p.getUniqueId() + ") and loaded account "
							+ request.getAccount().debugName());
			return false;
		}

		cachedAccounts.put(p.getUniqueId(), request.getAccount());
		QuestsListener.addAccountRequest(request);

		String loadMessage =
				"Completed load of " + p.getName() + " (" + request.getAccount().debugName() + ") datas within "
						+ (System.currentTimeMillis() - time) + " ms (" + request.getAccount().getQuestEntries().size()
						+ " quests, " + request.getAccount().getPoolDatas().size() + " pools)";

		if (request.getLoadedFrom() != null)
			loadMessage += " | Loaded from " + request.getLoadedFrom();

		QuestsPlugin.getPlugin().getLoggerExpanded().debug(loadMessage);

		/*
		Bukkit.getScheduler().runTask(BeautyQuests.getInstance(), () -> {

			if (p.isOnline()) {
				Bukkit.getPluginManager()
						.callEvent(new PlayerAccountJoinEvent(request.getAccount(), request.isAccountCreated()));
			} else {
				QuestsPlugin.getPlugin().getLoggerExpanded().warning(
						"Player " + p.getName() + " has quit the server while loading its datas. This may be a bug.");

				if (request.isAccountCreated())
					removeAccount(request.getAccount()).whenComplete(
							QuestsPlugin.getPlugin().getLoggerExpanded().logError("An error occurred while removing newly created account"));
			}
		});
		 */
		return false;
	}

	public synchronized void unloadPlayer(@NotNull Player p) {
		PlayerAccountImplementation acc = cachedAccounts.get(p.getUniqueId());
		if (acc == null) return;
		QuestsPlugin.getPlugin().getLoggerExpanded().debug("Unloading player " + p.getName() + "... (" + acc.getQuestEntries().size() + " quests, " + acc.getPoolDatas().size() + " pools)");
		Bukkit.getPluginManager().callEvent(new PlayerAccountLeaveEvent(acc));
		unloadAccount(acc);
		cachedAccounts.remove(p.getUniqueId());
	}


	public synchronized void reorpgUnload(@NotNull UUID uuid) {
		PlayerAccountImplementation acc = cachedAccounts.get(uuid);
		if (acc == null) return;
		QuestsPlugin.getPlugin().getLoggerExpanded().debug("Unloading player " + uuid + "... (" + acc.getQuestEntries().size() + " quests, " + acc.getPoolDatas().size() + " pools)");
		Bukkit.getScheduler().runTask(BeautyQuests.getInstance(), () -> {
			Bukkit.getPluginManager().callEvent(new PlayerAccountLeaveEvent(acc));
		});
		try {
			unloadAccount(acc).get(5000L, java.util.concurrent.TimeUnit.MILLISECONDS);
			ReoQuestModule.inst().logQuit(uuid, acc);
		} catch (Exception e) {
			QuestsPlugin.getPlugin().getLoggerExpanded().severe("[레오퀘스트] An error occurred while unloading datas of " + uuid + ".", e);
		}
		cachedAccounts.remove(uuid);
	}

	@Override
	public @UnknownNullability PlayerAccountImplementation getAccount(@NotNull Player p) {
		if (BeautyQuests.getInstance().getNpcManager().isNPC(p))
			return null;
		if (!p.isOnline()) {
			QuestsPlugin.getPlugin().getLoggerExpanded().severe("Trying to fetch the account of an offline player (" + p.getName() + ")");
			QuestsPlugin.getPlugin().getLoggerExpanded().debug("(via " + DebugUtils.stackTraces(2, 5) + ")");
		}

		return cachedAccounts.get(p.getUniqueId());
	}

	public @UnknownNullability PlayerAccountImplementation getAccount(@NotNull UUID uuid) {
		return cachedAccounts.get(uuid);
	}


	private static Map<UUID, String> cachedPlayerNames = new HashMap<>();
	private static Gson gson = new Gson();
	private static long lastOnlineFailure = 0;

	public static synchronized @Nullable String getPlayerName(@NotNull UUID uuid) {
		if (cachedPlayerNames.containsKey(uuid))
			return cachedPlayerNames.get(uuid);

		String name;
		if (Bukkit.getOnlineMode()) {
			try {
				if (System.currentTimeMillis() - lastOnlineFailure < 30_000) {
					QuestsPlugin.getPlugin().getLoggerExpanded().debug("Trying to fetch a name from an UUID but it failed within 30 seconds.");
					return null;
				}

				HttpURLConnection connection = (HttpURLConnection) new URL(
						"https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString()).openConnection();
				connection.setReadTimeout(5000);

				JsonObject profile = gson.fromJson(new BufferedReader(new InputStreamReader(connection.getInputStream())),
						JsonObject.class);
				JsonElement nameElement = profile.get("name");
				if (nameElement == null) {
					name = null;
					QuestsPlugin.getPlugin().getLoggerExpanded().debug("Cannot find name for UUID " + uuid.toString());
				} else {
					name = nameElement.getAsString();
				}
			} catch (Exception e) {
				QuestsPlugin.getPlugin().getLoggerExpanded().warning("Cannot connect to the mojang servers. UUIDs cannot be parsed.");
				lastOnlineFailure = System.currentTimeMillis();
				return null;
			}
		} else {
			name = Bukkit.getOfflinePlayer(uuid).getName();
		}

		cachedPlayerNames.put(uuid, name);
		return name;
	}

	public static class AccountFetchRequest {
		private final UUID uuid;
		private final long joinTimestamp;
		private final boolean allowCreation;
		private final boolean shouldCache;

		private boolean finished = false;
		private boolean created;
		private PlayerAccountImplementation account;
		private String loadedFrom;

		public AccountFetchRequest(UUID uuid, long joinTimestamp, boolean allowCreation, boolean shouldCache) {
			this.uuid = uuid;
			this.joinTimestamp = joinTimestamp;
			this.allowCreation = allowCreation;
			this.shouldCache = shouldCache;
		}

		public UUID getUniqueId() {
			return uuid;
		}

		public OfflinePlayer getOfflinePlayer() {
			return Bukkit.getOfflinePlayer(uuid);
		}

		public Player getOnlinePlayer() {
			Player player = Bukkit.getPlayer(uuid);
			if (player != null && player.isOnline())
				return player.getPlayer();
			throw new IllegalStateException("The player " + uuid + " is offline.");
		}

		public long getJoinTimestamp() {
			return joinTimestamp;
		}

		/**
		 * @return <code>true</code> if an account must be created when no account can be loaded
		 */
		public boolean mustCreateMissing() {
			return allowCreation;
		}

		/**
		 * @return <code>true</code> if the loaded account should be cached internally (usually because this
		 *         account will get associated with an online player)
		 */
		public boolean shouldCache() {
			return shouldCache;
		}

		public String getDebugPlayerName() {
			return uuid.toString();
		}

		/**
		 * This method must be called when the request results in a successfully loaded account.
		 *
		 * @param account account that has been loaded
		 * @param from source of the saved account
		 */
		public void loaded(PlayerAccountImplementation account, String from) {
			ensureAvailable();
			this.account = account;
			this.loadedFrom = from;
			this.created = false;
		}

		/**
		 * This method must be called when the request results in the creation of a new account.
		 * <p>
		 * It <strong>cannot</strong> be called when the {@link AccountFetchRequest#mustCreateMissing()}
		 * method returns false.
		 *
		 * @param account account that has been created
		 */
		public void created(PlayerAccountImplementation account) {
			if (!mustCreateMissing())
				throw new IllegalStateException(
						"This method cannot be called as this request does not allow account creation");
			ensureAvailable();
			this.account = account;
			this.created = true;
		}

		/**
		 * This method must be called when the request cannot load any account associated with the player
		 * and the {@link AccountFetchRequest#mustCreateMissing()} returns false.
		 */
		public void notLoaded() {
			if (mustCreateMissing())
				throw new IllegalStateException(
						"This method cannot be called as this request requires account creation if no account can be loaded");
			ensureAvailable();
		}

		private void ensureAvailable() {
			if (finished)
				throw new IllegalStateException("This request has already been completed");
			this.finished = true;
		}

		public boolean isFinished() {
			return finished;
		}

		public PlayerAccountImplementation getAccount() {
			return account;
		}

		public boolean isAccountCreated() {
			return created;
		}

		public String getLoadedFrom() {
			return loadedFrom;
		}

	}

}
