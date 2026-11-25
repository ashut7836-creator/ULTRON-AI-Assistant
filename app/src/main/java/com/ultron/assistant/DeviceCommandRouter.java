package com.ultron.assistant;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles a small allowlist of permission-conscious device actions.
 *
 * Search/open actions use Android ACTION_VIEW links. WhatsApp messages are
 * opened as drafts only; the user chooses the recipient and taps Send.
 */
public final class DeviceCommandRouter {

    private static final Pattern OPEN_AND_SEARCH = Pattern.compile(
            "^(?:please\\s+)?(?:open|launch|start)\\s+(youtube|instagram)\\s+(?:and\\s+)?(?:search(?:\\s+for)?|find)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SEARCH_ON_APP = Pattern.compile(
            "^(?:please\\s+)?(?:search|find)\\s+(.+?)\\s+(?:on|in)\\s+(youtube|instagram)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OPEN_APP = Pattern.compile(
            "^(?:please\\s+)?(?:open|launch|start)\\s+(youtube|instagram|whatsapp)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WHATSAPP_DRAFT = Pattern.compile(
            "^(?:please\\s+)?(?:send|write|draft|compose)\\s+(?:a\\s+)?(?:whatsapp\\s+)?message(?:\\s+to\\s+[^,]+)?\\s+(?:saying|that says|:|,)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern WHATSAPP_DRAFT_TO_PERSON = Pattern.compile(
            "^(?:please\\s+)?(?:message|text)\\s+.+?\\s+(?:on|via|in)\\s+whatsapp\\s+(?:saying|that says)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern WHATSAPP_TELL_PERSON = Pattern.compile(
            "^(?:please\\s+)?tell\\s+.+?\\s+(?:on|via|in)\\s+whatsapp\\s+(?:that\\s+)?(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern WHATSAPP_NO_TEXT = Pattern.compile(
            "^(?:please\\s+)?(?:message|text)\\s+.+?\\s+(?:on|via|in)\\s+whatsapp$",
            Pattern.CASE_INSENSITIVE);

    private DeviceCommandRouter() {}

    /**
     * @return a user-facing result when a supported action was handled;
     *         null when the command is not one of the supported actions.
     */
    public static String handle(Activity activity, String rawCommand) {
        if (activity == null || rawCommand == null) return null;

        String command = rawCommand.trim();
        if (command.isEmpty()) return null;

        Matcher matcher = OPEN_AND_SEARCH.matcher(command);
        if (matcher.matches()) {
            String app = matcher.group(1).toLowerCase(Locale.ROOT);
            String query = matcher.group(2).trim();
            String url = buildSearchUrl(app, query);
            if (openUrl(activity, url)) {
                return "Opening " + displayName(app) + " and searching for " + query + ".";
            }
            return "I couldn't open that search. Check whether the app or a browser is installed.";
        }

        matcher = SEARCH_ON_APP.matcher(command);
        if (matcher.matches()) {
            String query = matcher.group(1).trim();
            String app = matcher.group(2).toLowerCase(Locale.ROOT);
            if (openUrl(activity, buildSearchUrl(app, query))) {
                return "Searching for " + query + " on " + displayName(app) + ".";
            }
            return "I couldn't open that search. Check whether the app or a browser is installed.";
        }

        matcher = WHATSAPP_DRAFT.matcher(command);
        if (matcher.matches()) {
            return openWhatsAppDraft(activity, matcher.group(1).trim());
        }

        matcher = WHATSAPP_DRAFT_TO_PERSON.matcher(command);
        if (matcher.matches()) {
            return openWhatsAppDraft(activity, matcher.group(1).trim());
        }

        matcher = WHATSAPP_TELL_PERSON.matcher(command);
        if (matcher.matches()) {
            return openWhatsAppDraft(activity, matcher.group(1).trim());
        }

        if (WHATSAPP_NO_TEXT.matcher(command).matches()) {
            return "What should I write? Say: message them on WhatsApp saying followed by your message.";
        }

        matcher = OPEN_APP.matcher(command);
        if (matcher.matches()) {
            String app = matcher.group(1).toLowerCase(Locale.ROOT);
            String packageName = packageFor(app);
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                return displayName(app) + " does not appear to be installed.";
            }
            try {
                activity.startActivity(launch);
                return "Opening " + displayName(app) + ".";
            } catch (ActivityNotFoundException error) {
                return "Android couldn't open " + displayName(app) + ".";
            }
        }

        return null;
    }

    private static String openWhatsAppDraft(Activity activity, String message) {
        if (message == null || message.trim().isEmpty()) {
            return "Please tell me what the WhatsApp message should say.";
        }

        // Official Click to Chat flow: without a phone number, WhatsApp shows
        // the contact chooser with the message prefilled. The user sends it.
        String url = "https://wa.me/?text=" + Uri.encode(message.trim());
        if (openUrl(activity, url)) {
            return "WhatsApp is open with your message drafted. Choose the recipient and review it, then tap Send yourself.";
        }
        return "I couldn't open WhatsApp. Check whether it is installed.";
    }

    private static boolean openUrl(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException error) {
            return false;
        }
    }

    private static String buildSearchUrl(String app, String query) {
        String encoded = Uri.encode(query);
        if ("youtube".equals(app)) {
            return "https://www.youtube.com/results?search_query=" + encoded;
        }
        return "https://www.instagram.com/explore/search/keyword/?q=" + encoded;
    }

    private static String packageFor(String app) {
        switch (app) {
            case "youtube":
                return "com.google.android.youtube";
            case "instagram":
                return "com.instagram.android";
            default:
                return "com.whatsapp";
        }
    }

    private static String displayName(String app) {
        return app.substring(0, 1).toUpperCase(Locale.ROOT) + app.substring(1);
    }
}
