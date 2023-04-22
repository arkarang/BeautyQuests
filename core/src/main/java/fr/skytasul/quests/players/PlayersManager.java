package fr.skytasul.quests.players;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.skytasul.quests.BeautyQuests;
import fr.skytasul.quests.QuestsConfiguration;
import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.data.SavableData;
import fr.skytasul.quests.api.events.accounts.PlayerAccountJoinEvent;
import fr.skytasul.quests.api.events.accounts.PlayerAccountLeaveEvent;
import fr.skytasul.quests.players.accounts.AbstractAccount;
import fr.skytasul.quests.players.accounts.UUIDAccount;
import fr.skytasul.quests.structure.Quest;
import fr.skytasul.quests.structure.pools.QuestPool;
import fr.skytasul.quests.utils.DebugUtils;
import fr.skytasul.quests.utils.compatibility.Accounts;
import fr.skytasul.quests.utils.compatibility.MissingDependencyException;

public abstract class PlayersManager {

	protected final @NotNull Map<Player, PlayerAccount> cachedAccounts = new HashMap<>();
	protected final @NotNull Set<@NotNull SavableData<?>> accountDatas = new HashSet<>();
	private boolean loaded = false;

	public abstract void load(@NotNull AccountFetchRequest request);
	
	public abstract void unloadAccount(@NotNull PlayerAccount acc);

	protected abstract @NotNull CompletableFuture<Void> removeAccount(@NotNull PlayerAccount acc);
	
	public abstract @NotNull CompletableFuture<Integer> removeQuestDatas(@NotNull Quest quest);

	public abstract @NotNull PlayerQuestDatas createPlayerQuestDatas(@NotNull PlayerAccount acc, @NotNull Quest quest);

	public abstract @NotNull PlayerPoolDatas createPlayerPoolDatas(@NotNull PlayerAccount acc, @NotNull QuestPool pool);

	public @NotNull CompletableFuture<Void> playerQuestDataRemoved(@NotNull PlayerQuestDatas datas) {
		return CompletableFuture.completedFuture(null);
	}
	
	public @NotNull CompletableFuture<Void> playerPoolDataRemoved(@NotNull PlayerPoolDatas datas) {
		return CompletableFuture.completedFuture(null);
	}

	public void load() {
		if (loaded) throw new IllegalStateException("Already loaded");
		loaded = true;
	}
	
	public boolean isLoaded() {
		return loaded;
	}

	public abstract void save();
	
	public void addAccountData(@NotNull SavableData<?> data) {
		if (loaded)
			throw new IllegalStateException("Cannot add account data after players manager has been loaded");
		if (PlayerAccount.FORBIDDEN_DATA_ID.contains(data.getId()))
			throw new IllegalArgumentException("Forbidden account data id " + data.getId());
		if (accountDatas.stream().anyMatch(x -> x.getId().equals(data.getId())))
			throw new IllegalArgumentException("Another account data already exists with the id " + data.getId());
		if (data.getDataType().isPrimitive())
			throw new IllegalArgumentException("Primitive account data types are not supported");
		accountDatas.add(data);
		DebugUtils.logMessage("Registered account data " + data.getId());
	}
	
	public @NotNull Collection<@NotNull SavableData<?>> getAccountDatas() {
		return accountDatas;
	}

	protected @NotNull AbstractAccount createAbstractAccount(@NotNull Player p) {
		return QuestsConfiguration.hookAccounts() ? Accounts.getPlayerAccount(p) : new UUIDAccount(p.getUniqueId());
	}

	protected @NotNull String getIdentifier(@NotNull OfflinePlayer p) {
		if (QuestsConfiguration.hookAccounts()) {
			if (!p.isOnline())
				throw new IllegalArgumentException("Cannot fetch player identifier of an offline player with AccountsHook");
			return "Hooked|" + Accounts.getPlayerCurrentIdentifier(p.getPlayer());
		}
		return p.getUniqueId().toString();
	}

