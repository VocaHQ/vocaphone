import Testing

/// Two halves, tested apart: what repair is allowed to remove, and what it is
/// allowed to add. The cases that matter most are the ones asserting it does
/// *neither* — a rule that fires on a word someone meant is worse than the
/// filler it was written to catch.
struct TranscriptRepairTests {

    // MARK: - Fillers

    @Test func hesitationSoundsAreRemoved() {
        #expect(TranscriptRepair.apply("um we should ship it") == "we should ship it")
        #expect(TranscriptRepair.apply("we should uh ship it") == "we should ship it")
        #expect(TranscriptRepair.apply("we should er ship it") == "we should ship it")
        #expect(TranscriptRepair.apply("erm we should ship it") == "we should ship it")
    }

    /// However long the model drew the sound out, it is the same sound.
    @Test func elongatedFillersAreRemoved() {
        #expect(TranscriptRepair.apply("ummmm we should ship it") == "we should ship it")
        #expect(TranscriptRepair.apply("we uhhh should ship it") == "we should ship it")
        #expect(TranscriptRepair.apply("hmmm we should ship it") == "we should ship it")
    }

    /// A filler set off by commas takes both of them with it, or the sentence
    /// is left with a comma that separates nothing.
    @Test func commasAroundAFillerGoWithIt() {
        #expect(TranscriptRepair.apply("I think, um, we should ship") == "I think we should ship")
        #expect(TranscriptRepair.apply("um, we should ship it") == "we should ship it")
    }

