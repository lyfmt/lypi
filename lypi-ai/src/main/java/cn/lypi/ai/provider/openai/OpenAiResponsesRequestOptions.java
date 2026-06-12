package cn.lypi.ai.provider.openai;

record OpenAiResponsesRequestOptions(boolean previousResponseStateEnabled) {
    static OpenAiResponsesRequestOptions defaults() {
        return withPreviousResponseState(true);
    }

    static OpenAiResponsesRequestOptions withPreviousResponseState(boolean enabled) {
        return new OpenAiResponsesRequestOptions(enabled);
    }
}
