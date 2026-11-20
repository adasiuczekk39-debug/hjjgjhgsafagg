package pl.chestbot;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Stabilny identyfikator komputera (hash) - do przypisania klucza licencji do jednego PC. */
public final class Hwid {
    private static volatile String cached;

    private Hwid() {}

    public static String get() {
        String c = cached;
        if (c != null) return c;
        synchronized (Hwid.class) {
            if (cached == null) cached = compute();
            return cached;
        }
    }

    private static String compute() {
        String raw = "chestbot|" + machineId() + "|" + System.getProperty("os.name") + "|" + System.getProperty("os.arch");
        return sha256(raw).substring(0, 32).toUpperCase(Locale.ROOT);
    }

    private static String machineId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String out = run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
                for (String line : out.split("\\R")) {
                    if (line.contains("MachineGuid")) {
                        String[] t = line.trim().split("\\s+");
                        if (t.length > 0 && t[t.length - 1].length() > 8) return t[t.length - 1];
                    }
                }
            } else if (os.contains("mac")) {
                String out = run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
                int i = out.indexOf("IOPlatformUUID");
                if (i >= 0) {
                    int a = out.indexOf('"', out.indexOf('=', i));
                    int b = out.indexOf('"', a + 1);
                    if (a > 0 && b > a) return out.substring(a + 1, b);
                }
            } else {
                for (String f : new String[]{"/etc/machine-id", "/var/lib/dbus/machine-id"}) {
                    Path p = Path.of(f);
                    if (Files.exists(p)) {
                        String s = Files.readString(p).trim();
                        if (!s.isEmpty()) return s;
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback();
    }

    /** Gdy nie da sie odczytac ID systemu: adresy MAC kart sieciowych + nazwa uzytkownika. */
    private static String fallback() {
        List<String> macs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface n = e.nextElement();
                byte[] m = n.getHardwareAddress();
                if (m == null || m.length == 0 || n.isLoopback()) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : m) sb.append(String.format("%02x", b));
                macs.add(sb.toString());
            }
        } catch (Exception ignored) {}
        Collections.sort(macs);
        return String.join(",", macs) + "|" + System.getProperty("user.name") + "|" + System.getenv("COMPUTERNAME");
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(5, TimeUnit.SECONDS);
        return out;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
