package com.eh.bms.tools;

import org.mindrot.jbcrypt.BCrypt;

import java.io.Console;
import java.util.Scanner;

/**
 * For the support team, not the shop: turns the support password into the BCrypt hash that goes into
 * {@code SupportAccount.PASSWORD_HASH}. Run it (e.g. from the IDE) and type the password; copy the line it
 * prints. The password is never saved anywhere.
 */
public final class SupportHashTool {

    /** More work per try than the app's own passwords, because this hash is public. */
    private static final int ROUNDS = 12;

    private SupportHashTool() {
    }

    public static void main(String[] args) {
        String password;
        Console console = System.console();
        if (console != null) {
            char[] typed = console.readPassword("Support password: ");
            password = typed == null ? "" : new String(typed);
        } else {
            System.out.print("Support password: ");
            password = new Scanner(System.in).nextLine();
        }
        if (password.length() < 16) {
            System.out.println("Too short: use at least 16 characters (the hash is public).");
            return;
        }
        System.out.println("PASSWORD_HASH = \"" + BCrypt.hashpw(password, BCrypt.gensalt(ROUNDS)) + "\";");
    }
}
