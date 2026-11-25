package com.ultron.assistant;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up a requested contact entirely on-device.
 *
 * Contact names and phone numbers are never written to ULTRON memory or sent
 * to Gemini. The caller receives only the matching local contact candidates.
 */
public final class WhatsAppContactHelper {

    private static final int MAX_RESULTS = 20;

    public static final class Contact {
        public final String displayName;
        public final String rawNumber;
        public final String whatsappDigits;

        Contact(String displayName, String rawNumber, String whatsappDigits) {
            this.displayName = displayName;
            this.rawNumber = rawNumber;
            this.whatsappDigits = whatsappDigits;
        }

        public String maskedNumber() {
            String digits = whatsappDigits;
            if (digits == null || digits.length() < 4) return "number unavailable";
            return "•••• " + digits.substring(digits.length() - 4);
        }

        public String getKey() {
            return displayName + "|" + whatsappDigits;
        }
    }

    private static final Pattern[] CONTACT_MESSAGE_PATTERNS = {
            Pattern.compile(
                    "^(?:please\\s+)?(?:message|text)\\s+(?:to\\s+)?(.+?)\\s+(?:on|via|in)\\s+whatsapp\\s+(?:saying|that says|:|,)\\s*(.+)$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile(
                    "^(?:please\\s+)?(?:send|draft|write|compose|prepare)\\s+(?:a\\s+)?whatsapp\\s+message\\s+to\\s+(.+?)\\s+(?:saying|that says|:|,)\\s*(.+)$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
    };

    public static final class DraftRequest {
        public final String contactName;
        public final String message;

        DraftRequest(String contactName, String message) {
            this.contactName = contactName;
            this.message = message;
        }
    }

    /** Recognizes a direct, explicit "message [person] on WhatsApp saying ..." request. */
    public static DraftRequest parseDraftCommand(String rawCommand) {
        if (rawCommand == null) return null;
        String command = rawCommand.trim().replaceFirst(
                "(?i)^[\\s,.:;!?]*(?:(?:hey|okay|ok)\\s+)?ultron\\b[,!:.]?\\s*",
                "").trim();

        for (Pattern pattern : CONTACT_MESSAGE_PATTERNS) {
            Matcher matcher = pattern.matcher(command);
            if (matcher.matches()) {
                String name = matcher.group(1).trim();
                String message = matcher.group(2).trim();
                if (name.isEmpty() || name.length() > 80
                        || message.isEmpty() || message.length() > 1000) {
                    return null;
                }
                return new DraftRequest(name, message);
            }
        }
        return null;
    }

    private WhatsAppContactHelper() {}

    /**
     * Searches Android's Contacts provider locally. READ_CONTACTS must already
     * have been granted by the user before this method is called.
     */
    public static List<Contact> findContacts(Context context, String requestedName) {
        List<Contact> results = new ArrayList<>();
        if (context == null || requestedName == null) return results;

        String query = requestedName.trim();
        if (query.isEmpty() || query.length() > 80) return results;

        Uri filterUri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(query));

        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        };

        Map<String, Contact> unique = new LinkedHashMap<>();
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor = resolver.query(
                filterUri,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {

            if (cursor == null) return results;

            int nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
            int idIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID);

            if (nameIndex < 0 || numberIndex < 0 || idIndex < 0) return results;

            while (cursor.moveToNext() && unique.size() < MAX_RESULTS) {
                String name = cursor.getString(nameIndex);
                String number = cursor.getString(numberIndex);
                String contactId = cursor.getString(idIndex);

                if (name == null || number == null) continue;
                String digits = toWhatsAppDigits(context, number);
                if (digits == null) continue;

                Contact candidate = new Contact(name.trim(), number.trim(), digits);
                unique.put(contactId + "|" + digits, candidate);
            }
        } catch (SecurityException ignored) {
            // The caller handles missing or revoked READ_CONTACTS permission.
            return results;
        } catch (RuntimeException ignored) {
            return results;
        }

        results.addAll(unique.values());
        return results;
    }

    /**
     * WhatsApp click-to-chat requires a country-coded number. Prefer Android's
     * E.164 formatter for the device's configured region; never invent a code.
     */
    private static String toWhatsAppDigits(Context context, String rawNumber) {
        if (rawNumber == null || rawNumber.trim().isEmpty()) return null;

        // Prefer the actual mobile network region, not the device's UI language.
        // getNetworkCountryIso does not expose the subscriber's phone number.
        String region = null;
        try {
            if (context.getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)) {
                TelephonyManager telephony = (TelephonyManager)
                        context.getSystemService(Context.TELEPHONY_SERVICE);
                if (telephony != null) {
                    String networkRegion = telephony.getNetworkCountryIso();
                    if (networkRegion != null && !networkRegion.trim().isEmpty()) {
                        region = networkRegion.toUpperCase(Locale.ROOT);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            region = null;
        }

        return normalizeInternationalNumber(rawNumber, region);
    }

    private static String normalizeInternationalNumber(String rawNumber, String region) {
        String e164 = null;
        if (region != null && !region.trim().isEmpty()) {
            try {
                e164 = PhoneNumberUtils.formatNumberToE164(rawNumber, region);
            } catch (RuntimeException ignored) {
                e164 = null;
            }
        }

        String source = e164 != null ? e164 : rawNumber.trim();
        // Without a known network region, only accept numbers already written
        // with an explicit international prefix. Never guess a calling code.
        if (e164 == null && !source.startsWith("+") && !source.startsWith("00")) {
            return null;
        }

        String digits = source.replaceAll("[^0-9]", "");
        if (source.startsWith("00") && digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.length() < 8 || digits.length() > 15) return null;
        return digits;
    }
}
