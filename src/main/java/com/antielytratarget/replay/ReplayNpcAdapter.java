package com.antielytratarget.replay;

import com.antielytratarget.AntiElytraTargetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ReplayNpcAdapter {

    private final AntiElytraTargetPlugin plugin;
    private final String craftBukkitPackage;

    private boolean ready;
    private Class<?> craftPlayerClass;
    private Class<?> craftWorldClass;
    private Class<?> gameProfileClass;
    private Class<?> serverPlayerClass;
    private Class<?> clientInformationClass;
    private Constructor<?> serverPlayerConstructor;
    private Constructor<?> gameProfileConstructor;
    private Method getHandleMethod;
    private Method craftWorldGetHandle;
    private Method craftServerGetServer;
    private Method clientInfoCreateDefault;
    private volatile Method setPosMethod;
    private volatile Method setYRotMethod;
    private volatile Method setXRotMethod;
    private volatile Constructor<?> teleportPacketConstructor;
    private volatile Constructor<?> relativeMovePacketConstructor;
    private volatile Constructor<?> rotateHeadPacketConstructor;
    private volatile Field viewerConnectionField;
    private volatile Method connectionSendMethod;

    public ReplayNpcAdapter(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        this.craftBukkitPackage = Bukkit.getServer().getClass().getPackage().getName();
        init();
    }

    public boolean isReady() {
        return ready;
    }

    private void init() {
        try {
            craftPlayerClass = Class.forName(craftBukkitPackage + ".entity.CraftPlayer");
            craftWorldClass = Class.forName(craftBukkitPackage + ".CraftWorld");
            gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            try {
                clientInformationClass = Class.forName("net.minecraft.server.level.ClientInformation");
            } catch (ClassNotFoundException ignored) {
                clientInformationClass = null;
            }

            Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
            Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");

            gameProfileConstructor = gameProfileClass.getConstructor(UUID.class, String.class);
            if (clientInformationClass != null) {
                try {
                    serverPlayerConstructor = serverPlayerClass.getConstructor(
                            minecraftServerClass, serverLevelClass, gameProfileClass, clientInformationClass);
                } catch (NoSuchMethodException ignored) {
                    serverPlayerConstructor = serverPlayerClass.getConstructor(
                            minecraftServerClass, serverLevelClass, gameProfileClass);
                    clientInformationClass = null;
                }
            } else {
                serverPlayerConstructor = serverPlayerClass.getConstructor(
                        minecraftServerClass, serverLevelClass, gameProfileClass);
            }

            getHandleMethod = craftPlayerClass.getMethod("getHandle");
            craftWorldGetHandle = craftWorldClass.getMethod("getHandle");
            craftServerGetServer = Bukkit.getServer().getClass().getMethod("getServer");
            clientInfoCreateDefault = clientInformationClass != null
                    ? clientInformationClass.getMethod("createDefault")
                    : null;
            ready = true;
        } catch (Exception e) {
            ready = false;
            plugin.getLogger().warning("[ReplayNPC] Player NPC adapter unavailable: " + e.getMessage());
        }
    }

    public Npc spawn(Player viewer, UUID uuid, String name, Location location) {
        return spawn(viewer, uuid, name, location, ReplaySkinSnapshot.empty());
    }

    public Npc spawn(Player viewer, UUID uuid, String name, Location location, ReplaySkinSnapshot skin) {
        if (!ready || viewer == null || location == null || location.getWorld() == null) return null;
        try {
            Object server = craftServerGetServer.invoke(Bukkit.getServer());
            Object level = craftWorldGetHandle.invoke(location.getWorld());
            Object profile = gameProfileConstructor.newInstance(uuid, sanitizeName(name, uuid));
            applySkin(profile, skin);
            Object nmsPlayer = createServerPlayer(server, level, profile);

            attachFakeConnection(server, nmsPlayer, profile);
            setPosition(nmsPlayer, location);
            setGameModeSurvival(nmsPlayer);
            setHealth(nmsPlayer);
            setPlayerSkinLayers(nmsPlayer);

            Npc npc = new Npc(uuid, name, nmsPlayer, getEntityId(nmsPlayer), location.clone(), skinKey(skin));
            sendPlayerInfoAdd(viewer, nmsPlayer);
            sendEntitySpawn(viewer, nmsPlayer);
            sendEntityData(viewer, nmsPlayer, false);
            return npc;
        } catch (Exception e) {
            plugin.getLogger().warning("[ReplayNPC] Spawn failed for " + name + ": " + describe(e));
            return null;
        }
    }

    public boolean hasSkin(Npc npc, ReplaySkinSnapshot skin) {
        return npc != null && Objects.equals(npc.skinKey, skinKey(skin));
    }

    public void move(Player viewer, Npc npc, Location location, boolean onGround, boolean swing) {
        move(viewer, npc, location, onGround, swing, false);
    }

    public void move(Player viewer, Npc npc, Location location,
                     boolean onGround, boolean swing, boolean forceTeleport) {
        if (!ready || npc == null || location == null) return;
        try {
            boolean relative = !forceTeleport
                    && npc.relativeMovesSinceTeleport < 100
                    && canSendRelativeMove(npc.lastLocation, location);
            setPosition(npc.nmsPlayer, location);
            if (relative) {
                try {
                    sendRelativeMove(viewer, npc, location, onGround);
                    npc.relativeMovesSinceTeleport++;
                } catch (Exception relativeFailure) {
                    if (sendTeleport(viewer, npc.nmsPlayer)) {
                        npc.relativeMovesSinceTeleport = 0;
                    } else {
                        throw relativeFailure;
                    }
                }
            } else if (sendTeleport(viewer, npc.nmsPlayer)) {
                npc.relativeMovesSinceTeleport = 0;
            } else {
                sendRelativeMove(viewer, npc, location, onGround);
            }
            sendHeadRotation(viewer, npc.nmsPlayer, location.getYaw());
            if (swing) sendSwing(viewer, npc.nmsPlayer);
            npc.lastLocation = location.clone();
        } catch (Exception e) {
            plugin.debug("[ReplayNPC] Move failed for " + npc.name + ": " + e.getMessage());
        }
    }

    private boolean canSendRelativeMove(Location previous, Location next) {
        if (previous == null || next == null || previous.getWorld() != next.getWorld()) return false;
        return Math.abs(next.getX() - previous.getX()) < 7.9
                && Math.abs(next.getY() - previous.getY()) < 7.9
                && Math.abs(next.getZ() - previous.getZ()) < 7.9;
    }

    public void updateEquipment(Player viewer, Npc npc,
                                String mainHand, String offHand,
                                String helmet, String chestplate, String leggings, String boots) {
        if (!ready || npc == null) return;

        String key = String.join("|",
                normalizeMaterial(mainHand), normalizeMaterial(offHand),
                normalizeMaterial(helmet), normalizeMaterial(chestplate),
                normalizeMaterial(leggings), normalizeMaterial(boots));
        if (key.equals(npc.lastEquipmentKey)) return;

        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket");
            Class<?> equipmentSlotClass = Class.forName("net.minecraft.world.entity.EquipmentSlot");
            Class<?> craftItemStackClass = Class.forName(craftBukkitPackage + ".inventory.CraftItemStack");
            Class<?> pairClass = Class.forName("com.mojang.datafixers.util.Pair");

            Method asNMSCopy = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
            Method pairOf = pairClass.getMethod("of", Object.class, Object.class);

            List<Object> equipment = new ArrayList<>();
            addEquipment(equipment, pairOf, equipmentSlotClass, asNMSCopy, "MAINHAND", mainHand);
            addEquipment(equipment, pairOf, equipmentSlotClass, asNMSCopy, "OFFHAND", offHand);
            addEquipment(equipment, pairOf, equipmentSlotClass, asNMSCopy, "HEAD", helmet);
            addEquipment(equipment, pairOf, equipmentSlotClass, asNMSCopy, "CHEST", chestplate);
            addEquipment(equipment, pairOf, equipmentSlotClass, asNMSCopy, "LEGS", leggings);
            addEquipment(equipment, pairOf, equipmentSlotClass, asNMSCopy, "FEET", boots);

            Constructor<?> constructor = findConstructor(packetClass, 2);
            sendPacket(viewer, constructor.newInstance(npc.entityId, equipment));
            npc.lastEquipmentKey = key;
        } catch (Exception e) {
            warnOnce(npc, true, "[ReplayNPC] Equipment update failed for " + npc.name + ": " + describe(e));
        }
    }

    public void updateState(Player viewer, Npc npc, boolean sneaking, boolean sprinting, boolean gliding) {
        if (!ready || npc == null) return;

        String key = sneaking + "|" + sprinting + "|" + gliding;
        if (key.equals(npc.lastStateKey)) return;

        try {
            Object data = invoke(npc.nmsPlayer, "getEntityData");
            Method set = findMethod(data.getClass(), "set", 2);

            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
            Object flagsAccessor = staticFieldValue(entityClass, "DATA_SHARED_FLAGS_ID");
            Object poseAccessor = staticFieldValue(entityClass, "DATA_POSE");

            byte flags = 0;
            if (sneaking) flags |= 0x02;
            if (sprinting) flags |= 0x08;
            if (gliding) flags |= (byte) 0x80;
            set.invoke(data, flagsAccessor, flags);

            Class<?> poseClass = Class.forName("net.minecraft.world.entity.Pose");
            String poseName = gliding ? "FALL_FLYING" : (sneaking ? "CROUCHING" : "STANDING");
            set.invoke(data, poseAccessor, enumConstant(poseClass, poseName));

            sendEntityData(viewer, npc.nmsPlayer, true);
            npc.lastStateKey = key;
        } catch (Exception e) {
            warnOnce(npc, false, "[ReplayNPC] State update failed for " + npc.name + ": " + describe(e));
        }
    }

    public void destroy(Player viewer, Npc npc) {
        if (!ready || npc == null) return;
        try {
            sendRemoveEntity(viewer, npc.entityId);
            sendPlayerInfoRemove(viewer, npc.uuid);
        } catch (Exception e) {
            plugin.debug("[ReplayNPC] Destroy failed for " + npc.name + ": " + e.getMessage());
        }
    }

    public void hurt(Player viewer, Npc npc) {
        if (!ready || npc == null) return;
        try {
            sendHurtAnimation(viewer, npc.nmsPlayer);
        } catch (Exception e) {
            plugin.debug("[ReplayNPC] Hurt animation failed for " + npc.name + ": " + e.getMessage());
        }
    }

    private Object createServerPlayer(Object server, Object level, Object profile) throws Exception {
        if (serverPlayerConstructor.getParameterCount() == 4) {
            Object info = clientInfoCreateDefault.invoke(null);
            return serverPlayerConstructor.newInstance(server, level, profile, info);
        }
        return serverPlayerConstructor.newInstance(server, level, profile);
    }

    private void applySkin(Object profile, ReplaySkinSnapshot skin) {
        if (profile == null || skin == null || !skin.hasTexture()) return;
        try {
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Object textureProperty;
            String signature = skin.getSignature().isBlank() ? null : skin.getSignature();
            try {
                textureProperty = propertyClass
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", skin.getValue(), signature);
            } catch (NoSuchMethodException ignored) {
                textureProperty = propertyClass
                        .getConstructor(String.class, String.class)
                        .newInstance("textures", skin.getValue());
            }

            Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
            Method put = findMethod(properties.getClass(), "put", 2);
            put.invoke(properties, "textures", textureProperty);
        } catch (Exception e) {
            plugin.debug("[ReplayNPC] Could not apply recorded skin: " + e.getMessage());
        }
    }

    private void attachFakeConnection(Object server, Object nmsPlayer, Object profile) throws Exception {
        Object connection = buildConnection();
        Object listener = buildGameListener(server, connection, nmsPlayer, profile);
        setFieldValue(nmsPlayer, "connection", listener);
    }

    private Object buildConnection() throws Exception {
        Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
        Class<?> packetFlowClass = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Object flow = enumConstant(packetFlowClass, "SERVERBOUND");
        Object connection = connectionClass.getConstructor(packetFlowClass).newInstance(flow);

        Object channel = Class.forName("io.netty.channel.embedded.EmbeddedChannel")
                .getConstructor()
                .newInstance();
        setAutoReadFalse(channel);
        setFieldByTypeName(connection, "io.netty.channel.Channel", channel);
        setFieldByType(connection, SocketAddress.class, new InetSocketAddress("127.0.0.1", 0));

        try {
            Class<?> protocolClass = Class.forName("net.minecraft.network.ConnectionProtocol");
            Object play = enumConstant(protocolClass, "PLAY");
            setFieldByType(connection, protocolClass, play);
        } catch (Exception ignored) {}

        return connection;
    }

    private Object buildGameListener(Object server, Object connection, Object nmsPlayer, Object profile) throws Exception {
        Class<?> listenerClass = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
        Object cookie = buildCommonListenerCookie(profile);

        for (Constructor<?> constructor : listenerClass.getConstructors()) {
            if (constructor.getParameterCount() == 4 && cookie != null) {
                return constructor.newInstance(server, connection, nmsPlayer, cookie);
            }
            if (constructor.getParameterCount() == 3) {
                return constructor.newInstance(server, connection, nmsPlayer);
            }
        }
        for (Constructor<?> constructor : listenerClass.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            if (constructor.getParameterCount() == 4 && cookie != null) {
                return constructor.newInstance(server, connection, nmsPlayer, cookie);
            }
            if (constructor.getParameterCount() == 3) {
                return constructor.newInstance(server, connection, nmsPlayer);
            }
        }
        throw new IllegalStateException("ServerGamePacketListenerImpl constructor not found");
    }

    private Object buildCommonListenerCookie(Object profile) {
        try {
            Class<?> cookieClass = Class.forName("net.minecraft.server.network.CommonListenerCookie");
            for (Method method : cookieClass.getMethods()) {
                if (method.getName().equals("createInitial") && method.getParameterCount() == 2) {
                    return method.invoke(null, profile, false);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void setPosition(Object entity, Location location) throws Exception {
        Class<?> type = entity.getClass();
        Method setPos = setPosMethod;
        if (!isUsableFor(setPos, type)) {
            setPos = type.getMethod("setPos", double.class, double.class, double.class);
            setPosMethod = setPos;
        }
        Method setYRot = setYRotMethod;
        if (!isUsableFor(setYRot, type)) {
            setYRot = type.getMethod("setYRot", float.class);
            setYRotMethod = setYRot;
        }
        Method setXRot = setXRotMethod;
        if (!isUsableFor(setXRot, type)) {
            setXRot = type.getMethod("setXRot", float.class);
            setXRotMethod = setXRot;
        }

        setPos.invoke(entity, location.getX(), location.getY(), location.getZ());
        setYRot.invoke(entity, location.getYaw());
        setXRot.invoke(entity, location.getPitch());
    }

    private void setGameModeSurvival(Object nmsPlayer) {
        try {
            Class<?> gameTypeClass = Class.forName("net.minecraft.world.level.GameType");
            Object survival = enumConstant(gameTypeClass, "SURVIVAL");
            Method setGameMode = findMethod(nmsPlayer.getClass(), "setGameMode", 1);
            setGameMode.invoke(nmsPlayer, survival);
        } catch (Exception ignored) {}
    }

    private void setHealth(Object nmsPlayer) {
        try {
            invoke(nmsPlayer, "setHealth", new Class<?>[]{float.class}, 20.0f);
        } catch (Exception ignored) {}
    }

    private void setPlayerSkinLayers(Object nmsPlayer) {
        try {
            Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player");
            Field customisation = playerClass.getField("DATA_PLAYER_MODE_CUSTOMISATION");
            Object accessor = customisation.get(null);
            Object data = invoke(nmsPlayer, "getEntityData");
            Method set = findMethod(data.getClass(), "set", 2);
            set.invoke(data, accessor, (byte) 0x7F);
        } catch (Exception ignored) {}
    }

    private void sendPlayerInfoAdd(Player viewer, Object nmsPlayer) throws Exception {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        Method create = packetClass.getMethod("createPlayerInitializing", Collection.class);
        sendPacket(viewer, create.invoke(null, Collections.singletonList(nmsPlayer)));
    }

    private void sendPlayerInfoRemove(Player viewer, UUID uuid) throws Exception {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
        Constructor<?> constructor = findConstructor(packetClass, 1);
        sendPacket(viewer, constructor.newInstance(Collections.singletonList(uuid)));
    }

    private void sendEntitySpawn(Player viewer, Object nmsPlayer) throws Exception {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
        sendPacket(viewer, createAddEntityPacket(packetClass, nmsPlayer));
    }

    private Object createAddEntityPacket(Class<?> packetClass, Object nmsPlayer) throws Exception {
        Object blockPos = invoke(nmsPlayer, "blockPosition");
        Exception lastFailure = null;

        for (Constructor<?> constructor : constructors(packetClass)) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 3
                    && accepts(types[0], nmsPlayer)
                    && isIntType(types[1])
                    && accepts(types[2], blockPos)) {
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(nmsPlayer, 0, blockPos);
                } catch (Exception e) {
                    lastFailure = e;
                }
            }
        }

        for (Constructor<?> constructor : constructors(packetClass)) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 2 && accepts(types[0], nmsPlayer) && isIntType(types[1])) {
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(nmsPlayer, 0);
                } catch (Exception e) {
                    lastFailure = e;
                }
            }
        }

        for (Constructor<?> constructor : constructors(packetClass)) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 1 && accepts(types[0], nmsPlayer)) {
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(nmsPlayer);
                } catch (Exception e) {
                    lastFailure = e;
                }
            }
        }

        if (lastFailure != null) throw lastFailure;
        throw new IllegalStateException("compatible ClientboundAddEntityPacket constructor not found");
    }

    private void sendEntityData(Player viewer, Object nmsPlayer, boolean dirtyOnly) {
        try {
            Object data = invoke(nmsPlayer, "getEntityData");
            Object values = null;
            if (dirtyOnly) {
                try {
                    values = invoke(data, "packDirty");
                } catch (Exception ignored) {}
            }
            if (isEmptyValues(values)) {
                values = invoke(data, "getNonDefaultValues");
                if (isEmptyValues(values)) return;
            }
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
            Constructor<?> constructor = findConstructor(packetClass, 2);
            sendPacket(viewer, constructor.newInstance(getEntityId(nmsPlayer), values));
        } catch (Exception ignored) {}
    }

    private boolean isEmptyValues(Object values) {
        return values == null || (values instanceof Collection<?> collection && collection.isEmpty());
    }

    private boolean sendTeleport(Player viewer, Object nmsPlayer) {
        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
            Constructor<?> constructor = teleportPacketConstructor;
            if (constructor == null || constructor.getDeclaringClass() != packetClass) {
                constructor = findConstructor(packetClass, 1);
                teleportPacketConstructor = constructor;
            }
            sendPacket(viewer, constructor.newInstance(nmsPlayer));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void sendRelativeMove(Player viewer, Npc npc, Location location, boolean onGround) throws Exception {
        Location old = npc.lastLocation != null ? npc.lastLocation : location;
        short dx = fixedDelta(location.getX(), old.getX());
        short dy = fixedDelta(location.getY(), old.getY());
        short dz = fixedDelta(location.getZ(), old.getZ());

        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$PosRot");
        Constructor<?> constructor = relativeMovePacketConstructor;
        if (constructor == null || constructor.getDeclaringClass() != packetClass) {
            constructor = findConstructor(packetClass, 7);
            relativeMovePacketConstructor = constructor;
        }
        sendPacket(viewer, constructor.newInstance(
                npc.entityId, dx, dy, dz, angle(location.getYaw()), angle(location.getPitch()), onGround));
    }

    private void sendHeadRotation(Player viewer, Object nmsPlayer, float yaw) throws Exception {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket");
        Constructor<?> constructor = rotateHeadPacketConstructor;
        if (constructor == null || constructor.getDeclaringClass() != packetClass) {
            constructor = findConstructor(packetClass, 2);
            rotateHeadPacketConstructor = constructor;
        }
        sendPacket(viewer, constructor.newInstance(nmsPlayer, angle(yaw)));
    }

    private void sendSwing(Player viewer, Object nmsPlayer) throws Exception {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundAnimatePacket");
        Field swing = packetClass.getField("SWING_MAIN_HAND");
        Constructor<?> constructor = findConstructor(packetClass, 2);
        sendPacket(viewer, constructor.newInstance(nmsPlayer, swing.getInt(null)));
    }

    private void sendHurtAnimation(Player viewer, Object nmsPlayer) throws Exception {
        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket");
            for (Constructor<?> constructor : packetClass.getConstructors()) {
                if (constructor.getParameterCount() == 1) {
                    sendPacket(viewer, constructor.newInstance(nmsPlayer));
                    return;
                }
                if (constructor.getParameterCount() == 2) {
                    sendPacket(viewer, constructor.newInstance(nmsPlayer, 0.0f));
                    return;
                }
            }
        } catch (Exception ignored) {
        }

        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundAnimatePacket");
        Constructor<?> constructor = findConstructor(packetClass, 2);
        sendPacket(viewer, constructor.newInstance(nmsPlayer, 1));
    }

    private void sendRemoveEntity(Player viewer, int entityId) throws Exception {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
        Constructor<?> constructor = packetClass.getConstructor(int[].class);
        sendPacket(viewer, constructor.newInstance((Object) new int[]{entityId}));
    }

    private void sendPacket(Player viewer, Object packet) throws Exception {
        Object handle = getHandleMethod.invoke(viewer);
        Object connection = findFieldValue(handle, "connection");
        if (connection == null) throw new IllegalStateException("viewer connection not found");

        Class<?> connectionClass = connection.getClass();
        Method send = connectionSendMethod;
        if (send == null || !send.getDeclaringClass().isAssignableFrom(connectionClass)) {
            send = null;
            for (Method method : connectionClass.getMethods()) {
                if (method.getName().equals("send") && method.getParameterCount() == 1) {
                    send = method;
                    break;
                }
            }
            if (send == null) {
                for (Method method : connectionClass.getDeclaredMethods()) {
                    if (method.getName().equals("send") && method.getParameterCount() == 1) {
                        method.setAccessible(true);
                        send = method;
                        break;
                    }
                }
            }
            if (send != null) connectionSendMethod = send;
        }
        if (send == null) throw new IllegalStateException("send method not found");
        send.invoke(connection, packet);
    }

    private void setAutoReadFalse(Object channel) {
        try {
            Object config = channel.getClass().getMethod("config").invoke(channel);
            config.getClass().getMethod("setAutoRead", boolean.class).invoke(config, false);
        } catch (Exception ignored) {}
    }

    private void setFieldValue(Object target, String preferredName, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(preferredName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {}
            type = type.getSuperclass();
        }
        if (!setFieldByTypeName(target, value.getClass().getName(), value)) {
            throw new IllegalStateException("field not found: " + preferredName);
        }
    }

    private boolean setFieldByType(Object target, Class<?> wantedType, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().isAssignableFrom(wantedType) || wantedType.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    field.set(target, value);
                    return true;
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private boolean setFieldByTypeName(Object target, String wantedTypeName, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().getName().equals(wantedTypeName)
                        || field.getType().getName().contains(wantedTypeName)) {
                    field.setAccessible(true);
                    field.set(target, value);
                    return true;
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addEquipment(List<Object> equipment, Method pairOf, Class<?> equipmentSlotClass,
                              Method asNMSCopy, String slotName, String materialName) throws Exception {
        Object slot = Enum.valueOf((Class<Enum>) equipmentSlotClass.asSubclass(Enum.class), slotName);
        Object nmsItem = asNMSCopy.invoke(null, toBukkitItem(materialName));
        equipment.add(pairOf.invoke(null, slot, nmsItem));
    }

    private ItemStack toBukkitItem(String materialName) {
        return ReplayItemCodec.decode(materialName);
    }

    private String normalizeMaterial(String materialName) {
        return ReplayItemCodec.normalize(materialName);
    }

    private String skinKey(ReplaySkinSnapshot skin) {
        return skin != null ? skin.key() : ReplaySkinSnapshot.empty().key();
    }

    private int getEntityId(Object entity) throws Exception {
        Object value = invoke(entity, "getId");
        return value instanceof Number number ? number.intValue() : -1;
    }

    private Object staticFieldValue(Class<?> type, String name) throws Exception {
        Field field;
        try {
            field = type.getField(name);
        } catch (NoSuchFieldException ignored) {
            field = type.getDeclaredField(name);
        }
        field.setAccessible(true);
        return field.get(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(target, args);
    }

    private boolean isUsableFor(Method method, Class<?> targetType) {
        return method != null && method.getDeclaringClass().isAssignableFrom(targetType);
    }

    private Object invoke(Object target, String methodName) throws Exception {
        Method method = findMethod(target.getClass(), methodName, 0);
        return method.invoke(target);
    }

    private Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("method not found: " + name);
    }

    private Constructor<?> findConstructor(Class<?> type, int parameterCount) {
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() == parameterCount) return constructor;
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == parameterCount) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new IllegalStateException("constructor not found: " + type.getName() + "/" + parameterCount);
    }

    private List<Constructor<?>> constructors(Class<?> type) {
        List<Constructor<?>> constructors = new ArrayList<>();
        Collections.addAll(constructors, type.getConstructors());
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!constructors.contains(constructor)) {
                constructors.add(constructor);
            }
        }
        return constructors;
    }

    private boolean accepts(Class<?> parameterType, Object value) {
        if (value == null) return !parameterType.isPrimitive();
        Class<?> boxed = boxed(parameterType);
        return boxed.isAssignableFrom(value.getClass());
    }

    private boolean isIntType(Class<?> type) {
        return boxed(type) == Integer.class;
    }

    private Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private Object findFieldValue(Object target, String preferredName) throws Exception {
        Field cached = viewerConnectionField;
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(target.getClass())) {
            return cached.get(target);
        }

        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(preferredName);
                field.setAccessible(true);
                viewerConnectionField = field;
                return field.get(target);
            } catch (NoSuchFieldException ignored) {}

            for (Field field : type.getDeclaredFields()) {
                if (field.getType().getName().contains("ServerGamePacketListener")) {
                    field.setAccessible(true);
                    viewerConnectionField = field;
                    return field.get(target);
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private byte angle(float value) {
        return (byte) (value * 256.0f / 360.0f);
    }

    private short fixedDelta(double next, double previous) {
        double delta = next * 4096.0 - previous * 4096.0;
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(delta)));
    }

    private String sanitizeName(String name, UUID uuid) {
        String base = name == null ? "Replay" : name.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank()) base = "Replay";
        String suffix = "_" + uuid.toString().substring(0, 4);
        int maxBase = Math.max(1, 16 - suffix.length());
        if (base.length() > maxBase) base = base.substring(0, maxBase);
        return base + suffix;
    }

    private String describe(Throwable throwable) {
        Throwable root = throwable;
        while (root instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            root = invocation.getCause();
        }
        while (root.getCause() != null && root.getCause() != root
                && !(root instanceof ReflectiveOperationException)) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private void warnOnce(Npc npc, boolean equipment, String message) {
        if (equipment) {
            if (npc.warnedEquipmentFailure) {
                plugin.debug(message);
                return;
            }
            npc.warnedEquipmentFailure = true;
        } else {
            if (npc.warnedStateFailure) {
                plugin.debug(message);
                return;
            }
            npc.warnedStateFailure = true;
        }
        plugin.getLogger().warning(message);
    }

    public static class Npc {
        private final UUID uuid;
        private final String name;
        private final Object nmsPlayer;
        private final int entityId;
        private final String skinKey;
        private Location lastLocation;
        private String lastEquipmentKey = "";
        private String lastStateKey = "";
        private int relativeMovesSinceTeleport;
        private boolean warnedEquipmentFailure;
        private boolean warnedStateFailure;

        private Npc(UUID uuid, String name, Object nmsPlayer, int entityId, Location lastLocation, String skinKey) {
            this.uuid = uuid;
            this.name = name;
            this.nmsPlayer = nmsPlayer;
            this.entityId = entityId;
            this.lastLocation = lastLocation;
            this.skinKey = skinKey;
        }
    }
}
