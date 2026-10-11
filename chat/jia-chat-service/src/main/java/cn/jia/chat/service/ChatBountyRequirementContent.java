package cn.jia.chat.service;

/** The single exact rendering of an immutable requirement used by admission and execution. */
final class ChatBountyRequirementContent {
    private ChatBountyRequirementContent() { }

    static String render(String title, String description) {
        if (title == null || title.isBlank()) {
            throw new IllegalStateException("Exact requirement title is unavailable");
        }
        // Quick intake may store the same demand twice. Never normalize either field or
        // deduplicate paragraphs within a field: only exact whole-field equality is special.
        return title + (description == null || description.isEmpty() || description.equals(title)
                ? "" : "\n\n" + description);
    }
}