    /// The filler goes; the sentence it happened to end does not.
    @Test func aFillerKeepsTheSentenceItClosed() {
        #expect(
            TranscriptRepair.apply("I was thinking. Um. We should ship it")
                == "I was thinking. We should ship it"
        )
    }

    /// These are answers. A transcript that drops them says the opposite of
    /// what was said.
    @Test func affirmationsSurvive() {
        #expect(TranscriptRepair.apply("mhm that works for me") == "mhm that works for me")
        #expect(TranscriptRepair.apply("uh-huh that works") == "uh-huh that works")
        #expect(TranscriptRepair.apply("huh") == "huh")
    }

    /// Every one of these is a real word often enough that removing it needs
    /// judgement about what the speaker meant, which this stage does not have.
    @Test func realWordsAreNeverTreatedAsFillers() {
        let source = "I mean it is like you know actually pretty good so"
        #expect(TranscriptRepair.apply(source) == source)
        #expect(TranscriptRepair.apply("ah well oh dear") == "ah well oh dear")
    }

    /// Quoted, the filler is being talked about rather than said.
    @Test func aQuotedFillerIsKept() {
        #expect(
            TranscriptRepair.apply("he said \"um\" a lot") == "he said \"um\" a lot"
        )
    }

    /// Only in the language that has them: a German "eh" is not a French one.
    @Test func localFillersNeedTheirLanguage() {
        #expect(
            TranscriptRepair.apply("wir sollten ähm morgen liefern", language: "de")
                == "wir sollten morgen liefern"
        )
        // "eh" is Spanish hesitation and an English interjection, so it goes
        // only when the transcript is Spanish.
        #expect(
            TranscriptRepair.apply("eh deberíamos enviarlo", language: "es")
                == "deberíamos enviarlo"
        )
        #expect(TranscriptRepair.apply("eh what was that", language: "en") == "eh what was that")
    }

    /// Repairing a transcript down to nothing is a rule misfiring, not silence.
    @Test func aTranscriptIsNeverRepairedAway() {
        #expect(TranscriptRepair.apply("um") == "um")
        #expect(TranscriptRepair.apply("um uh") == "um uh")
    }

    // MARK: - False starts

    @Test func aRepeatedPhraseCollapses() {
        #expect(
            TranscriptRepair.apply("we should we should probably ship it")
                == "we should probably ship it"
        )
        #expect(TranscriptRepair.apply("I think I think so") == "I think so")
    }

    /// The second copy is the one the speaker carried on from, so it is the one
    /// that keeps its punctuation.
    @Test func theCompletedCopyIsTheOneKept() {
        #expect(
            TranscriptRepair.apply("we should, we should probably ship")
                == "we should probably ship"
        )
    }

    @Test func aDoubledFunctionWordCollapses() {
        #expect(TranscriptRepair.apply("the the tests are green") == "the tests are green")
        #expect(TranscriptRepair.apply("send it to to me") == "send it to me")
    }

    /// A repeated content word is emphasis the speaker meant.
    @Test func aDoubledContentWordSurvives() {
        #expect(TranscriptRepair.apply("that is very very good") == "that is very very good")
        #expect(TranscriptRepair.apply("no no not that one") == "no no not that one")
    }

    /// Both of these are ordinary English and both look exactly like a stutter.
    @Test func legitimateDoublesSurvive() {
        #expect(TranscriptRepair.apply("she had had enough") == "she had had enough")
        #expect(
            TranscriptRepair.apply("the thing that that man said") == "the thing that that man said"
        )
    }

    /// A sentence ended between the copies, so the second starts a new thought.
    @Test func aRepeatAcrossASentenceBoundarySurvives() {
        #expect(TranscriptRepair.apply("I went home. Home is quiet") == "I went home. Home is quiet")
    }

    // MARK: - Marks that are already there

    @Test func spacingAroundMarksIsNormalized() {
        #expect(TranscriptRepair.apply("hello there ,how are you") == "hello there, how are you")
        #expect(TranscriptRepair.apply("one.two.three") == "one. two. three")
    }

    /// A bare hostname has to end in something that is actually a domain, or
    /// "the report.Then I left" is masked as one and the missing space after
    /// the full stop can never be repaired.
    @Test func aDottedPairOfWordsIsNotAHostname() {
        #expect(
            TranscriptRepair.apply("I finished the report.Then I left")
                == "I finished the report. Then I left"
        )
        #expect(
            TranscriptRepair.apply("visit example.com/a.b. thanks")
                == "visit example.com/a.b. thanks"
        )
    }

    @Test func runsOfMarksCollapse() {
        #expect(TranscriptRepair.apply("that is great!!!") == "that is great!")
        #expect(TranscriptRepair.apply("really?? I did not know") == "really? I did not know")
        #expect(TranscriptRepair.apply("wait for it....") == "wait for it...")
        // Three is an ellipsis and stays one.
        #expect(TranscriptRepair.apply("wait for it...") == "wait for it...")
    }

    @Test func aSeparatorTouchingATerminatorLoses() {
        #expect(TranscriptRepair.apply("that is all ,. thanks") == "that is all. thanks")
    }

    @Test func orphanedMarksAreDropped() {
        #expect(TranscriptRepair.apply(", we should ship it,") == "we should ship it")
    }

    // MARK: - Marks that are missing

    /// The whole complaint, end to end.
    @Test func aRunOnGetsItsSentencesBack() {
        #expect(
            TranscriptRepair.apply(
                "so i was thinking that um we should we should probably ship it on "
                    + "friday but i dont know if the the tests are green okay lets check"
            )
                == "so i was thinking that we should probably ship it on friday, "
                + "but i dont know if the tests are green. okay, lets check"
        )
    }

    @Test func aConjunctionJoiningTwoClausesTakesAComma() {
        #expect(
            TranscriptRepair.apply("we can ship it on friday but i need the tests")
                == "we can ship it on friday, but i need the tests"
        )
    }

    /// Not every "but" joins two clauses, and not every clause is long enough
    /// to be sure it is one.
    @Test func aConjunctionInsideAClauseIsLeftAlone() {
        #expect(TranscriptRepair.apply("nothing but trouble here") == "nothing but trouble here")
        #expect(TranscriptRepair.apply("slow but steady wins") == "slow but steady wins")
        // "so that" is a phrase, not a new clause.
        #expect(
            TranscriptRepair.apply("i moved the file so that the tests would pass")
                == "i moved the file so that the tests would pass"
        )
    }

    @Test func anOpeningDiscourseMarkerTakesAComma() {
        #expect(TranscriptRepair.apply("okay lets ship it") == "okay, lets ship it")
        #expect(TranscriptRepair.apply("actually i changed my mind") == "actually, i changed my mind")
    }

    /// "so" and "well" open a sentence far more often without a comma than
    /// with one, which is why neither is in the list.
    @Test func soAndWellDoNotTakeAComma() {
        #expect(TranscriptRepair.apply("so i was thinking about it") == "so i was thinking about it")
        #expect(TranscriptRepair.apply("well that settles it then") == "well that settles it then")
    }

    /// "okay" after a copula is an adjective describing something, not somebody
    /// starting a sentence.
    @Test func okayAsAnAdjectiveDoesNotSplit() {
        #expect(
            TranscriptRepair.apply("make sure the build is okay before you ship it")
                == "make sure the build is okay before you ship it"
        )
    }

    @Test func questionsGetAQuestionMark() {
        #expect(TranscriptRepair.apply("what time is it") == "what time is it?")
        #expect(TranscriptRepair.apply("do you want coffee") == "do you want coffee?")
        #expect(TranscriptRepair.apply("how many people are coming") == "how many people are coming?")
        // A model that closed a question with a full stop is corrected.
        #expect(TranscriptRepair.apply("can you send it.") == "can you send it?")
    }

    /// A wh-word opening a noun clause is not a question, and the giveaway is a
    /// subject sitting between it and the verb.
    @Test func aStatementStartingWithAWhWordIsNotAQuestion() {
        #expect(TranscriptRepair.apply("what i meant was simple") == "what i meant was simple")
        #expect(TranscriptRepair.apply("how he did it is unclear") == "how he did it is unclear")
        #expect(
            TranscriptRepair.apply("when i get there i will call you")
                == "when i get there i will call you"
        )
    }

    /// Someone chose the exclamation mark. Repair does not overrule it.
    @Test func anExclamationIsLeftAlone() {
        #expect(TranscriptRepair.apply("what a day!") == "what a day!")
    }

    // MARK: - Spans nothing may touch

    @Test func protectedSpansSurviveEveryStage() {
        #expect(
            TranscriptRepair.apply("um send it to me@example.com at 3.30 today")
                == "send it to me@example.com at 3.30 today"
        )
        #expect(
            TranscriptRepair.apply("open https://example.com/a.b and uh tell me")
                == "open https://example.com/a.b and tell me"
        )
        #expect(TranscriptRepair.apply("it is the 1st of may") == "it is the 1st of may")
    }

    // MARK: - Other scripts

    /// The inference rules are written against how English builds a sentence.
    /// In a script that terminates differently they would do damage, so they
    /// do not run at all.
    @Test func inferenceIsSkippedOutsideLatinLayout() {
        let hindi = "मैं कल बाजार जाऊंगा"
        #expect(TranscriptRepair.apply(hindi, language: "hi") == hindi)
        let japanese = "えーと明日出荷します"
        #expect(TranscriptRepair.apply(japanese, language: "ja") == "明日出荷します")
    }

    /// Another Latin language keeps its layout, and the English trigger words
    /// simply never match — which is the point of choosing English-only words.
    @Test func anotherLatinLanguageIsUntouched() {
        let french = "nous devrions livrer vendredi mais je ne sais pas"
        #expect(TranscriptRepair.apply(french, language: "fr") == french)
    }

    @Test func nothingInMeansNothingOut() {
        #expect(TranscriptRepair.apply(nil).isEmpty)
        #expect(TranscriptRepair.apply("   ").isEmpty)
    }
}
