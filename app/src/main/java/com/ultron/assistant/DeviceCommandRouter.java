package com.ultron.assistant;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles a small allowlist of permission-conscious device actions.
 *
 * Search/open actions use Android ACTION_VIEW links. WhatsApp messages are
 * opened as drafts only; the user chooses the recipient and taps Send.
 */
public final class DeviceCommandRouter {

    private static final Pattern APP_FIRST_SEARCH = Pattern.compile(
            ".*\\b(youtube|instagram|google)\\b.*\\b(?:search|find|look\\s+up)\\b\\s+(?:for\\s+)?(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern SEARCH_APP_FOR = Pattern.compile(
            ".*\\b(?:search|find|look\\s+up)\\s+(youtube|instagram|google)\\s+(?:for\\s+)?(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern SEARCH_ON_APP = Pattern.compile(
            ".*\\b(?:search|find|look\\s+up)\\b\\s+(?:for\\s+)?(.+?)\\s+\\b(?:on|in)\\s+(youtube|instagram|google)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern OPEN_APP = Pattern.compile(
            "^(?:please\\s+)?(?:open|launch|start)\\s+(youtube|instagram|whatsapp|google)$",
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

        String command = stripWakePhrase(rawCommand.trim());
        if (command.isEmpty()) return null;

        SearchRequest searchRequest = extractSearchRequest(command);
        if (searchRequest != null) {
            String query = searchRequest.query;
            if (openSearch(activity, searchRequest.app, query)) {
                return "Opening " + displayName(searchRequest.app)
                        + " search for " + query + ".";
            }
            return "I couldn't open that search. Check whether the app or a browser is installed.";
        }

        Matcher matcher;

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

    /**
     * Result returned by the closed allowlist of model-requested device actions.
     */
    public static final class ToolActionResult {
        public final boolean success;
        public final String message;

        ToolActionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * Executes only three declared, allowlisted tools. Tool arguments are
     * untrusted model output and are validated again before Android intents run.
     * There is deliberately no function for sending a WhatsApp message.
     */
    public static ToolActionResult executeTool(
            Activity activity, String toolName, Map<String, String> arguments) {
        if (activity == null || toolName == null || arguments == null) {
            return new ToolActionResult(false, "The requested action was invalid.");
        }

        if ("open_app".equals(toolName)) {
            String app = arguments.get("app");
            if (!isAllowedApp(app)) {
                return new ToolActionResult(false, "That app is not on ULTRON's approved app list.");
            }

            String packageName = packageFor(app.toLowerCase(Locale.ROOT));
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                return new ToolActionResult(false, displayName(app) + " does not appear to be installed.");
            }
            try {
                activity.startActivity(launch);
                return new ToolActionResult(true, "Opening " + displayName(app) + ".");
            } catch (ActivityNotFoundException error) {
                return new ToolActionResult(false, "Android couldn't open " + displayName(app) + ".");
            }
        }

        if ("search_app".equals(toolName)) {
            String app = arguments.get("app");
            String query = arguments.get("query");
            if (!isAllowedSearchApp(app) || query == null) {
                return new ToolActionResult(false, "The search request was invalid.");
            }
            query = query.trim();
            if (query.isEmpty() || query.length() > 200) {
                return new ToolActionResult(false, "Please use a search query between 1 and 200 characters.");
            }
            String safeApp = app.toLowerCase(Locale.ROOT);
            if (openSearch(activity, safeApp, query)) {
                return new ToolActionResult(true, "Opening " + displayName(safeApp)
                        + " search for " + query + ".");
            }
            return new ToolActionResult(false, "I couldn't open that search.");
        }

        if ("draft_whatsapp_message".equals(toolName)) {
            String message = arguments.get("message");
            if (message == null || message.trim().isEmpty() || message.length() > 1000) {
                return new ToolActionResult(false, "The WhatsApp draft must contain 1 to 1000 characters.");
            }
            String url = "https://wa.me/?text=" + Uri.encode(message.trim());
            if (openUrl(activity, url)) {
                return new ToolActionResult(true,
                        "WhatsApp is open with a draft. Choose the recipient, review it, and tap Send yourself.");
            }
            return new ToolActionResult(false, "I couldn't open WhatsApp.");
        }

        return new ToolActionResult(false, "That action is not available in ULTRON.");
    }

    private static boolean isAllowedApp(String app) {
        if (app == null) return false;
        String value = app.trim().toLowerCase(Locale.ROOT);
        return "youtube".equals(value) || "instagram".equals(value)
                || "whatsapp".equals(value) || "google".equals(value);
    }

    private static boolean isAllowedSearchApp(String app) {
        if (app == null) return false;
        String value = app.trim().toLowerCase(Locale.ROOT);
        return "youtube".equals(value) || "instagram".equals(value)
                || "google".equals(value);
    }

    private static final class SearchRequest {
        final String app;
        final String query;

        SearchRequest(String app, String query) {
            this.app = app;
            this.query = query;
        }
    }

    private static String stripWakePhrase(String command) {
        return command.replaceFirst(
                "(?i)^[\\s,.:;!?]*(?:(?:hey|okay|ok)\\s+)?ultron\\b[,!:.]?\\s*",
                "").trim();
    }

    private static SearchRequest extractSearchRequest(String command) {
        Matcher matcher = APP_FIRST_SEARCH.matcher(command);
        String app;
        String query;

        if (matcher.matches()) {
            app = matcher.group(1).toLowerCase(Locale.ROOT);
            query = matcher.group(2);
        } else {
            matcher = SEARCH_APP_FOR.matcher(command);
            if (matcher.matches()) {
                app = matcher.group(1).toLowerCase(Locale.ROOT);
                query = matcher.group(2);
            } else {
                matcher = SEARCH_ON_APP.matcher(command);
                if (!matcher.matches()) return null;
                query = matcher.group(1);
                app = matcher.group(2).toLowerCase(Locale.ROOT);
            }
        }

        query = query == null ? "" : query.trim();
        query = query.replaceAll("[\\s,.;:!?]+$", "").trim();
        if (query.isEmpty()) return null;

        return new SearchRequest(app, query);
    }

    private static boolean openSearch(Activity activity, String app, String query) {
        String url = buildSearchUrl(app, query);
        String packageName = packageFor(app);

        // Prefer the requested app, then fall back to the user's browser.
        try {
            Intent appIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            appIntent.setPackage(packageName);
            activity.startActivity(appIntent);
            return true;
        } catch (ActivityNotFoundException ignored) {
            return openUrl(activity, url);
        }
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
        if ("instagram".equals(app)) {
            return "https://www.instagram.com/explore/search/keyword/?q=" + encoded;
        }
        return "https://www.google.com/search?q=" + encoded;
    }

    private static String packageFor(String app) {
        switch (app) {
            case "youtube":
                return "com.google.android.youtube";
            case "instagram":
                return "com.instagram.android";
            case "google":
                return "com.google.android.googlequicksearchbox";
            default:
                return "com.whatsapp";
        }
    }

    private static String displayName(String app) {
        return app.substring(0, 1).toUpperCase(Locale.ROOT) + app.substring(1);
    }
}
