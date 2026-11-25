package com.ultron.assistant;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                String digits = toWhatsAppDigits(number);
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
    private static String toWhatsAppDigits(String rawNumber) {
        if (rawNumber == null || rawNumber.trim().isEmpty()) return null;

        String region = Locale.getDefault().getCountry();
        String e164 = null;
        if (region != null && !region.trim().isEmpty()) {
            try {
                e164 = PhoneNumberUtils.formatNumberToE164(rawNumber, region);
            } catch (RuntimeException ignored) {
                e164 = null;
            }
        }

        String source = e164 != null ? e164 : rawNumber;
        String digits = source.replaceAll("[^0-9]", "");

        if (source.startsWith("00") && digits.startsWith("00")) {
            digits = digits.substring(2);
        }

        // Refuse malformed numbers rather than guessing a country calling code.
        if (digits.length() < 8 || digits.length() > 15) return null;
        return digits;
    }
}
