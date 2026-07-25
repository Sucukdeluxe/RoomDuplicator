package utils;

import gearth.extensions.ExtensionBase;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.services.packet_info.PacketInfo;
import gearth.services.packet_info.PacketInfoManager;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Executor {
    private static final Set<String> ROOM_PACKET_NAMES = new HashSet<>(Arrays.asList(
            "getguestroomresult", "roomentrytile", "floorheightmap", "items", "objects", "roomvisualizationsettings"));
    private static final String[] NAME_SUFFIXES = { "messagecomposer", "messageevent", "composer", "event" };

    private final ExtensionBase extension;
    private final List<AwaitingPacket> awaitingPackets = new ArrayList<>();
    private final Map<String, HPacket> roomPacketCache = new HashMap<>();
    private final Object lock = new Object();

    public Executor(ExtensionBase extension) {
        this.extension = extension;

        extension.intercept(HMessage.Direction.TOSERVER, this::onMessageToServer);
        extension.intercept(HMessage.Direction.TOCLIENT, this::onMessageToClient);
    }

    private PacketInfoManager packetInfoManager() {
        return extension.getPacketInfoManager();
    }

    public boolean isKnownName(HMessage.Direction direction, String name) {
        return packetInfoManager().getPacketInfoFromName(direction, name) != null;
    }

    public boolean sendToServer(String hashOrName, Object... objects) {
        if(!isKnownName(HMessage.Direction.TOSERVER, hashOrName)) {
            return false;
        }
        return extension.sendToServer(new HPacket(hashOrName, HMessage.Direction.TOSERVER, objects));
    }

    public String sendFirstKnown(String... names) {
        for(String name : names) {
            if(sendToServer(name)) {
                return name;
            }
        }
        return null;
    }

    public void sendToClient(String hashOrName, Object... objects) {
        extension.sendToClient(new HPacket(hashOrName, HMessage.Direction.TOCLIENT, objects));
    }

    private void onMessageToServer(HMessage hMessage) {
        PacketInfo info = packetInfoManager().getPacketInfoFromHeaderId(HMessage.Direction.TOSERVER, hMessage.getPacket().headerId());
        if(info == null) {
            return;
        }
        synchronized(lock) {
            awaitingPackets.stream()
                    .filter(packet -> packet.direction.equals(HMessage.Direction.TOSERVER)) // Filter TOSERVER packets
                    .filter(packet -> namesMatch(packet.headerName, info.getName()))        // Filter to packets with matching headernames
                    .forEach(packet -> {
                        if(packet.test(hMessage)) {
                            packet.setPacket(hMessage.getPacket());
                        }
                    });
        }
    }

    public static String normalizeName(String name) {
        if(name == null) return "";
        String normalized = name.toLowerCase(Locale.ROOT);
        for(String suffix : NAME_SUFFIXES) {
            if(normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }

    private static boolean namesMatch(String expected, String actual) {
        return normalizeName(expected).equals(normalizeName(actual));
    }

    public HPacket getCachedRoomPacket(String headerName) {
        synchronized(lock) {
            HPacket cached = roomPacketCache.get(normalizeName(headerName));
            if(cached == null) return null;
            HPacket copy = new HPacket(cached);
            copy.resetReadIndex();
            return copy;
        }
    }

    public int cachedRoomPacketCount() {
        synchronized(lock) {
            return roomPacketCache.size();
        }
    }

    private void cacheRoomPacket(String name, HPacket packet) {
        String normalized = normalizeName(name);
        if(!ROOM_PACKET_NAMES.contains(normalized)) return;

        HPacket copy = new HPacket(packet);
        copy.resetReadIndex();
        if(normalized.equals("getguestroomresult") && !readsEnteredRoomFlag(copy)) return;
        copy.resetReadIndex();

        synchronized(lock) {
            roomPacketCache.put(normalized, copy);
        }
    }

    private static boolean readsEnteredRoomFlag(HPacket packet) {
        try {
            return packet.readBoolean();
        } catch (Throwable t) {
            return true;
        }
    }

    private void onMessageToClient(HMessage hMessage) {
        PacketInfo info = packetInfoManager().getPacketInfoFromHeaderId(HMessage.Direction.TOCLIENT, hMessage.getPacket().headerId());
        if(info == null) {
            return;
        }
        cacheRoomPacket(info.getName(), hMessage.getPacket());
        synchronized(lock) {
            awaitingPackets.stream()
                    .filter(packet -> packet.direction.equals(HMessage.Direction.TOCLIENT)) // Filter TOCLIENT packets
                    .filter(packet -> namesMatch(packet.headerName, info.getName()))        // Filter to packets with matching headernames
                    .forEach(packet -> {
                        if (packet.test(hMessage)) {
                            packet.setPacket(hMessage.getPacket());
                        }
                    });
        }
    }

    public void register(AwaitingPacket... packets) {
        synchronized(lock) {
            for(AwaitingPacket packet : packets) {
                if(!awaitingPackets.contains(packet)) {
                    awaitingPackets.add(packet);
                }
            }
        }
    }

    public HPacket awaitPacket(AwaitingPacket... packets) {
        register(packets);

        while (true) {
            for (AwaitingPacket awaitingPacket : packets) {
                if (awaitingPacket.isReady()) {
                    synchronized (lock) {
                        awaitingPackets.removeAll(Arrays.asList(packets));
                    }
                    return awaitingPacket.getPacket();
                }
            }
            idle();
        }
    }

    private static void idle() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void clear() {
        synchronized(lock) {
            this.awaitingPackets.clear();
        }
    }

    public List<HPacket> awaitPacketList(AwaitingPacket... packets) {
        register(packets);

        while(true) {
            if(Arrays.stream(packets).allMatch(AwaitingPacket::isReady)) {
                synchronized (lock) {
                    awaitingPackets.removeAll(Arrays.asList(packets));
                }
                return Arrays.stream(packets).map(AwaitingPacket::getPacket).collect(Collectors.toList());
            }
            idle();
        }
    }

    public static class AwaitingPacket {
        public final String headerName;
        public final HMessage.Direction direction;
        private HPacket packet = null;
        private boolean received = false;
        private final List<Predicate<? super HPacket>> conditions = new ArrayList<>();
        private final long start;
        private long minWait = 0;

        public AwaitingPacket(String headerName, HMessage.Direction direction, int maxWaitingTimeMillis) {
            this.headerName = headerName;
            this.direction = direction;

            if(maxWaitingTimeMillis < 50) {
                maxWaitingTimeMillis = 50;
            }

            AwaitingPacket packet = this;
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    packet.received = true;
                }
            }, maxWaitingTimeMillis);

            this.start = System.currentTimeMillis();
        }

        public AwaitingPacket setMinWaitingTime(int millis) {
            this.minWait = millis;
            return  this;
        }

        @SafeVarargs
        public final AwaitingPacket addConditions(Predicate<? super HPacket>... conditions) {
            this.conditions.addAll(Arrays.asList(conditions));
            return this;
        }

        private void setPacket(HPacket packet) {
            this.packet = packet;
            received = true;
        }

        public HPacket getPacket() {
            if(packet != null) {
                this.packet.resetReadIndex();
            }
            return this.packet;
        }

        private boolean test(HMessage hMessage) {
            for(Predicate<? super HPacket> condition : conditions) {
                HPacket packet = hMessage.getPacket();
                packet.resetReadIndex();
                if(condition.test(packet)) {
                    return true;
                }
            }
            return conditions.isEmpty();
        }

        private boolean isReady() {
            return received && start + minWait < System.currentTimeMillis();
        }
    }
}

