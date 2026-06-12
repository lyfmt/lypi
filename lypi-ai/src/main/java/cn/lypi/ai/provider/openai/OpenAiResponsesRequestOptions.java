package cn.lypi.ai.provider.openai;

record OpenAiResponsesRequestOptions(
    boolean previousResponseStateEnabled,
    boolean replayToolInteractionsAsProtocolItems
) {
    static OpenAiResponsesRequestOptions defaults() {
        return withPreviousResponseState(true);
    }

    static OpenAiResponsesRequestOptions withPreviousResponseState(boolean enabled) {
        return new OpenAiResponsesRequestOptions(enabled, false);
    }

    static OpenAiResponsesRequestOptions fallbackWithoutPreviousResponseState() {
        return new OpenAiResponsesRequestOptions(false, true);
    }
}
