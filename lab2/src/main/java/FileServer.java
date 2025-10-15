import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class FileServer {
    private static boolean running = true;
    private static ServerSocket serverSocket;
    private static final ExecutorService clientPool = Executors.newCachedThreadPool();
    private static final int BUFFER_SIZE = 1024;
    private static final int FILENAME_MAX_BYTES = 4096;
    private static final Path UPLOADS_DIR = Paths.get("uploads");

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java FileServer <port>");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);

        try {
            if (!Files.exists(UPLOADS_DIR)) {
                Files.createDirectories(UPLOADS_DIR);
            } else if (!Files.isDirectory(UPLOADS_DIR)) {
                System.err.println("uploads exists and is not a directory");
                System.exit(1);
            }
        } catch (IOException e) {
            System.err.println("Cannot create uploads directory: " + e.getMessage());
            System.exit(1);
        }

//        ExecutorService clientPool = Executors.newCachedThreadPool();

        Thread stopThread = new Thread(() -> {
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    if (scanner.nextLine().equalsIgnoreCase("exit")) {
                        stop();
                        break;
                    }
                }
            }
        });
        stopThread.start();

        try {
            serverSocket = new ServerSocket(port);
            System.out.println("FileServer listening on port " + port);
            while (running) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientPool.submit(new ClientHandler(clientSocket));
            }
        } catch (SocketException e) {
            if (running) {
                System.err.println("Socket error: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("Server socket error: " + e.getMessage());
        } finally {
            try {
                stopThread.join();
            } catch (InterruptedException e) {}
        }
    }

    public static void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server socket: " + e.getMessage());
        }
        clientPool.shutdown();
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientId;
        private final AtomicLong totalReceived = new AtomicLong(0);
        private final AtomicLong intervalReceived = new AtomicLong(0);

        ClientHandler(Socket socket) {
            this.socket = socket;
            this.clientId = socket.getRemoteSocketAddress().toString() + "@" + System.currentTimeMillis();
        }

        @Override
        public void run() {
            System.out.println("Accepted connection from " + socket.getRemoteSocketAddress());


            long startTimeNano = System.nanoTime();
            Object printLock = new Object();
            long[] lastPrintNano = { startTimeNano };

            ScheduledExecutorService scheduler = createSpeedPrinter(totalReceived, intervalReceived, startTimeNano, printLock, lastPrintNano);

            boolean success = false;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

                String filename = readFilename(in);
                long declaredSize = readFileSize(in);
                Path target = prepareTargetPath(filename);

                receiveFile(in, target, declaredSize, totalReceived, intervalReceived);

                printFinalSpeedIfNeeded(totalReceived, intervalReceived, startTimeNano, printLock, lastPrintNano);

                long receivedBytes = totalReceived.get();
                success = (receivedBytes == declaredSize);

                sendResult(out, success);

                logTransferResult(success, target, declaredSize, receivedBytes);

            } catch (Exception e) {
                System.err.println("Error while handling client " + clientId + ": " + e.getMessage());
            } finally {
                cleanup(scheduler);
            }
        }


        private ScheduledExecutorService createSpeedPrinter(AtomicLong total, AtomicLong interval, long startTimeNano, Object printLock, long[] lastPrintNano) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

            Runnable printTask = () -> {
                long now = System.nanoTime();
                long last = lastPrintNano[0];
                long intervalNs = now - last;
                double intervalSec = intervalNs / 1_000_000_000.0;
                long intervalBytes = interval.getAndSet(0);
                long totalBytes = total.get();

                double instantRate = intervalSec > 0 ? intervalBytes / intervalSec : 0.0;
                double avgRate = ((now - startTimeNano) / 1_000_000_000.0) > 0
                        ? totalBytes / ((now - startTimeNano) / 1_000_000_000.0)
                        : 0.0;

                synchronized (printLock) {
                    System.out.printf("[Client %s] Instant: %.2f B/s, Average: %.2f B/s (received %d bytes)%n", clientId, instantRate, avgRate, totalBytes);
                    lastPrintNano[0] = now;
                }
            };

            scheduler.scheduleAtFixedRate(printTask, 1, 1, TimeUnit.SECONDS);
            return scheduler;
        }

        private String readFilename(DataInputStream in) throws IOException {
            int filenameLen = in.readInt();
            if (filenameLen <= 0 || filenameLen > FILENAME_MAX_BYTES) {
                throw new IOException("Invalid filename length: " + filenameLen);
            }

            byte[] filenameBytes = new byte[filenameLen];
            in.readFully(filenameBytes);
            String filename = new String(filenameBytes, "UTF-8");

            String safeName = Paths.get(filename).getFileName().toString();
            if (safeName.isEmpty() || safeName.contains("..") || safeName.contains(File.separator) || safeName.contains("/") || safeName.contains("\\")) {
                safeName = "received_" + System.currentTimeMillis();
            }
            return safeName;
        }

        private long readFileSize(DataInputStream in) throws IOException {
            long declaredSize = in.readLong();
            if (declaredSize < 0 || declaredSize > (1L << 40)) {
                throw new IOException("Declared file size out of range: " + declaredSize);
            }
            return declaredSize;
        }

        private Path prepareTargetPath(String safeName) throws IOException {
            Path target = UPLOADS_DIR.resolve(safeName);
            return makeUniqueFilePath(target);
        }

        private void receiveFile(DataInputStream in, Path target, long declaredSize, AtomicLong totalReceived, AtomicLong intervalReceived) throws IOException {
            try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(target.toFile()))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long remaining = declaredSize;

                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = in.read(buffer, 0, toRead);
                    if (read == -1) throw new IOException("Unexpected end of stream while reading file data");

                    fileOut.write(buffer, 0, read);
                    totalReceived.addAndGet(read);
                    intervalReceived.addAndGet(read);
                    remaining -= read;
                }
                fileOut.flush();
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
        }

        private void printFinalSpeedIfNeeded(AtomicLong totalReceived, AtomicLong intervalReceived, long startTimeNano, Object printLock, long[] lastPrintNano) {
            long endTimeNano = System.nanoTime();
            double totalSec = (endTimeNano - startTimeNano) / 1_000_000_000.0;
            if (totalSec < 3.0) {
                synchronized (printLock) {
                    long intervalBytes = intervalReceived.getAndSet(0);
                    long totalBytes = totalReceived.get();
                    double instantRate = totalSec > 0 ? intervalBytes / totalSec : 0.0;
                    double avgRate = totalSec > 0 ? totalBytes / totalSec : 0.0;
                    System.out.printf("[Client %s] Instant: %.2f B/s, Average: %.2f B/s (received %d bytes)%n", clientId, instantRate, avgRate, totalBytes);
                    lastPrintNano[0] = endTimeNano;
                }
            }
        }

        private void sendResult(DataOutputStream out, boolean success) throws IOException {
            out.writeBoolean(success);
            out.flush();
        }

        private void logTransferResult(boolean success, Path target, long declaredSize, long receivedBytes) {
            if (success) {
                System.out.printf("Transfer complete for client %s. Saved as %s (%d bytes).%n",
                        clientId, target, receivedBytes);
            } else {
                System.out.printf("Transfer failed for client %s. Declared=%d, Received=%d%n",
                        clientId, declaredSize, receivedBytes);
            }
        }

        private void cleanup(ScheduledExecutorService scheduler) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}

            try {
                socket.close();
            } catch (IOException ignored) {}

            System.out.println("Connection with " + clientId + " closed");
        }

        private Path makeUniqueFilePath(Path base) {
            Path candidate = base;
            int index = 1;
            String filename = base.getFileName().toString();
            String name, ext;
            int dot = filename.lastIndexOf('.');
            if (dot > 0 && dot < filename.length() - 1) {
                name = filename.substring(0, dot);
                ext = filename.substring(dot);
            } else {
                name = filename;
                ext = "";
            }

            while (Files.exists(candidate)) {
                String newName = String.format("%s(%d)%s", name, index++, ext);
                candidate = base.getParent().resolve(newName);
            }
            return candidate;
        }
    }

}
