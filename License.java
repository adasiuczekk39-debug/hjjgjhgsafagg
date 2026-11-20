package pl.chestbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

/**
 * Licencje: klucz jest sprawdzany na serwerze, ktory przypisuje go do jednego komputera.
 * Odpowiedz serwera jest podpisana (Ed25519) - bez prawdziwego podpisu mod nie uzna licencji.
 */
public final class License {
    // ============ UZUPELNIJ PO WDROZENIU SERWERA (panel /admin -> "Dane do wklejenia") ============
    private static final String SERVER_URL = "https://TWOJ-WORKER.workers.dev";
    private static final String PUBLIC_KEY_B64 = "WKLEJ_TUTAJ_KLUCZ_PUBLICZNY";
    // ================================================================================================

    private static final long GRACE_NANOS = 15L * 60 * 1_000_000_000L;   // ile mozna dzialac bez polaczenia z serwerem
    private static final int HEARTBEAT_TICKS = 20 * 60 * 5;               // sprawdzanie co 5 minut
    private static final Set<String> DEFINITIVE = Set.of("invalid_key", "revoked", "expired", "hwid_mismatch", "invalid");

    private static volatile boolean valid = false;
    private static volatile long expiresMs = 0;        // czas serwera, 0 = bezterminowa
    private static volatile long serverOffsetMs = 0;    // roznica: czas serwera - czas lokalny
    private static volatile long lastOkNano = 0;
    private static volatile String message = "Brak licencji";
    private static volatile boolean checking = false;
    private static volatile String key = "";
    private static int hb = 0;

    private static PublicKey pub;
    private static final SecureRandom RNG = new SecureRandom();

    private License() {}

    // ------------------------------------------------------------------ API

    public static void init() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("key")) key = o.get("key").getAsString().trim().toUpperCase();
            }
        } catch (Exception ignored) {}
        hb = HEARTBEAT_TICKS - 40;      // pierwsze sprawdzenie zaraz po starcie
        if (!configured()) message = "Serwer licencji nie jest skonfigurowany";
    }

    /** Czy licencja jest aktywna (sprawdzane w wielu miejscach moda). */
    public static boolean valid() {
        if (!valid) return false;
        long serverNow = System.currentTimeMillis() + serverOffsetMs;
        if (expiresMs > 0 && serverNow >= expiresMs) { invalidate("Licencja wygasła"); return false; }
        if (System.nanoTime() - lastOkNano > GRACE_NANOS) { invalidate("Brak połączenia z serwerem licencji"); return false; }
        return true;
    }

    public static String message() { return message; }
    public static boolean isChecking() { return checking; }
    public static boolean configured() {
        return !SERVER_URL.contains("TWOJ-WORKER") && !PUBLIC_KEY_B64.startsWith("WKLEJ");
    }

    public static String statusLine() {
        if (!valid()) return "Brak licencji";
        if (expiresMs <= 0) return "Licencja bezterminowa";
        return "Licencja do " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(expiresMs));
    }

    /** Aktywacja klucza wpisanego przez uzytkownika (asynchronicznie). */
    public static void activate(String typed) {
        String k = typed == null ? "" : typed.trim().toUpperCase();
        if (k.isEmpty()) { message = "Wpisz klucz licencji"; return; }
        if (!configured()) { message = "Serwer licencji nie jest skonfigurowany"; return; }
        verifyAsync(k, true);
    }

    /** Wywolywane co tick klienta - okresowe sprawdzanie licencji (cofniecie dziala w kilka minut). */
    public static void tick(MinecraftClient mc) {
        if (!configured() || key.isEmpty()) return;
        if (++hb >= HEARTBEAT_TICKS) {
            hb = 0;
            verifyAsync(key, false);
        }
    }

    // ------------------------------------------------------------------ WEWNETRZNE

    private static void verifyAsync(String k, boolean persist) {
        if (checking) return;
        checking = true;
        if (persist) message = "Sprawdzam klucz...";
        Thread t = new Thread(() -> {
            try { doVerify(k, persist); } finally { checking = false; }
        }, "chestbot-license");
        t.setDaemon(true);
        t.start();
    }

    private static void doVerify(String k, boolean persist) {
        String nonce = randomHex(16);
        String hwid = Hwid.get();
        JsonObject req = new JsonObject();
        req.addProperty("key", k);
        req.addProperty("hwid", hwid);
        req.addProperty("nonce", nonce);

        int code;
        String text;
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(SERVER_URL + "/api/verify").toURL().openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "ChestBot/1.0");
            try (OutputStream os = c.getOutputStream()) {
                os.write(new Gson().toJson(req).getBytes(StandardCharsets.UTF_8));
            }
            code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            text = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            networkProblem();
            return;
        }

        JsonObject o;
        try {
            o = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            networkProblem();               // np. strona bledu zamiast JSON
            return;
        }

        if (code == 200 && o.has("payload") && o.has("sig")) {
            try {
                String payload = o.get("payload").getAsString();
                if (!verifySignature(payload, o.get("sig").getAsString())) {
                    invalidate("Nieprawidłowa odpowiedź serwera");
                    return;
                }
                JsonObject p = JsonParser.parseString(payload).getAsJsonObject();
                if (!k.equals(p.get("key").getAsString()) || !hwid.equals(p.get("hwid").getAsString())
                        || !nonce.equals(p.get("nonce").getAsString())) {
                    invalidate("Nieprawidłowa odpowiedź serwera");
                    return;
                }
                long exp = p.get("exp").getAsLong();
                long ts = p.get("ts").getAsLong();
                if (exp > 0 && ts >= exp) { invalidate("Licencja wygasła"); return; }

                expiresMs = exp;
                serverOffsetMs = ts - System.currentTimeMillis();
                lastOkNano = System.nanoTime();
                valid = true;
                message = "Licencja aktywna";
                if (persist || !k.equals(key)) {
                    key = k;
                    saveKey(k);
                }
            } catch (Exception e) {
                invalidate("Błąd weryfikacji licencji");
            }
            return;
        }

        String err = o.has("error") ? o.get("error").getAsString() : "";
        if (DEFINITIVE.contains(err)) {
            if (persist) { if (!valid) message = describe(err); }   // zly klucz wpisany recznie
            else invalidate(describe(err));                          // sprawdzenie zapisanego klucza: cofniecie/wygasniecie
        } else {
            networkProblem();
        }
    }

    private static void networkProblem() {
        // brak sieci / blad serwera - nie odbieramy licencji od razu (okres karencji)
        if (valid) message = "Brak połączenia z serwerem licencji (tryb awaryjny)";
        else message = "Nie można połączyć z serwerem licencji";
    }

    private static void invalidate(String msg) {
        valid = false;
        message = msg;
    }

    private static String describe(String err) {
        return switch (err) {
            case "invalid_key" -> "Nieprawidłowy klucz";
            case "revoked" -> "Licencja została cofnięta";
            case "expired" -> "Licencja wygasła";
            case "hwid_mismatch" -> "Klucz jest przypisany do innego komputera";
            default -> "Nieprawidłowe dane";
        };
    }

    private static boolean verifySignature(String payload, String sigB64) throws Exception {
        if (pub == null) {
            byte[] der = Base64.getDecoder().decode(PUBLIC_KEY_B64.trim());
            pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        }
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(pub);
        s.update(payload.getBytes(StandardCharsets.UTF_8));
        return s.verify(Base64.getDecoder().decode(sigB64));
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("chestbot-license.json");
    }

    private static void saveKey(String k) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("key", k);
            Files.writeString(file(), new Gson().toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
