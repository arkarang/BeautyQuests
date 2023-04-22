package fr.skytasul.quests.api.serializable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.NotNull;
import fr.skytasul.quests.BeautyQuests;
import fr.skytasul.quests.utils.Utils;

public abstract class SerializableObject {
	
	protected final @NotNull SerializableCreator creator;

	protected SerializableObject(@NotNull SerializableRegistry registry) {
		this.creator = registry.getByClass(getClass());
		if (creator == null) throw new IllegalArgumentException(getClass().getName() + " has not been registered as an object.");
	}
	
	protected SerializableObject(@NotNull SerializableCreator creator) {
		this.creator = creator;
		if (creator == null) throw new IllegalArgumentException("Creator cannot be null.");
	}

	public @NotNull SerializableCreator getCreator() {
		return creator;
	}

	public @NotNull String getName() {
		return creator.getID();
	}

	@Override
	public abstract @NotNull SerializableObject clone();
	
	public abstract void save(@NotNull ConfigurationSection section);
	
	public abstract void load(@NotNull ConfigurationSection section);
	
	public final void serialize(@NotNull ConfigurationSection section) {
		section.set("id", creator.getID());
		save(section);
	}

	public static <T extends SerializableObject, C extends SerializableCreator<T>> T deserialize(
			@NotNull Map<String, Object> map, @NotNull SerializableRegistry<T, C> registry) {
		return deserialize(Utils.createConfigurationSection(map), registry);
	}
	
	public static <T extends SerializableObject, C extends SerializableCreator<T>> @NotNull T deserialize(
			@NotNull ConfigurationSection section, @NotNull SerializableRegistry<T, C> registry) {
		SerializableCreator<T> creator = null;
		
		String id = section.getString("id");
		if (id != null) creator = registry.getByID(id);
		
		if (creator == null && section.contains("class")) {
			String className = section.getString("class");
			try {
				creator = registry.getByClass(Class.forName(className));
			}catch (ClassNotFoundException e) {}
			
			if (creator == null) {
				BeautyQuests.logger.severe("Cannot find object class " + className);
				return null;
			}
		}
		if (creator == null) {
			BeautyQuests.logger.severe("Cannot find object creator with id: " + id);
			return null;
		}
		T reward = creator.newObject();
		reward.load(section);
		return reward;
	}

	public static <T extends SerializableObject> @NotNull List<T> deserializeList(@NotNull List<Map<?, ?>> objectList,
			@NotNull Function<Map<String, Object>, T> deserializeFunction) {
		List<T> objects = new ArrayList<>(objectList.size());
		for (Map<?, ?> objectMap : objectList) {
			try {
				T object = deserializeFunction.apply((Map<String, Object>) objectMap);
				if (object == null) {
					BeautyQuests.loadingFailure = true;
					BeautyQuests.getInstance().getLogger().severe("The quest object for class " + String.valueOf(objectMap.get("class")) + " has not been deserialized.");
				}else objects.add(object);
			}catch (Exception e) {
				BeautyQuests.logger.severe("An exception occured while deserializing a quest object (class " + objectMap.get("class") + ").", e);
				BeautyQuests.loadingFailure = true;
			}
		}
		return objects;
	}
	
	public static @NotNull List<Map<String, Object>> serializeList(@NotNull List<? extends SerializableObject> objects) {
		return objects.stream().map(object -> {
			MemoryConfiguration section = new MemoryConfiguration();
			object.serialize(section);
			return section.getValues(false);
		}).collect(Collectors.toList());
	}
	
}