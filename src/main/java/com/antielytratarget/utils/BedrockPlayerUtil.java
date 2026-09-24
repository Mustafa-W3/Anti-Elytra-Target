package com.antielytratarget.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BedrockPlayerUtil {

    private static final String FLOODGATE_UUID_PREFIX = "00000000-0000-0000-0009";
    private static final ApiSpec[] API_SPECS = {
            new ApiSpec("org.geysermc.floodgate.api.FloodgateApi",
                    "getInstance", "isFloodgatePlayer"),
            new ApiSpec("org.geysermc.api.Geyser",
                    "api", "isBedrockPlayer"),
            new ApiSpec("org.geysermc.geyser.api.GeyserApi",
                    "api", "isBedrockPlayer")
    };
    private static final ApiAdapter[] NO_ADAPTERS = new ApiAdapter[0];
    private static volatile ApiAdapter[] cachedAdapters;

    private BedrockPlayerUtil() {
    }

    public static void initialize() {
        adapters();
    }

    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) return false;

        if (isFloodgateUuid(uuid)) return true;

        for (ApiAdapter adapter : adapters()) {
            if (adapter.isBedrockPlayer(uuid)) {
                return true;
            }
        }
        return false;
    }

    static boolean isFloodgateUuid(UUID uuid) {
        return uuid != null && uuid.toString().startsWith(FLOODGATE_UUID_PREFIX);
    }

    public static boolean hasConfiguredNamePrefix(String playerName, String prefix) {
        return playerName != null
                && prefix != null
                && !prefix.isEmpty()
                && playerName.startsWith(prefix);
    }

    private static ApiAdapter[] adapters() {
        ApiAdapter[] adapters = cachedAdapters;
        if (adapters != null) return adapters;

        synchronized (BedrockPlayerUtil.class) {
            adapters = cachedAdapters;
            if (adapters != null) return adapters;

            Set<ClassLoader> loaders = candidateClassLoaders();
            List<ApiAdapter> discovered = new ArrayList<>(API_SPECS.length);
            for (ApiSpec spec : API_SPECS) {
                ApiAdapter adapter = resolveAdapter(spec, loaders);
                if (adapter != null) {
                    discovered.add(adapter);
                }
            }

            adapters = discovered.isEmpty()
                    ? NO_ADAPTERS
                    : discovered.toArray(new ApiAdapter[0]);
            cachedAdapters = adapters;
            return adapters;
        }
    }

    private static ApiAdapter resolveAdapter(ApiSpec spec, Set<ClassLoader> loaders) {
        for (ClassLoader loader : loaders) {
            try {
                Class<?> apiClass = Class.forName(spec.className, false, loader);
                Method factory = apiClass.getMethod(spec.factoryMethod);
                Object api = factory.invoke(null);
                if (api == null) continue;

                Method playerCheck = apiClass.getMethod(spec.playerCheckMethod, UUID.class);
                return new ApiAdapter(api, playerCheck);
            } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            }
        }
        return null;
    }

    private static Set<ClassLoader> candidateClassLoaders() {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        addLoader(loaders, BedrockPlayerUtil.class.getClassLoader());
        addLoader(loaders, Thread.currentThread().getContextClassLoader());

        try {
            if (Bukkit.getServer() != null) {
                for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    String name = plugin.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.contains("floodgate") || name.contains("geyser")) {
                        addLoader(loaders, plugin.getClass().getClassLoader());
                    }
                }
            }
        } catch (LinkageError | RuntimeException ignored) {
        }
        return loaders;
    }

    private static void addLoader(Set<ClassLoader> loaders, ClassLoader loader) {
        if (loader != null) loaders.add(loader);
    }

    private static final class ApiSpec {
        private final String className;
        private final String factoryMethod;
        private final String playerCheckMethod;

        private ApiSpec(String className, String factoryMethod, String playerCheckMethod) {
            this.className = className;
            this.factoryMethod = factoryMethod;
            this.playerCheckMethod = playerCheckMethod;
        }
    }

    private static final class ApiAdapter {
        private final Object api;
        private final Method playerCheck;

        private ApiAdapter(Object api, Method playerCheck) {
            this.api = api;
            this.playerCheck = playerCheck;
        }

        private boolean isBedrockPlayer(UUID uuid) {
            try {
                return Boolean.TRUE.equals(playerCheck.invoke(api, uuid));
            } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
                return false;
            }
        }
    }
}
