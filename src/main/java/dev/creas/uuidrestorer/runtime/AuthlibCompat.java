package dev.creas.uuidrestorer.runtime;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AuthlibCompat {
    private static final String[] PROFILE_ID_METHODS = {"id", "getId"};
    private static final String[] PROFILE_NAME_METHODS = {"name", "getName"};
    private static final String[] PROFILE_PROPERTIES_METHODS = {"properties", "getProperties"};
    private static final String[] PROPERTY_NAME_METHODS = {"name", "getName"};
    private static final String[] PROPERTY_VALUE_METHODS = {"value", "getValue"};
    private static final String[] PROPERTY_SIGNATURE_METHODS = {"signature", "getSignature"};

    private AuthlibCompat() {
    }

    public static GameProfile createGameProfile(UUID id, String name, String texturesValue, String texturesSignature) {
        List<AuthPropertyData> properties = new ArrayList<>();
        if (texturesValue != null && !texturesValue.isBlank()) {
            properties.add(new AuthPropertyData("textures", texturesValue, texturesSignature));
        }
        return createGameProfile(id, name, properties);
    }

    public static GameProfile createGameProfile(UUID id, String name, Collection<AuthPropertyData> properties) {
        if (!properties.isEmpty()) {
            PropertyMap propertyMap = createPropertyMap(properties);
            GameProfile threeArgProfile = instantiateGameProfile(id, name, propertyMap);
            if (threeArgProfile != null) {
                return threeArgProfile;
            }
        }

        GameProfile twoArgProfile = instantiateGameProfile(id, name);
        if (twoArgProfile == null) {
            throw new IllegalStateException("Unable to construct GameProfile for current authlib");
        }
        if (!properties.isEmpty() && !tryPopulateProperties(twoArgProfile, properties)) {
            throw new IllegalStateException("Unable to populate GameProfile properties for current authlib");
        }
        return twoArgProfile;
    }

    public static ResolvedProfile toResolvedProfile(Object profile) {
        UUID uuid = readUuid(profile);
        String name = readName(profile);
        AuthPropertyData textures = readFirstProperty(profile, "textures").orElse(null);
        return new ResolvedProfile(
            uuid,
            name,
            textures == null ? null : textures.value(),
            textures == null ? null : textures.signature()
        );
    }

    public static UUID readUuid(Object profile) {
        Object value = invokeCompatible(profile, PROFILE_ID_METHODS);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        throw new IllegalStateException("authlib GameProfile UUID accessor returned " + value);
    }

    public static String readName(Object profile) {
        Object value = invokeCompatible(profile, PROFILE_NAME_METHODS);
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalStateException("authlib GameProfile name accessor returned " + value);
    }

    public static List<AuthPropertyData> readProperties(Object profile, String propertyName) {
        Object rawProperties = invokeCompatible(profile, PROFILE_PROPERTIES_METHODS);
        Collection<?> rawEntries = extractPropertyEntries(rawProperties, propertyName);
        List<AuthPropertyData> properties = new ArrayList<>(rawEntries.size());
        for (Object rawEntry : rawEntries) {
            properties.add(readProperty(rawEntry));
        }
        return properties;
    }

    public static java.util.Optional<AuthPropertyData> readFirstProperty(Object profile, String propertyName) {
        List<AuthPropertyData> properties = readProperties(profile, propertyName);
        return properties.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(properties.get(0));
    }

    private static GameProfile instantiateGameProfile(UUID id, String name) {
        try {
            Constructor<GameProfile> constructor = GameProfile.class.getConstructor(UUID.class, String.class);
            return constructor.newInstance(id, name);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    private static GameProfile instantiateGameProfile(UUID id, String name, PropertyMap propertyMap) {
        try {
            Constructor<GameProfile> constructor = GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class);
            return constructor.newInstance(id, name, propertyMap);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    private static boolean tryPopulateProperties(GameProfile profile, Collection<AuthPropertyData> properties) {
        try {
            Object rawProperties = invokeCompatible(profile, PROFILE_PROPERTIES_METHODS);
            if (rawProperties instanceof Multimap<?, ?> multimap) {
                @SuppressWarnings("unchecked")
                Multimap<String, Object> propertyMultimap = (Multimap<String, Object>) multimap;
                for (AuthPropertyData property : properties) {
                    propertyMultimap.put(property.name(), createProperty(property));
                }
                return true;
            }
            if (rawProperties instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> propertyMap = (Map<String, Object>) map;
                for (AuthPropertyData property : properties) {
                    propertyMap.put(property.name(), List.of(createProperty(property)));
                }
                return true;
            }
        } catch (UnsupportedOperationException | IllegalStateException ignored) {
        }
        return false;
    }

    private static PropertyMap createPropertyMap(Collection<AuthPropertyData> properties) {
        Multimap<String, Object> multimap = HashMultimap.create();
        for (AuthPropertyData property : properties) {
            multimap.put(property.name(), createProperty(property));
        }

        try {
            Constructor<PropertyMap> constructor = PropertyMap.class.getDeclaredConstructor(Multimap.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            Multimap<String, com.mojang.authlib.properties.Property> typedMultimap =
                (Multimap<String, com.mojang.authlib.properties.Property>) (Multimap<?, ?>) multimap;
            return constructor.newInstance(typedMultimap);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException ignored) {
        }

        try {
            Constructor<PropertyMap> constructor = PropertyMap.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            PropertyMap propertyMap = constructor.newInstance();
            propertyMap.putAll((Multimap) multimap);
            return propertyMap;
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Unable to construct authlib PropertyMap", exception);
        }
    }

    private static Object createProperty(AuthPropertyData property) {
        try {
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            if (property.signature() != null && !property.signature().isBlank()) {
                Constructor<?> constructor = propertyClass.getConstructor(String.class, String.class, String.class);
                return constructor.newInstance(property.name(), property.value(), property.signature());
            }
            Constructor<?> constructor = propertyClass.getConstructor(String.class, String.class);
            return constructor.newInstance(property.name(), property.value());
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Unable to construct authlib Property", exception);
        }
    }

    private static AuthPropertyData readProperty(Object property) {
        String name = readString(property, PROPERTY_NAME_METHODS);
        String value = readString(property, PROPERTY_VALUE_METHODS);
        String signature = readOptionalString(property, PROPERTY_SIGNATURE_METHODS);
        return new AuthPropertyData(name, value, signature);
    }

    private static String readString(Object target, String[] methodNames) {
        Object value = invokeCompatible(target, methodNames);
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalStateException("authlib property accessor returned " + value);
    }

    private static String readOptionalString(Object target, String[] methodNames) {
        try {
            Object value = invokeCompatible(target, methodNames);
            return value instanceof String string ? string : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static Object invokeCompatible(Object target, String[] methodNames) {
        Objects.requireNonNull(target, "target");
        Class<?> type = target.getClass();
        for (String methodName : methodNames) {
            try {
                Method method = type.getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("Failed to invoke authlib method '" + methodName + "' on " + type.getName(), exception);
            }
        }
        throw new IllegalStateException("No compatible authlib method found on " + type.getName() + " for " + String.join("/", methodNames));
    }

    private static Collection<?> extractPropertyEntries(Object rawProperties, String propertyName) {
        if (rawProperties instanceof Multimap<?, ?> multimap) {
            @SuppressWarnings("unchecked")
            Multimap<Object, Object> propertyMultimap = (Multimap<Object, Object>) multimap;
            Object values = propertyMultimap.get(propertyName);
            return values instanceof Collection<?> collection ? collection : List.of();
        }
        if (rawProperties instanceof Map<?, ?> map) {
            Object values = map.get(propertyName);
            if (values instanceof Collection<?> collection) {
                return collection;
            }
            if (values != null) {
                return List.of(values);
            }
            return List.of();
        }
        throw new IllegalStateException("Unsupported authlib properties container: " + rawProperties.getClass().getName());
    }

    public record AuthPropertyData(String name, String value, String signature) {
    }
}
