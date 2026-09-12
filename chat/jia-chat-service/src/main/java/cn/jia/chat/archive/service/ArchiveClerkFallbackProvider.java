package cn.jia.chat.archive.service;

import java.util.Objects;

public final class ArchiveClerkFallbackProvider implements ArchiveQuestionProvider {
    @Override
    public Answer answer(Request request) {
        Objects.requireNonNull(request, "request");
        String answer = "案卷书吏答：据所选原文，问题“" + request.question()
                + "”可结合以下文字理解：\n\n" + request.selectedText();
        return Answer.complete(answer);
    }
}
