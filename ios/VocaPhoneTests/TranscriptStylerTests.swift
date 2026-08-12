import Testing

struct TranscriptStylerTests {
    @Test func localStylesMatchGatewayExamples() {
        let source = "hello there. how are you"
        #expect(TranscriptStyler.apply(source, style: .raw) == source)
        #expect(TranscriptStyler.apply(source, style: .clean) == "hello there. how are you.")
        #expect(TranscriptStyler.apply(source, style: .formal) == "Hello there. How are you.")
        #expect(TranscriptStyler.apply(source, style: .casual) == "Hello there. How are you")
        #expect(TranscriptStyler.apply(source, style: .veryCasual) == "hello there, how are you")
        #expect(TranscriptStyler.apply(source, style: .excited) == "Hello there! How are you!")
    }

    /// The case that made plumbing the detected language back from the on-device
    /// engines worth doing: "auto" can only guess from the text, and
    /// unpunctuated Devanagari looks exactly like Latin to that guess.
    @Test func anUnpunctuatedScriptNeedsTheDetectedLanguageNotAuto() {
        let hindi = "मैं कल बाजार जाऊंगा"
        #expect(TranscriptStyler.apply(hindi, style: .formal, language: "hi") == "मैं कल बाजार जाऊंगा।")
        #expect(TranscriptStyler.apply(hindi, style: .formal, language: "auto") == "मैं कल बाजार जाऊंगा.")
        // Once the model has punctuated it, sniffing the text is enough.
        #expect(
            TranscriptStyler.apply(
                "मैं कल बाजार जाऊंगा। वह ठीक है", style: .formal, language: "auto"
            ) == "मैं कल बाजार जाऊंगा। वह ठीक है।"
        )
    }

    @Test func localStylingKeepsProtectedSpansIntact() {
        #expect(
            TranscriptStyler.apply(
                "Email John@Example.com at 3:30.",
                style: .veryCasual
            ) == "email John@Example.com at 3:30"
        )
    }

    @Test func localStylingUsesLanguagePunctuation() {
        #expect(
            TranscriptStyler.apply(
                "家に帰りました。ジョンが電話してきました。",
                style: .excited,
                language: "ja"
            ) == "家に帰りました！ジョンが電話してきました！"
        )
        #expect(
            TranscriptStyler.apply(
                "मैं कल बाजार जाऊंगा",
                style: .formal,
                language: "hi"
            ) == "मैं कल बाजार जाऊंगा।"
        )
    }
}