	protected @Nullable AbstractAccount createAccountFromIdentifier(@NotNull String identifier) {
		if (identifier.startsWith("Hooked|")){
			if (!QuestsConfiguration.hookAccounts()) throw new MissingDependencyException("AccountsHook is not enabled or config parameter is disabled, but saved datas need it.");
			String nidentifier = identifier.substring(7);
			try{
				return Accounts.getAccountFromIdentifier(nidentifier);
			}catch (Exception ex){
				ex.printStackTrace();
			}
		}else {
			try{
				UUID uuid = UUID.fromString(identifier);
				if (QuestsConfiguration.hookAccounts()){
					try{
						return Accounts.createAccountFromUUID(uuid);
					}catch (UnsupportedOperationException ex){
						BeautyQuests.logger.warning("Can't migrate an UUID account to a hooked one.");
					}
				}else return new UUIDAccount(uuid);
			}catch (IllegalArgumentException ex){
				BeautyQuests.logger.warning("Account identifier " + identifier + " is not valid.");
			}
		}
		return null;
	}
	
	public synchronized void loadPlayer(@NotNull Player p) {
		cachedPlayerNames.put(p.getUniqueId(), p.getName());

		long time = System.currentTimeMillis();
		DebugUtils.logMessage("Loading player " + p.getName() + "...");
		cachedAccounts.remove(p);
		Bukkit.getScheduler().runTaskAsynchronously(BeautyQuests.getInstance(), () -> {
			for (int i = 1; i >= 0; i--) {
				try {
					if (!tryLoad(p, time))
						return;
				} catch (Exception ex) {
					BeautyQuests.logger.severe("An error ocurred while trying to load datas of " + p.getName() + ".", ex);
				}
				if (i > 0)
					BeautyQuests.logger.severe("Doing " + i + " more attempt.");
			}
			BeautyQuests.logger.severe("Datas of " + p.getName() + " have failed to load. This may cause MANY issues.");
		});
	}

	private boolean tryLoad(@NotNull Player p, long time) {
		if (!p.isOnline()) {
			BeautyQuests.logger
					.warning("Player " + p.getName() + " has quit the server while loading its datas. This may be a bug.");
			return false;
		}

		AccountFetchRequest request = new AccountFetchRequest(p, time, true, true);
		load(request);

		if (!request.isFinished() || request.getAccount() == null) {
			BeautyQuests.logger.severe("The account of " + p.getName() + " has not been properly loaded.");
			return true;
		}

		if (!p.isOnline()) {
			if (request.isAccountCreated()) {
				DebugUtils.logMessage(
						"New account registered for " + p.getName() + "... but deleted as player left before loading.");
				removeAccount(request.getAccount()).whenComplete(
						BeautyQuests.logger.logError("An error occurred while removing newly created account"));
			}
			return false;
		}

		if (request.isAccountCreated())
			DebugUtils.logMessage(
					"New account registered for " + p.getName() + " (" + request.getAccount().abstractAcc.getIdentifier()
							+ "), index " + request.getAccount().index + " via " + DebugUtils.stackTraces(2, 4));

		cachedAccounts.put(p, request.getAccount());
		Bukkit.getScheduler().runTask(BeautyQuests.getInstance(), () -> {
			String loadMessage =
					"Completed load of " + p.getName() + " (" + request.getAccount().debugName() + ") datas within "
							+ (System.currentTimeMillis() - time) + " ms (" + request.getAccount().getQuestsDatas().size()
							+ " quests, " + request.getAccount().getPoolDatas().size() + " pools)";

			if (request.getLoadedFrom() != null)
				loadMessage += " | Loaded from " + request.getLoadedFrom();

			DebugUtils.logMessage(loadMessage);

			if (p.isOnline()) {
				Bukkit.getPluginManager()
						.callEvent(new PlayerAccountJoinEvent(request.getAccount(), request.isAccountCreated()));
			} else {
				BeautyQuests.logger.warning(
						"Player " + p.getName() + " has quit the server while loading its datas. This may be a bug.");

				if (request.isAccountCreated())
					removeAccount(request.getAccount()).whenComplete(
							BeautyQuests.logger.logError("An error occurred while removing newly created account"));
			}
		});
		return false;
	}
	
