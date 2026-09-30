import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Match this legacy client's RSA/SHA1 format using an ephemeral local key. */
class SignClientPackages {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final List<String> PACKAGES = List.of(
        "bin32/bin32.pak", "Data/func_pet/func_pet.pak", "Plugin/RelicCalc/RelicCalc.pak");

    private static byte[] readHex(Path path) throws Exception {
        return HEX.parseHex(Files.readString(path).strip());
    }

    private static void verify(PublicKey key, byte[] bytes, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(key);
        verifier.update(bytes);
        if (!verifier.verify(signature)) throw new SecurityException("Package signature mismatch");
    }

    private static Path stockSignatures(Path client) throws Exception {
        List<Path> candidates = new ArrayList<>();
        candidates.add(client);
        Path backups = client.resolve("TransmogMenu-backups");
        if (Files.isDirectory(backups)) try (var directories = Files.list(backups)) {
            directories.filter(Files::isDirectory).sorted().forEach(candidates::add);
        }
        for (Path candidate : candidates) {
            Path key = candidate.resolve("Pub.key");
            if (!Files.isRegularFile(key)) continue;
            String hash = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(key)));
            if (!hash.equals("11C64FF8E5DDE91B281B57AC2B372DC0C8F0DF0DF9E18C936335D10E63D85136")) continue;
            PublicKey originalKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(readHex(key)));
            try {
                for (String path : PACKAGES) if (!path.startsWith("Plugin/"))
                    verify(originalKey, Files.readAllBytes(client.resolve(path)), readHex(candidate.resolve(path + ".sig")));
                return candidate;
            } catch (SecurityException | NoSuchFileException ignored) {
                // A repaired client retains Pub.key but its archives use Addon.key.
            }
        }
        throw new IllegalStateException("Original model key is missing; preserve it before installing addons");
    }

    public static void main(String[] args) throws Exception {
        Path client = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path stock = stockSignatures(client);
        PublicKey originalKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(readHex(stock.resolve("Pub.key"))));
        Path addonKeyPath = Files.isRegularFile(client.resolve("Addon.key")) ? client.resolve("Addon.key") : client.resolve("Pub.key");
        PublicKey addonKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(readHex(addonKeyPath)));
        Set<String> actual = new HashSet<>();
        try (var paths = Files.walk(client)) {
            paths.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".pak.sig"))
                .filter(p -> !p.toString().contains("TransmogMenu-backups"))
                .forEach(p -> actual.add(client.relativize(p).toString().replace('\\', '/')));
        }
        Set<String> expected = new HashSet<>();
        for (String path : PACKAGES) expected.add(path + ".sig");
        if (!actual.equals(expected)) throw new IllegalStateException("Unexpected signed packages: " + actual);
        for (String path : PACKAGES) {
            byte[] bytes = Files.readAllBytes(client.resolve(path));
            byte[] signature = readHex(client.resolve(path + ".sig"));
            try { verify(addonKey, bytes, signature); }
            catch (SecurityException mismatch) {
                if (path.startsWith("Plugin/")) throw mismatch;
                verify(originalKey, bytes, signature);
            }
        }
        // Match the legacy 128-byte signature size; the private key is never saved.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair localKey = generator.generateKeyPair();
        Files.copy(stock.resolve("Pub.key"), output.resolve("Pub.key"));
        Files.writeString(output.resolve("Addon.key"), HEX.formatHex(localKey.getPublic().getEncoded()), StandardCharsets.US_ASCII);
        for (String path : PACKAGES) {
            Path stagedPackage = output.resolve(path);
            byte[] bytes = Files.readAllBytes(Files.exists(stagedPackage) ? stagedPackage : client.resolve(path));
            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(localKey.getPrivate());
            signer.update(bytes);
            byte[] signature = signer.sign();
            verify(localKey.getPublic(), bytes, signature);
            Path destination = output.resolve(path + ".sig");
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, HEX.formatHex(signature), StandardCharsets.US_ASCII);
        }
        System.out.println("Verified original stock packages and installed signatures; signed all three archives with Addon.key; original model key preserved.");
    }
}
