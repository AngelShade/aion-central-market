package com.aionemu.gameserver.services.player;

import java.nio.charset.StandardCharsets;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.network.aion.AionConnection;
import com.aionemu.gameserver.network.aion.serverpackets.SM_SECURITY_TOKEN;

/** Refresh the client's web session when re-entering from the server selection screen. */
public final class WebSessionService {
    private WebSessionService() {}
    static String token(Account account) {
        synchronized (account) {
            if (account.getSecurityToken().isEmpty()) SecurityTokenService.generateToken(account);
            return account.getSecurityToken();
        }
    }
    public static void send(AionConnection connection) {
        Account account = connection.getAccount();
        if (account != null) connection.sendPacket(new SM_SECURITY_TOKEN(token(account).getBytes(StandardCharsets.US_ASCII)));
    }
}
