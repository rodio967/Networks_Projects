import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class NetworkCloneDetector {
    private static final int PORT = 8888;
    private static final int HEARTBEAT_INTERVAL_MS = 2000;
    private static final int TIMEOUT_MS = 7000;

    private final String groupAddress;
    private MulticastSocket socket;
    private InetAddress multicastGroup;
    private NetworkInterface networkInterface;
    private final Map<String, Long> aliveCopies = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();
    private volatile boolean running = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private Thread receiverThread;

    public NetworkCloneDetector(String groupAddress) {
        this.groupAddress = groupAddress;
    }

    public void start() {
        try {
            multicastGroup = InetAddress.getByName(groupAddress);

            socket = new MulticastSocket(PORT);
            networkInterface = findNetworkInterface();
            if (networkInterface == null) {
                System.err.println("Не найден подходящий сетевой интерфейс для группы: " + groupAddress);
                return;
            }

            try {
                socket.setNetworkInterface(networkInterface);
            } catch (SocketException se) {
                System.err.println("Не удалось установить сетевой интерфейс на сокете: " + se.getMessage());
            }
            socket.joinGroup(new InetSocketAddress(multicastGroup, PORT), networkInterface);

            System.out.println("Присоединились к multicast группе: " + groupAddress + " на интерфейсе: " + networkInterface.getDisplayName());
            System.out.println("InstanceId этой копии: " + instanceId);

            running = true;

            scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);


            receiverThread = new Thread(this::receiveMessages, "mcast-receiver");
            receiverThread.start();


            scheduler.scheduleAtFixedRate(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);

            System.out.println("Детектор запущен, Нажмите Enter для остановки");
            new Scanner(System.in).nextLine();

        } catch (IOException e) {
            System.err.println("Ошибка при запуске: " + e.getMessage());
        } finally {
            stop();
        }
    }

    private NetworkInterface findNetworkInterface() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                if (isCompatible(addr, multicastGroup)) {
                    return ni;
                }
            }
        }
        return null;
    }

    private boolean isCompatible(InetAddress interfaceAddr, InetAddress multicastAddr) {
        if (interfaceAddr instanceof Inet4Address && multicastAddr instanceof Inet4Address) return true;
        if (interfaceAddr instanceof Inet6Address && multicastAddr instanceof Inet6Address) return true;
        return false;
    }

    private void sendHeartbeat() {
        sendMessage((byte) 0, instanceId);
    }

    private void sendLeave() {
        sendMessage((byte) 1, instanceId);
    }


    private void sendMessage(byte type, String payload) {
        try {
            byte[] msgBytes = payload.getBytes();
            int length = msgBytes.length;

            if (length > 65535) {
                throw new IOException("Слишком длинное сообщение: " + length);
            }

            byte[] buf = new byte[1 + 2 + length];
            buf[0] = type;
            buf[1] = (byte) ((length >> 8) & 0xFF);
            buf[2] = (byte) (length & 0xFF);
            System.arraycopy(msgBytes, 0, buf, 3, length);

            DatagramPacket packet = new DatagramPacket(buf, buf.length, multicastGroup, PORT);
            socket.send(packet);

        } catch (IOException e) {
            if (running) System.err.println("Ошибка отправки: " + e.getMessage());
        }
    }

    private void receiveMessages() {
        byte[] buffer = new byte[1024];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                byte[] data = packet.getData();
                int packetLength = packet.getLength();

                if (packetLength < 3) {
                    System.err.println("Слишком короткое сообщение: " + packetLength + " байт");
                    continue;
                }

                byte type = data[0];
                int payloadLength = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);


                int expectedTotalLength = 3 + payloadLength;
                if (expectedTotalLength != packetLength) {
                    System.err.println("Несоответствие длины: ожидалось " + expectedTotalLength + " получили " + packetLength);
                    continue;
                }

                String payload = new String(data, 3, payloadLength).trim();

                if (isOwnPacket(packet)) {
                    continue;
                }

//                if (payload.equals(instanceId)) {
//                    continue;
//                }

                if (type == 0) {
                    handleHeartbeat(payload, packet.getAddress());
                } else if (type == 1) {
                    handleLeave(payload, packet.getAddress());
                } else {
                    System.err.println("Неизвестный тип сообщения: " + type);
                }

            } catch (IOException e) {
                if (running) System.err.println("Ошибка приема: " + e.getMessage());
                break;
            }
        }
    }

    private boolean isOwnPacket(DatagramPacket packet) {
        try {
            InetAddress packetAddress = packet.getAddress();
            int packetPort = packet.getPort();

            if (packetAddress.isLoopbackAddress()) {
                return true;
            }

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp()) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress localAddr = addresses.nextElement();

                    if (localAddr.equals(packetAddress)) {
                        if (packetPort == PORT) {
                            return true;
                        }
                    }
                }
            }

            return false;

        } catch (SocketException e) {
            return false;
        }
    }

    private void handleHeartbeat(String remoteId, InetAddress sourceAddress) {
        String key = sourceAddress.getHostAddress() + ":" + PORT;
        long now = System.currentTimeMillis();
        Long prev = aliveCopies.put(key, now);
        if (prev == null) {
            System.out.println("Обнаружена новая копия: " + key + " (id=" + remoteId + ")");
            printAliveCopies();
        }
    }

    private void handleLeave(String remoteId, InetAddress sourceAddress) {
        String key = sourceAddress.getHostAddress() + ":" + PORT;
        if (aliveCopies.remove(key) != null) {
            System.out.println("Копия вышла: " + key + " (id=" + remoteId + ")");
            printAliveCopies();
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<String, Long>> it = aliveCopies.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > TIMEOUT_MS) {
                it.remove();
                System.out.println("Копия пропала: " + e.getKey());
                printAliveCopies();
            }
        }
    }

    private void printAliveCopies() {
        if (aliveCopies.isEmpty()) {
            System.out.println("Активные копии: нет");
        } else {
            System.out.println("Активные копии: " + String.join(", ", aliveCopies.keySet()));
        }
        System.out.println("---");
    }

    public void stop() {
        running = false;
        sendLeave();

        scheduler.shutdownNow();
        if (socket != null) {
            try {
                if (multicastGroup != null && networkInterface != null) {
                    socket.leaveGroup(new InetSocketAddress(multicastGroup, PORT), networkInterface);
                }
            } catch (IOException e) {
                //
            }
            socket.close();
        }
        try {
            if (receiverThread != null) receiverThread.join(1000);
        } catch (InterruptedException ignored) {}
        System.out.println("Детектор остановлен");
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Использование: java NetworkCloneDetector <multicast_address>");
            System.out.println("Примеры:");
            System.out.println("java NetworkCloneDetector 239.255.255.250 (IPv4)");
            System.out.println("java NetworkCloneDetector ff02::1 (IPv6)");
            System.exit(1);
        }
        new NetworkCloneDetector(args[0]).start();
    }
}
