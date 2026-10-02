import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
class VerifySignatures {
    public static void main(String[] args) throws Exception {
        Path client=Path.of(args[0]);
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(HexFormat.of().parseHex(Files.readString(client.resolve("Addon.key")).strip())));
        for(String rel:new String[]{"bin32/bin32.pak","Data/func_pet/func_pet.pak","Plugin/RelicCalc/RelicCalc.pak"}) {
            Path archive=client.resolve(rel);
            if(!Files.isRegularFile(archive) && args.length>1)archive=Path.of(args[1]).resolve(rel);
            Signature check=Signature.getInstance("SHA1withRSA");check.initVerify(key);check.update(Files.readAllBytes(archive));
            if(!check.verify(HexFormat.of().parseHex(Files.readString(client.resolve(rel+".sig")).strip())))throw new SecurityException(rel);
        }
        System.out.println("All three installed addon signatures verified.");
    }
}
