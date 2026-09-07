package cn.jia.chat.archive.service;

import java.util.List;

public interface ArchiveQuestionProvider {
    default boolean available() {
        return true;
    }

    Answer answer(Request request) throws Exception;

    record Request(String questionId, String question, String selectedText) { }

    record Answer(List<String> deltas) {
        public Answer {
            deltas = List.copyOf(deltas);
            if (deltas.isEmpty() || deltas.stream().anyMatch(value -> value == null || value.isEmpty())) {
                throw new IllegalArgumentException("Archive provider answer requires non-empty deltas");
            }
        }

        public static Answer complete(String answer) {
            return new Answer(List.of(answer));
        }
    }
}
