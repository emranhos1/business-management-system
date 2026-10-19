package com.eh.bms.config;

/**
 * The support team's hidden login. When a shop's admin forgets the password, the support team logs in with this
 * account (it can do everything an admin can) and sets a new password for the admin or any other user from the
 * Users page. The shop's users never see it: it is not in the users list and cannot be edited, switched off or
 * deleted from the app; its changes do show in the audit log under the name {@link #NAME}.
 * <p>
 * The password is known only to the support team. This repository is public, so only a BCrypt hash of it may be
 * written here, never the password itself: make the hash with {@code com.eh.bms.tools.SupportHashTool}, paste it
 * into {@link #PASSWORD_HASH}, and use a long random password (16+ characters), since anyone can try to guess a
 * password from a public hash. While the hash is blank the account is not created. Changing the hash in a release
 * changes the password in every shop at its next start.
 */
public final class SupportAccount {

    public static final String USERNAME = "emranhos1";
    public static final String NAME = "BMS Support";
    /** BCrypt hash of the support password (output of SupportHashTool). Blank = no support account yet. */
    public static final String PASSWORD_HASH = "$2a$12$o7EYn8yICTDzyNtkrQFAQew25ufZjsq.xTiGPPgyaRdfqYXBfazza";

    private SupportAccount() {
    }
}
