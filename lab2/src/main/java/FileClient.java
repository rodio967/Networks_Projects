import java.io.*;
import java.net.*;
import java.nio.file.*;

public class FileClient {
    private static final int BUFFER_SIZE = 64 * 1024;

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Error args");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        Path filePath = Paths.get(args[2]);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            System.err.println("File not found or not a regular file: " + filePath);
            System.exit(1);
        }

        try {
            long fileSize = Files.size(filePath);
            if (fileSize > (1L << 40)) {
                System.err.println("File too large (max 1 TB): " + fileSize);
                System.exit(1);
            }

            String filename = filePath.getFileName().toString();
            byte[] filenameBytes = filename.getBytes("UTF-8");
            if (filenameBytes.length > 4096) {
                System.err.println("Filename in UTF-8 exceeds 4096 bytes");
                System.exit(1);
            }

            try (Socket socket = new Socket(host, port)) {
                socket.setTcpNoDelay(true);
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                     DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                     InputStream fileIn = new BufferedInputStream(new FileInputStream(filePath.toFile()))) {

                    out.writeInt(filenameBytes.length);
                    out.write(filenameBytes);


                    out.writeLong(fileSize);
                    out.flush();


                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = fileIn.read(buffer, 0, BUFFER_SIZE)) != -1)  {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
//                    long remaining = fileSize;
//                    while (remaining > 0) {
//                        int toRead = (int) Math.min(buffer.length, remaining);
//                        int read = fileIn.read(buffer, 0, toRead);
//                        if (read == -1) break;
//                        out.write(buffer, 0, read);
//                        remaining -= read;
//                    }
//                    out.flush();


                    boolean success;
                    try {
                        success = in.readBoolean();
                    } catch (EOFException eof) {
                        throw new IOException("Server closed connection before sending result");
                    }

                    if (success) {
                        System.out.println("File transfer successful");
                    } else {
                        System.out.println("File transfer failed server reported mismatch");
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(2);
        }
    }
}