	public synchronized void unloadPlayer(@NotNull Player p) {
		PlayerAccount acc = cachedAccounts.get(p);
		if (acc == null) return;
		DebugUtils.logMessage("Unloading player " + p.getName() + "... (" + acc.getQuestsDatas().size() + " quests, " + acc.getPoolDatas().size() + " pools)");
		Bukkit.getPluginManager().callEvent(new PlayerAccountLeaveEvent(acc));
		unloadAccount(acc);
		cachedAccounts.remove(p);
	}
	
	public @UnknownNullability PlayerAccount getAccount(@NotNull Player p) {
		if (QuestsAPI.getNPCsManager().isNPC(p)) return null;
		if (!p.isOnline()) {
			BeautyQuests.logger.severe("Trying to fetch the account of an offline player (" + p.getName() + ")");
			DebugUtils.logMessage("(via " + DebugUtils.stackTraces(2, 5) + ")");
		}
		
		return cachedAccounts.get(p);
	}

	private static Map<UUID, String> cachedPlayerNames = new HashMap<>();
	private static Gson gson = new Gson();
	private static long lastOnlineFailure = 0;

	/**
	 * @deprecated use {@link BeautyQuests#getPlayersManager()}
	 */
	@Deprecated
	public static PlayersManager manager; // TODO remove, changed in 0.20.1

	public static @UnknownNullability PlayerAccount getPlayerAccount(@NotNull Player p) {
		return BeautyQuests.getInstance().getPlayersManager().getAccount(p);
	}

	public static synchronized @Nullable String getPlayerName(@NotNull UUID uuid) {
		if (cachedPlayerNames.containsKey(uuid))
			return cachedPlayerNames.get(uuid);

		String name;
		if (Bukkit.getOnlineMode()) {
			try {
				if (System.currentTimeMillis() - lastOnlineFailure < 30_000) {
					DebugUtils.logMessage("Trying to fetch a name from an UUID but it failed within 30 seconds.");
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
					DebugUtils.logMessage("Cannot find name for UUID " + uuid.toString());
				} else {
					name = nameElement.getAsString();
				}
			} catch (Exception e) {
				BeautyQuests.logger.warning("Cannot connect to the mojang servers. UUIDs cannot be parsed.");
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
		private final OfflinePlayer player;
		private final long joinTimestamp;
		private final boolean allowCreation;
		private final boolean shouldCache;

		private boolean finished = false;
		private boolean created;
		private PlayerAccount account;
		private String loadedFrom;

		public AccountFetchRequest(OfflinePlayer player, long joinTimestamp, boolean allowCreation, boolean shouldCache) {
			this.player = player;
			this.joinTimestamp = joinTimestamp;
			this.allowCreation = allowCreation;
			this.shouldCache = shouldCache;

			if (allowCreation && !player.isOnline())
				throw new IllegalArgumentException("Cannot create an account for an offline player.");
		}

		public OfflinePlayer getOfflinePlayer() {
			return player;
		}

		public Player getOnlinePlayer() {
			if (player.isOnline())
				return player.getPlayer();
			throw new IllegalStateException("The player " + player.getName() + " is offline.");
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
			String name = player.getName();
			if (name == null)
				name = player.getUniqueId().toString();
			return name;
		}

		/**
		 * This method must be called when the request results in a successfully loaded account.
		 * 
		 * @param account account that has been loaded
		 * @param from source of the saved account
		 */
		public void loaded(PlayerAccount account, String from) {
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
		public void created(PlayerAccount account) {
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

		public PlayerAccount getAccount() {
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
